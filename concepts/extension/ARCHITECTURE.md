# Architecture Overview

> This document describes the conceptual architecture of OBSERVA4J — the core modules, their responsibilities, and how they interact. It is a design-phase document; implementation details will be refined during development.

---

## System Context

OBSERVA4J sits **inside** a Quarkus service. It is not a sidecar, not an agent, and not a separate process. It is a library that the service's code depends on directly.

```text
┌─────────────────────────────────────────────────────┐
│                   Quarkus Service                   │
│                                                     │
│  ┌──────────────┐     ┌─────────────────────────┐  │
│  │ Business     │────▶│      OBSERVA4J           │  │
│  │ Logic        │     │                         │  │
│  └──────────────┘     │  StructuredLogger       │  │
│                       │  ObservabilityContext   │  │
│  ┌──────────────┐     │  AuditWriter            │  │
│  │ JAX-RS       │────▶│  ExceptionReporter      │  │
│  │ Endpoints    │     │  HealthContributor      │  │
│  └──────────────┘     └────────────┬────────────┘  │
│                                    │               │
└────────────────────────────────────┼───────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              │                      │                       │
              ▼                      ▼                       ▼
     Log Aggregator           Tracing Backend         Metrics Backend
  (Elasticsearch/Loki)      (Jaeger/Zipkin/OTLP)   (Prometheus/Grafana)
```

---

## Core Modules

### 1. `observa4j-core`

The central module. Defines the primary abstractions and the request-scoped
context lifecycle.

| Component | Type | Description |
|---|---|---|
| `ObservabilityContext` | Java record | Carrier for all context fields: `trace_id`, `span_id`, `request_id`, `user_id`, `hostname`, `pid` |
| `ObservabilityContextProducer` | CDI `@RequestScoped` producer | Creates and destroys `ObservabilityContext` for each HTTP request |
| `StructuredLogger` | CDI `@ApplicationScoped` bean | Primary logging API; wraps SLF4J; enforces 5 Ws; auto-attaches `ObservabilityContext` |
| `ObservabilityEvent` | Sealed interface | Type hierarchy for `TechnicalEvent` and `BusinessEvent` |
| `RequestIdFilter` | JAX-RS `ContainerRequestFilter` | Generates `request_id` and populates `ObservabilityContext` at request entry |
| `FieldNameAdapter` | CDI interface | Remaps canonical field names to platform conventions at output time; selected via `observa4j.fields.standard`; built-in implementations: `default`, `ecs`, `datadog`, `graylog` |
### 2. `observa4j-tracing`

Integrates OpenTelemetry for distributed tracing.

| Component | Type | Description |
| --- | --- | --- |
| `TraceContextExtractor` | Utility class | Extracts `trace_id` and `span_id` from the active OpenTelemetry span |
| `TraceContextPropagator` | JAX-RS `ClientRequestFilter` | Injects W3C `traceparent` header into outbound HTTP calls |
| `SpanRecorder` | CDI bean | Records span start/end times and exports via the configured exporter |

### 3. `observa4j-audit`

Audit logging with annotation-driven interception.

| Component | Type | Description |
| --- | --- | --- |
| `@Auditable` | CDI interceptor binding annotation | Marks methods for automatic audit event emission |
| `AuditInterceptor` | CDI interceptor | Captures before/after state; emits `AuditRecord` via `StructuredLogger` |
| `AuditRecord` | Java record | Immutable audit event: actor, action, entity, states, timestamp, trace_id |
| `AuditWriter` | CDI interface | Emits the `AuditRecord` as a structured JSON log event — no built-in persistence implementations |

```text
     └──▶ AuditWriter
               │
               ▼
          JSON log stream (event_type: AUDIT_*)
               │
               ▼
          Consumer pipeline (separate process — platform responsibility)
          e.g. Logstash → RDBMS, Kafka consumer, S3 with Object Lock
```

---

### 4. `observa4j-metrics`

Telemetry and metrics via Micrometer.

| Component | Type | Description |
| --- | --- | --- |
| `@Logged` | CDI interceptor binding annotation | Marks methods for automatic timing and logging |
| `LoggingInterceptor` | CDI interceptor | Records method execution time via `MeterRegistry` |
| `BusinessEventMetrics` | CDI bean | Emits Micrometer counters when `BusinessEvent` instances are logged |

### 5. `observa4j-health`

Health Check API implementation.

| Component | Type | Description |
| --- | --- | --- |
| `HealthContributor` | CDI interface | Extensible health check contract |
| `DatabaseHealthContributor` | `@Readiness` impl | Checks connection pool status |
| `ExternalApiHealthContributor` | `@Readiness` impl | Checks reachability of configured downstream URLs |
| `DiskSpaceHealthContributor` | `@Liveness` impl | Checks available disk space |

### 6. `observa4j-exceptions`

Centralised exception tracking.

| Component | Type | Description |
| --- | --- | --- |
| `ExceptionReporter` | CDI bean | Enriches, de-duplicates, and forwards exceptions to the configured backend |
| `ExceptionFingerprinter` | Utility class | Computes a stable fingerprint from exception class + stack frames |
| `GlobalExceptionHandler` | JAX-RS `ExceptionMapper` | Catches all unhandled exceptions; delegates to `ExceptionReporter` |
| `ExceptionTrackingBackend` | CDI interface | Pluggable backend: `SentryBackend`, `WebhookBackend` |

---

## Request Lifecycle

The following sequence shows how context is created, propagated, and cleaned up for a typical HTTP request:

```text
1. HTTP request arrives
      │
      ▼
2. RequestIdFilter (JAX-RS ContainerRequestFilter)
   - Generates request_id (UUID)
   - Extracts or generates trace_id from W3C traceparent header
   - Populates ObservabilityContext (@RequestScoped)
   - Populates MDC: request_id, trace_id, user_id, hostname
      │
      ▼
3. Business method executes
   - @Logged interceptor: records start time, puts class/method in MDC
   - @Auditable interceptor: captures entity state before
   - StructuredLogger.log(): auto-attaches full ObservabilityContext to every event
   - Outbound HTTP calls: TraceContextPropagator injects traceparent header
      │
      ▼
4. Response committed
   - @Logged interceptor finally block: records duration metric; clears class/method from MDC
   - @Auditable interceptor: captures entity state after; writes AuditRecord
      │
      ▼
5. Request scope destroyed
   - ObservabilityContextProducer @PreDestroy: MDC.clear()
   - All @RequestScoped beans destroyed
```

---

## Data Flow

```text
Business Event
      │
      ▼
StructuredLogger ──────────────────────────────────────────┐
      │                                                     │
      ├── attaches ObservabilityContext                     │
      │   (trace_id, request_id, user_id, hostname, pid)    │
      │                                                     │
      ├── serialises to JSON via SLF4J 2.x key-value API    │
      │                                                     ▼
      │                                            JSON Log Line
      │                                            (stdout / file)
      │                                                     │
      │                                                     ▼
      │                                          Log Aggregation Pipeline
      │                                          (Fluentd → Elasticsearch)
      │
      ├── emits BusinessEvent counter (if event_type present)
      │   via Micrometer → Prometheus → Grafana
      │
      └── (if exception) ExceptionReporter
              │
              ├── enriches with ObservabilityContext
              ├── computes fingerprint
              └── forwards to ExceptionTrackingBackend
```

---

## Configuration Overview

All OBSERVA4J configuration is under the `observa4j` prefix in `application.properties`:

```properties
# Core
observa4j.service.name=order-service
observa4j.context.hostname-auto=true

# Tracing — sampler is always_on; sampling rates are configured in the OTel Collector, not here
observa4j.tracing.exporter=otlp
observa4j.tracing.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}

# Exceptions
observa4j.exceptions.reporter=sentry
observa4j.exceptions.sentry.dsn=https://...

# Health
observa4j.health.disk.min-free-gb=5
observa4j.health.external-apis=https://pay.example.com/health,https://ship.example.com/health

# Field name adapter
observa4j.fields.standard=default
```

---
## Production Data Flow (End-to-End)
```
User request
     │
     ▼
[Quarkus 3.27.2 + OBSERVA4J]
     │
     ├──▶ stdout (JSON — quarkus-logging-json)
     │         │
     │         ▼
     │    consumed by container runtime log driver
     │    (e.g. Docker → file, Kubernetes → node log collector)
     │
     ├──▶ GELF (quarkus-logging-gelf — UDP/TCP, direct)
     │         │
     │         ├──▶ Graylog  (fields flattened by GraylogFieldNameAdapter)
     │         └──▶ Logstash → Elasticsearch → Kibana
     │
     ├──▶ OTLP/gRPC — traces (quarkus-opentelemetry)
     │         │
     │         ▼
     │    OpenTelemetry Collector
     │         │
     │         ├──▶ Jaeger / Zipkin (trace visualisation)
     │         ├──▶ Elastic APM
     │         └──▶ Azure Application Insights
     │
     ├──▶ OTLP/gRPC — metrics (quarkus-micrometer-opentelemetry bridge)
     │         │
     │         ▼
     │    OpenTelemetry Collector  ──▶  same pipeline as traces above
     │
     ├──▶ /q/metrics (Prometheus scrape — quarkus-micrometer-registry-prometheus)
     │         │
     │         ▼
     │    Prometheus ──▶ Grafana (dashboards, alerts)
     │
     ├──▶ /q/health (quarkus-smallrye-health)
     │         │
     │         ▼
     │    Load balancer / Kubernetes probes
     │
     ├──▶ /q/info (quarkus-info)
     │         │
     │         ▼
     │    Build metadata, git commit, version — complement to /q/health
     │
     └──▶ AuditWriter
               │
               ▼
          JSON log stream (event_type: AUDIT_*)
               │
               ▼
          Consumer pipeline (separate process — platform responsibility)
```
---

## See Also

- [Vision Document](VISION.md) — scope, roadmap, and objectives
- [5 Ws Framework](concepts/FIVE_WS.md) — the logging model
- [Distributed Tracing](concepts/DISTRIBUTED_TRACING.md) — trace context propagation detail
- [Field Name Registry](reference/FIELD_NAMES.md) — all canonical field names
- [Backend Output Contract](reference/BACKENDS.md) — signals, protocols, and formats
- [Integration Guide](guides/INTEGRATION_GUIDE.md) — connecting to Graylog, ELK, Grafana, Azure
