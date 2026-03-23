# Output Contract — Supported Backends

> **Audience:** Developers using or extending OBSERVA4J.
>
> **Scope:** This document defines what the extension emits — the signals, protocols, formats, and field conventions it guarantees for each supported backend category. It does not prescribe which backend to choose or how to configure backend infrastructure. For infrastructure setup and operational guidance, see [`docs/guides/INTEGRATION_GUIDE.md`](../guides/INTEGRATION_GUIDE.md).

---

## Signal Overview

OBSERVA4J emits three classes of signal simultaneously and independently. Each signal has a fixed format and transport that the extension controls:

| Signal | Format | Transport | Controlled by |
|---|---|---|---|
| **Logs** | JSON (structured, flat or nested per adapter) | stdout and/or GELF | `quarkus-logging-json` + `quarkus-logging-gelf` |
| **Traces** | OpenTelemetry spans | OTLP/gRPC | `quarkus-opentelemetry` |
| **Metrics** | Micrometer + OTLP bridge | Prometheus scrape + OTLP/gRPC | `quarkus-micrometer` + `quarkus-micrometer-opentelemetry` |

No signal depends on another being active. A deployment that receives only traces (e.g., Azure Application Insights via OTLP) does not require the GELF log handler to be enabled.

---

## Log Signal

### Format

All log events are emitted as flat JSON objects. The top-level structure is fixed:

```json
{
  "@timestamp": "2026-03-09T14:32:01.123Z",
  "event_type": "ORDER_COMPLETED",
  "message": "Order processed successfully",
  "severity": "INFO",
  "trace_id": "7d2c8e4f1a3b9c2da3f9c2d17b441234",
  "span_id": "a3f9c2d17b44",
  "request_id": "b1c3d4e5-f6a7-...",
  "user_id": "USR-445",
  "service_name": "order-service",
  "hostname": "app-node-03",
  "pid": 1
}
```

Fields with nested structure (e.g., `exception`, `state_before`, `state_after`) are emitted as nested objects by default and flattened by the `FieldNameAdapter` when the active standard requires it. See [Field Names](FIELD_NAMES.md#nested-object-fields) for the full object structure.

### Transports

The extension supports two simultaneous log transports:

| Transport | Activation | Format delivered |
|---|---|---|
| **stdout** | Always active when `quarkus-logging-json` is present | JSON, with nested objects intact |
| **GELF** | `quarkus.log.handler.gelf.enabled=true` | GELF additional fields (scalar only — nested objects flattened by adapter) |

Both transports receive the same event. Enabling GELF does not disable stdout.

### Field Name Adapter

The `FieldNameAdapter` remaps canonical field names at output time. It does not alter values — only names. The active adapter is selected via:

```properties
observa4j.fields.standard=default|ecs|datadog|graylog
```

| Standard | Target | Effect |
|---|---|---|
| `default` | Any OTLP-native backend | No remapping; canonical names used as-is |
| `ecs` | Elastic Common Schema | `trace_id` → `trace.id`, `span_id` → `span.id`, `user_id` → `user.id` |
| `datadog` | Datadog | `trace_id` → `dd.trace_id`, `span_id` → `dd.span_id`, `user_id` → `usr.id` |
| `graylog` | GELF transport | Flattens all nested objects: `exception.class` → `exception_class` |

> `trace_id` and `span_id` are injected by Quarkus via `quarkus-opentelemetry`. OBSERVA4J reads them from MDC but never writes to these fields directly. The adapter remaps names; it does not generate or alter the underlying OTel identifiers.

A custom adapter can be provided via CDI:

```java
@ApplicationScoped
public class MyFieldNameAdapter implements FieldNameAdapter {
    @Override
    public String adapt(String canonicalName) {
        // return the platform-specific name for this field
    }
}
```

---

## Trace Signal

### Format

Spans are emitted in OpenTelemetry format via OTLP/gRPC. The extension does not define a custom span format — it uses the OpenTelemetry Java SDK span model as produced by `quarkus-opentelemetry`.

### What the extension adds to spans

OBSERVA4J annotates spans with attributes that are not emitted by default Quarkus instrumentation:

| Span attribute | Source | Condition |
|---|---|---|
| `fault_tolerance.fallback=true` | `FaultToleranceListener` | When `@Fallback` is activated |
| `fault_tolerance.timeout=true` | `FaultToleranceListener` | When `@Timeout` is exceeded |
| `circuit_breaker.state` | `FaultToleranceListener` | On circuit state transition spans |
| `observa4j.event_type` | `StructuredLogger` | On log-correlated spans |

### Sampler contract

The extension configures `parentbased_always_on` as the default sampler. Sampling decisions at rates below 100% are delegated to the OpenTelemetry Collector via tail-based sampling policies. The application SDK never discards spans before they reach the Collector.

```properties
# Default — do not change without understanding tail-sampling implications
quarkus.otel.traces.sampler=parentbased_always_on
```

### Compatible backends (OTLP/gRPC)

Any backend that accepts OTLP/gRPC is compatible without additional configuration on the extension side:

| Backend | OTLP support |
|---|---|
| Jaeger | ✅ Native |
| Grafana Tempo | ✅ Native |
| Elastic APM | ✅ Via OTel Collector |
| Azure Application Insights | ✅ Via `quarkus-opentelemetry-exporter-azure` |
| Zipkin | ⚠️ Requires OTLP→Zipkin translation in OTel Collector |

---

## Metrics Signal

### Format

Metrics are emitted in two formats simultaneously:

| Format | Endpoint / transport | Backend |
|---|---|---|
| Prometheus text format | `GET /q/metrics` (HTTP scrape) | Prometheus |
| OTLP/gRPC | Pushed to configured OTLP endpoint | Any OTLP-native backend |

The `quarkus-micrometer-opentelemetry` bridge causes all Micrometer meters to be exported via both channels without duplication at the application level.

### Extension-defined metrics

In addition to the standard Quarkus/Micrometer JVM and HTTP metrics, OBSERVA4J registers the following meters:

| Metric name | Type | Labels | Description |
|---|---|---|---|
| `observa4j_circuit_breaker_state_transitions_total` | Counter | `circuit`, `state` | Incremented on each circuit state transition |
| `observa4j_retry_attempts_total` | Histogram | `method`, `outcome` | One observation per retry attempt |
| `observa4j_audit_events_total` | Counter | `event_type`, `outcome` | Incremented per audit event emitted |
| `observa4j_fallback_activations_total` | Counter | `method` | Incremented each time a `@Fallback` path is taken |

### Scrape endpoint

The Prometheus scrape endpoint is provided by `quarkus-micrometer-registry-prometheus` and is available at:

```
GET /q/metrics
```

No authentication is applied by default. The consuming team is responsible for securing this endpoint if exposed externally.

---

## Health and Info Endpoints

These endpoints are provided by Quarkus extensions declared in the project. OBSERVA4J does not modify their output format — it adds `HealthContributor` implementations for its own internal state where applicable.

| Endpoint | Extension | Purpose |
|---|---|---|
| `GET /q/health` | `quarkus-smallrye-health` | Combined liveness + readiness |
| `GET /q/health/live` | `quarkus-smallrye-health` | Liveness probe |
| `GET /q/health/ready` | `quarkus-smallrye-health` | Readiness probe |
| `GET /q/info` | `quarkus-info` | Build metadata, git commit, version |

---

## What the Extension Does Not Control

The following are explicitly outside the extension's output contract and are the responsibility of the consuming team or platform:

- Which backend receives which signal
- Backend configuration, credentials, and topology
- OTel Collector pipeline configuration
- Prometheus scrape interval and retention
- Elasticsearch index lifecycle policies
- Graylog stream rules and extractors
- Alert rules and dashboard definitions

For guidance on configuring each backend, see [`docs/guides/INTEGRATION_GUIDE.md`](../guides/INTEGRATION_GUIDE.md).
