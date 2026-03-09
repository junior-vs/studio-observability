# Vision Document — OBSERVA4J

> Version 0.1 · DRAFT · March 2026

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Project Context](#2-project-context)
   - [The Problem](#21-the-problem)
   - [Target Audience](#22-target-audience)
3. [Objectives & Expected Benefits](#3-objectives--expected-benefits)
   - [Main Goals](#31-main-goals)
   - [Expected Benefits](#32-expected-benefits)
   - [Differentiation](#33-differentiation-from-existing-solutions)
4. [Scope](#4-scope)
   - [What the Library Will Offer](#41-what-the-library-will-offer)
   - [Out of Scope](#42-out-of-scope)
5. [Rules and Principles](#5-rules-and-principles)
   - [Coding Standards](#51-coding-standards)
   - [Naming Conventions](#52-naming-conventions)
   - [Best Practices](#53-best-practices)
6. [Fundamental Concepts](#6-fundamental-concepts)
   - [Glossary](#61-glossary)
   - [Main Abstractions](#62-main-abstractions)
7. [Simple Examples of Use](#7-simple-examples-of-use)
8. [Vision for the Future](#8-vision-for-the-future)
   - [Expected Evolution](#81-expected-evolution)
   - [Possible Extensions](#82-possible-extensions-and-integrations)
   - [Initial Roadmap](#83-initial-roadmap)
9. [Open Questions](#9-open-questions)
10. [References](#10-references)

---

## 1. Introduction

This document describes the vision for **OBSERVA4J** — a Java 21 module that provides unified, structured, and context-aware observability capabilities to Quarkus 3.x microservices. It consolidates the four pillars of modern observability into a single, cohesive library with a minimal and opinionated API surface:

- **Structured Logging** — every event carries the 5 Ws as queryable JSON
- **Distributed Tracing** — Trace IDs propagate across all service boundaries
- **Telemetry / Metrics** — counters, histograms, and gauges exported to Prometheus
- **Audit Logging** — tamper-evident records of user actions for compliance

This document draws from the following primary sources:

- Taylor Scott's talk at SolidusConf 2020 on the 5 Ws structured logging framework
- The Microservices Observability Patterns published at microservices.io by Chris Richardson
- Reference implementations from the java-design-patterns project by Iluwatar
- The OpenTelemetry and W3C TraceContext community standards

---

## 2. Project Context

### 2.1 The Problem

Modern applications built on microservice architectures face a fundamental observability challenge: individual services generate fragmented, inconsistent, and often unqueryable diagnostic data. The status quo produces the following concrete problems:

**Log walls.** Services write plain-text log lines with no structure, making machine parsing and aggregation impossible or unreliable.

**Missing context.** Log entries lack identity (who triggered the event?), spatial context (which service, queue, or container?), and causal context (why did this happen?). Post-incident investigation relies on guesswork.

**Trace fragmentation.** In a distributed system with dozens of services, a single user request may generate log entries in ten different services. Without a shared correlation identifier, these entries cannot be stitched into a coherent timeline.

**Observability entangled with business logic.** Per microservices.io, both metrics instrumentation and audit logging tend to become tightly coupled to business logic, increasing cognitive complexity and maintenance burden.

**Reactive vs. proactive monitoring.** Teams discover production problems only after user reports, rather than through automated anomaly detection over structured telemetry data.

**Exception loss.** Exceptions are logged but not de-duplicated, tracked, or routed to a notification system, resulting in silent degradation.

> **The Core Insight (SolidusConf 2020)**
>
> When you apply the 5 Ws framework to every log event, your log files stop being a wall of text and become a queryable database. Instead of running `grep` in a terminal, you run queries such as:
> _"Show me all errors (What) for user 45 (Who) in the last 10 minutes (When) on the priority queue (Where)."_

### 2.2 Target Audience

| Consumer | Primary Needs |
| --- | --- |
| **Java / Quarkus developers** | Emit structured, contextual logs and traces with minimal boilerplate; automatic MDC-equivalent context injection |
| **Platform / SRE teams** | Enforce log format standards across all services; plug into existing Prometheus, OpenTelemetry, and Elasticsearch stacks |
| **Security & Compliance teams** | Access tamper-evident audit trails of user actions for LGPD, SOC 2, and similar regulatory frameworks |
| **Business / Analytics teams** | Query business events (cart abandonment, conversion, order completion) from the same log infrastructure without a separate analytics pipeline |

---

## 3. Objectives & Expected Benefits

### 3.1 Main Goals

- **Unified observability module.** Deliver logging, distributed tracing, metrics, and auditing through a single dependency, eliminating ad-hoc library assembly.
- **5 Ws enforcement.** Guarantee that every log event produced by the library carries answers to Who, What, When, Where, and Why.
- **Structured JSON output.** Emit all log events in machine-parseable JSON, enabling direct indexing by Elasticsearch, Logstash, Loki, and similar tools.
- **Automatic context propagation.** Inject request-scoped context (Request ID, User ID, Hostname, PID) at Quarkus filter/interceptor boundaries, applying the DRY principle to observability code.
- **OpenTelemetry-native tracing.** Assign each external request a unique Trace ID and propagate it across service boundaries following the distributed tracing pattern.
- **Health Check API.** Expose a standardized `/q/health` endpoint that monitors infrastructure dependencies in addition to liveness.
- **Centralized exception tracking.** Report all uncaught exceptions to a configurable tracking backend, with automatic de-duplication and developer notification.
- **Business event logging.** Support first-class business events (e.g., `ORDER_COMPLETED`, `CHECKOUT_STARTED`) alongside technical events, transforming logs into a real-time business intelligence stream.

### 3.2 Expected Benefits

- **Reduced MTTR.** Structured, correlated logs allow engineers to isolate the cause of an incident by querying rather than scanning.
- **Proactive anomaly detection.** Consistent structure enables ML-based or threshold-based alerting — for example, detecting a surge in failed logins before a human notices an attack.
- **Regulatory audit readiness.** A persistent, queryable audit trail of user actions supports LGPD, SOC 2, and other compliance frameworks without requiring separate infrastructure.
- **Developer productivity.** Zero-config context injection means developers write business logic, not observability plumbing.
- **Business intelligence without overhead.** Structured business event logs can feed Kibana dashboards directly — no separate analytics database or third-party SDK required.
- **Dispute resolution.** Logged request and response bodies provide irrefutable evidence when debugging conflicts with third-party APIs.

### 3.3 Differentiation from Existing Solutions

| Capability | Existing Tools | OBSERVA4J Advantage |
| --- | --- | --- |
| Structured Logging | SLF4J + Logback/Log4j2 (manual config per service) | 5 Ws enforcement built-in; JSON formatter pre-configured; zero boilerplate for context fields |
| Distributed Tracing | OpenTelemetry SDK (complex instrumentation setup) | Pre-wired Quarkus integration; Trace ID / Span ID injected automatically at HTTP filter layer |
| Audit Logging | Custom hand-rolled solutions or event-sourcing frameworks | Lightweight annotation-driven `@Auditable` interceptor; persistence-agnostic |
| Business Events | Segment, Mixpanel, custom Kafka producers | First-class `BusinessEvent` type in the same log infrastructure; no additional SDK dependency |
| Health Checks | Quarkus SmallRye Health (manual `HealthCheck` impls) | Pre-built health indicators for common infrastructure; composable checks with structured output |

---

## 4. Scope

### 4.1 What the Library Will Offer

A full description of each capability is maintained in the concepts documentation. The table below summarises the scope:

| Capability | Document |
| --- | --- |
| Structured Logging — 5 Ws Framework | [FIVE_WS.md](FIVE_WS.md) |
| Log Consistency and JSON Format Standards | [STRUCTURED_LOGGING.md](STRUCTURED_LOGGING.md) |
| Tagged / Contextual Logging (MDC) | [STRUCTURED_LOGGING.md](STRUCTURED_LOGGING.md) |
| Distributed Tracing | [DISTRIBUTED_TRACING.md](DISTRIBUTED_TRACING.md) |
| Application Metrics & Telemetry | [TELEMETRY.md](TELEMETRY.md) |
| Audit Logging | [AUDIT_LOGGING.md](concepts/AUDIT_LOGGING.md) |
| Exception Tracking | [EXCEPTION_TRACKING.md](concepts/EXCEPTION_TRACKING.md) |
| Health Check API | [HEALTH_CHECK.md](concepts/HEALTH_CHECK.md) |
| Business Value via Structured Logs | [FIVE_WS.md#business-value](concepts/FIVE_WS.md#6-business-value-beyond-debugging) |

### 4.2 Out of Scope

The following capabilities are explicitly excluded from the initial scope:

- **Log aggregation infrastructure.** OBSERVA4J emits structured logs to stdout/file; the aggregation pipeline (Fluentd, Logstash, Vector) is the consumer's responsibility.
- **Trace storage and UI.** The library exports spans; operating a Jaeger or Zipkin server is outside the library's boundary.
- **Metrics storage and dashboards.** The library exposes a `/q/metrics` endpoint; configuring Prometheus scraping and Grafana dashboards is out of scope.
- **Non-Quarkus frameworks.** Spring Boot, Micronaut, or plain Java SE integrations are not in scope for v1.
- **Non-Java languages.** Polyglot microservice environments require language-specific implementations; v1 covers Java 21 only.
- **Log retention policy enforcement.** Deciding how long logs are kept is a platform concern, not a library concern.

---

## 5. Rules and Principles

### 5.1 Coding Standards

- **Java 21 features.** Use records for immutable value objects, sealed interfaces for event type hierarchies, pattern matching for `instanceof` checks, and virtual threads (Project Loom) for non-blocking context propagation.
- **Quarkus 3.20 CDI.** Injection points use `@ApplicationScoped` and `@RequestScoped` beans. No Spring annotations.
- **Immutability by default.** All log event and audit record objects are immutable value types (Java records).
- **No silent failures.** Observability infrastructure failures (e.g., cannot reach the tracing backend) must be logged to the local fallback appender and must never propagate as business exceptions.
- **Minimal runtime overhead.** Context injection must be O(1); JSON serialization must use a pre-compiled schema. This is a firm requirement per the microservices.io patterns.

### 5.2 Naming Conventions

See [Field Name Registry](reference/FIELD_NAMES.md) for the full canonical field list. The key rule: **field names are reserved and must be used consistently across all services.** Using synonyms (e.g., `order_number` instead of `order_id`) is a violation of the consistency principle and causes split results in analytics tools.

### 5.3 Best Practices

**Pass the full exception object, not just the message.**

```java
// WRONG
logger.error(e.getMessage());

// CORRECT — allows formatter to extract class, backtrace, and cause chain
logger.error("Failed to process order", e);
```

**Use dynamic, specific messages with entity IDs.**

```java
// WRONG
log.error("Error saving");

// CORRECT
log.error("Error saving Order#{}: duplicate key", orderId);
```

**Log entity state at event time.** Include the relevant state of the entity at the moment of the event, not just its identifier. This allows post-incident reconstruction without querying the database.

**Prefer structured key-value pairs over string interpolation.**

```java
// WRONG — string interpolation
log.info("Order " + id + " saved by user " + userId);

// CORRECT — structured fields
logger.atInfo()
    .addKeyValue("order_id", id)
    .addKeyValue("user_id", userId)
    .log("Order saved");
```

**UTC timestamps always.** Local timezone timestamps in distributed systems produce misleading timelines.

**Configure log rotation.** Rotate by both size and time period to prevent storage exhaustion. Logstash and Quarkus must be configured explicitly — some default formatters omit timestamps or rotation settings.

**Never log sensitive data.** Passwords, tokens, full card numbers, and LGPD-protected fields must be masked or redacted before reaching any log statement. See the [Security section in STRUCTURED_LOGGING.md](concepts/STRUCTURED_LOGGING.md#security-and-governance).

---

## 6. Fundamental Concepts

### 6.1 Glossary

| Term | Definition |
| --- | --- |
| **Observability** | The ability to infer the internal state of a system from its external outputs (logs, traces, metrics). |
| **Structured Log** | A log entry represented as a machine-parseable data structure (JSON) with named fields, as opposed to a plain-text line. |
| **MDC** | Mapped Diagnostic Context — a thread-local map in SLF4J/Logback that stores key-value pairs automatically appended to every log event emitted on that thread. |
| **Request ID** | A UUID generated once per HTTP request, propagated through all log lines and downstream calls generated by that request. Enables full-request filtering in log aggregators. |
| **Trace ID** | An OpenTelemetry identifier that spans multiple services and correlates all spans in a single end-to-end user operation. Broader than Request ID in distributed systems. |
| **Span** | A single unit of work within a trace — e.g., a database query, an HTTP call to a downstream service, or a message publish. Spans form a tree structure under a Trace. |
| **Telemetry** | The automated collection and transmission of measurements from a remote or distributed system — in this context: metrics, logs, and traces collectively. |
| **Audit Log** | A persistent, append-only record of user actions on business entities, maintained for compliance, security investigation, and customer support. |
| **Event Sourcing** | An architectural pattern where state changes are recorded as an immutable sequence of events. Recommended by microservices.io as a reliable audit logging implementation strategy. |
| **5 Ws** | A logging framework from Taylor Scott (SolidusConf 2020): Who, What, When, Where, Why — the five questions every useful log event must answer. |
| **Health Check API** | An HTTP endpoint (typically `/q/health` in Quarkus) that returns the operational status of a service instance and its dependencies. |
| **Exception Tracking** | Reporting exceptions to a centralized service for de-duplication, aggregation, and developer notification — distinct from and complementary to log aggregation. |
| **Log Aggregation** | The collection of log files from multiple service instances into a centralized, searchable store (e.g., Elasticsearch + Kibana, Loki + Grafana). |
| **OpenTelemetry** | A CNCF standard providing a vendor-neutral API, SDK, and instrumentation for traces, metrics, and logs. |
| **Sampling** | Recording only a percentage of traces to reduce storage overhead. Head-based sampling decides at trace start; tail-based sampling decides after completion. |

### 6.2 Main Abstractions

These are conceptual definitions. Implementation details are deferred to the design phase.

| Abstraction | Description |
| --- | --- |
| `ObservabilityContext` | The central carrier object for a request scope. Holds `trace_id`, `span_id`, `request_id`, `user_id`, `hostname`, `pid`. Injected as a `@RequestScoped` CDI bean. |
| `ObservabilityEvent` | A sealed interface representing any observable event. Subtypes: `TechnicalEvent` and `BusinessEvent`. |
| `StructuredLogger` | The primary API for emitting events. Wraps SLF4J and automatically attaches the current `ObservabilityContext` to every event. Enforces the 5 Ws contract. |
| `AuditRecord` | An immutable record capturing actor, action, target entity, before/after states, and timestamp. |
| `AuditWriter` | An injectable interface that emits `AuditRecord` instances as structured log events (`event_type: AUDIT_*`). No built-in persistence implementations — persistence is the consumer's responsibility. |
| `TraceContext` | A value object carrying W3C TraceContext headers for cross-service propagation. |
| `HealthContributor` | A CDI interface that services implement to add custom health checks, composed into the `/q/health` response. |
| `ExceptionReporter` | An injectable service that receives exceptions, enriches them with `ObservabilityContext`, de-duplicates by fingerprint, and forwards to the configured tracking backend. |
| `FieldNameAdapter` | A CDI interface that remaps canonical field names to platform-specific conventions at output time. Built-in implementations: `default`, `ecs`, `datadog`, `graylog`. |

---

## 7. Simple Examples of Use

> These examples are **conceptual pseudo-code** illustrating the intended developer experience. They are not final API specifications.

### 7.1 Emitting a Business Event

The developer only provides the `What`. The library automatically enriches it with `Who`, `Where`, and `When`:

```java
structuredLogger.businessEvent("ORDER_COMPLETED",
    Map.of(
        "order_id", order.getId(),
        "order_value", order.getTotal(),
        "currency", "BRL"
    ));
```

Emitted JSON:

```json
{
  "@timestamp": "2026-03-09T14:32:01.123Z",
  "event_type": "ORDER_COMPLETED",
  "order_id": "ORD-9912",
  "order_value": 349.90,
  "currency": "BRL",
  "user_id": "USR-445",
  "request_id": "a3f9c2d1-...",
  "trace_id": "7d2c8e4f-...",
  "hostname": "app-node-03",
  "severity": "INFO"
}
```

### 7.2 Auditing a Sensitive User Action

```java
@Auditable(action = "UPDATE", entity = "UserProfile")
public void updateEmail(Long userId, String newEmail) {
    // pure business logic — no audit code here
}
```

The library intercepts the call, captures before/after state, and writes an immutable `AuditRecord` to the configured `AuditWriter` automatically.

### 7.3 Cross-Service Trace Correlation

A user action triggers three downstream service calls:

```text
User request → Order Service  (generates trace_id: "7d2c...")
                    ↓
              Payment Service  (propagates trace_id: "7d2c...", new span_id)
                    ↓
         Notification Service  (propagates trace_id: "7d2c...", new span_id)
```

An engineer queries the log aggregator for `trace_id = "7d2c..."` and sees all three services' log lines in chronological order — across service boundaries, with a single filter.

### 7.4 Health Check Response

```http
GET /q/health/ready
```

```json
{
  "status": "DOWN",
  "checks": [
    { "name": "database",        "status": "UP" },
    { "name": "payment-gateway", "status": "DOWN",
      "data": { "error": "Connection timeout after 5000ms" } }
  ]
}
```

The load balancer sees `DOWN` and stops routing traffic to this instance — without requiring a full restart.

---

## 8. Vision for the Future

### 8.1 Expected Evolution

- **GraalVM Native Image support.** All library components must be compatible with Quarkus's native compilation mode for serverless deployments.
- **Reactive / non-blocking context propagation.** Quarkus Mutiny and reactive REST clients use non-thread-local execution. Context propagation via SmallRye Context Propagation must be explicitly supported.
- **OpenTelemetry Logs signal.** As the OTel Logs data model stabilises, a future version should emit logs through the OTLP protocol, unifying all three signals in a single pipeline.
- **Adaptive sampling.** Tail-based sampling that preferentially retains traces containing errors or latency outliers, reducing storage cost while preserving diagnostic value.
- **ML anomaly baseline.** A companion module that builds statistical baselines for business event rates and alerts on deviations.

### 8.2 Possible Extensions and Integrations

- **Kafka / messaging context propagation.** Extend context propagation to message headers, enabling end-to-end tracing across event-driven architectures.
- **Security event specialisation.** A dedicated `SecurityEventLogger` for authentication failures, authorization denials, and anomalous access patterns — feeding a SIEM system.
- **Compliance report generation.** Scheduled jobs that produce audit log summaries in regulatory formats (LGPD data access reports, SOC 2 change log exports).
- **Multi-language bridge.** An HTTP-based sidecar exposing the context propagation API to non-Java services (Node.js, Python, Go).

### 8.3 Initial Roadmap

| Phase | Version | Deliverables |
| --- | --- | --- |
| **Foundation** | `v0.1` | `ObservabilityContext` CDI bean; `StructuredLogger` with JSON formatter and 5 Ws enforcement; JAX-RS filter for Request ID injection; basic Health Check API |
| **Tracing** | `v0.2` | OpenTelemetry SDK integration (traces + spans); W3C TraceContext propagation; Trace ID in all log events; Jaeger and Zipkin exporters |
| **Audit & Exceptions** | `v0.3` | `@Auditable` CDI interceptor; `AuditWriter` implementations (RDBMS, Kafka); `ExceptionReporter` with de-duplication and webhook notification; structured stack trace capture |
| **Metrics & Business Events** | `v0.4` | Micrometer / Prometheus integration; `BusinessEvent` type; push and pull metric export; latency histograms by route |
| **Production Ready** | `v1.0` | GraalVM Native Image compatibility; Mutiny context propagation; PII field masking; full documentation and example application |

---
## 9. Open Questions

See [Open Questions](reference/OPEN_QUESTIONS.md) for the full list. Items #1–#4 are resolved.

**Resolved:**
1. ✅ Identity array semantics — `user_id` only; no `visitor_token`
2. ✅ Sampling strategy — `always_on` no app; tail-based no OTel Collector
3. ✅ Audit record immutability — log stream apenas; sem persistência na extensão
4. ✅ Field name standard — flat snake_case + `FieldNameAdapter` plugável

**Open:**
5. 🟡 PII handling — opt-in ou opt-out masking?
6. 🟡 Audit vs. log separation — mesmo stream ou canal separado?
7. 🟢 Background job framework scope — quais frameworks em v1?
8. 🟡 Exception de-duplication strategy — algoritmo de fingerprint
---

## 10. References

- Taylor Scott — _"Logging in Production"_, SolidusConf 2020
- Chris Richardson — [Microservices.io: Application Logging](https://microservices.io/patterns/observability/application-logging.html)
- Chris Richardson — [Microservices.io: Distributed Tracing](https://microservices.io/patterns/observability/distributed-tracing.html)
- Chris Richardson — [Microservices.io: Exception Tracking](https://microservices.io/patterns/observability/exception-tracking.html)
- Chris Richardson — [Microservices.io: Audit Logging](https://microservices.io/patterns/observability/audit-logging.html)
- Iluwatar — [java-design-patterns: microservices-log-aggregation](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-log-aggregation)
- Iluwatar — [java-design-patterns: microservices-distributed-tracing](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-distributed-tracing)

- Chris Richardson — [Microservices.io: Application Metrics](https://microservices.io/patterns/observability/application-metrics.html)
- Chris Richardson — [Microservices.io: Health Check API](https://microservices.io/patterns/observability/health-check-api.html)

- [OpenTelemetry Specification](https://opentelemetry.io/docs/specs/)
- [W3C TraceContext Recommendation](https://www.w3.org/TR/trace-context/)
- [Quarkus 3.x — OpenTelemetry Guide](https://quarkus.io/guides/opentelemetry)
- [Elasticsearch Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/)
