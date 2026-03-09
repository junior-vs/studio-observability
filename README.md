# OBSERVA4J

> **Observability Library for Java 21 + Quarkus 3.x**
>
> Unified structured logging, distributed tracing, telemetry, and audit — in a single dependency.

[![Java 21](https://img.shields.io/badge/Java-21-blue?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Quarkus 3.20](https://img.shields.io/badge/Quarkus-3.20-red?logo=quarkus)](https://quarkus.io/)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-native-blueviolet)](https://opentelemetry.io/)
[![Status: Draft](https://img.shields.io/badge/Status-Draft-yellow)]()

---

## What is OBSERVA4J?

OBSERVA4J is a Java 21 module that consolidates the four pillars of modern observability into a single, cohesive library with a minimal and opinionated API surface:

| Pillar | What it solves |
|---|---|
| **Structured Logging** | Every log event carries the 5 Ws (Who, What, When, Where, Why) as queryable JSON |
| **Distributed Tracing** | Each request gets a Trace ID that propagates across all downstream service calls |
| **Telemetry & Metrics** | Application metrics (latency, counters, gauges) exported to Prometheus / Grafana |
| **Audit Logging** | Tamper-evident records of user actions for compliance and security investigation |

---

## The Core Problem

In microservice architectures, individual services produce fragmented, inconsistent, and unqueryable diagnostic data. The consequences are predictable:

- Engineers `grep` log files instead of querying a database
- A failed request leaves traces scattered across a dozen service logs with no shared identifier
- Observability logic is copy-pasted into every business service
- Production incidents take hours to diagnose because context is missing

OBSERVA4J solves this by enforcing the **5 Ws framework** at the library boundary: every event must answer Who triggered it, What happened, When it occurred, Where in the system, and Why (the causal context that the other four Ws make visible together).

> _"When you apply the 5 Ws, your logs stop being a wall of text and become a queryable database."_
> — Taylor Scott, SolidusConf 2020

---

## Documentation

### Vision & Architecture

| Document | Description |
|---|---|
| [Vision Document](VISION.md) | Purpose, objectives, scope, and roadmap |
| [Architecture Overview](ARCHITECTURE.md) | Core abstractions, modules, and data flow |

### Concepts

| Document | Description |
|---|---|
| [The 5 Ws Framework](FIVE_WS.md) | The logging model at the heart of the library |
| [Structured Logging](STRUCTURED_LOGGING.md) | JSON format, field standards, MDC usage |
| [Distributed Tracing](DISTRIBUTED_TRACING.md) | Trace ID, Span ID, W3C TraceContext propagation |
| [Audit Logging](AUDIT_LOGGING.md) | User action records, compliance, @Auditable |
| [Telemetry & Metrics](TELEMETRY.md) | Counters, histograms, gauges, Prometheus export |
| [Health Check API](HEALTH_CHECK.md) | Liveness, readiness, and custom health contributors |
| [Exception Tracking](EXCEPTION_TRACKING.md) | Centralized exception reporting and de-duplication |

### Reference

| Document | Description |
|---|---|
| [Field Name Registry](FIELD_NAMES.md) | Canonical field names for all log events |
| [Coding Standards](CODING_STANDARDS.md) | Naming conventions and best practices |
| [Open Questions](OPEN_QUESTIONS.md) | Ambiguities requiring resolution before detailed design |

---

## Quick Example

A developer logs a business event. The library automatically enriches it with the current observability context:

**Developer writes:**
```java
structuredLogger.businessEvent("ORDER_COMPLETED",
    Map.of("order_id", "ORD-9912", "order_value", 349.90, "currency", "BRL"));
```

**Library emits:**
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

No boilerplate. No repeated context fields. No manual string assembly.

---

## Status

This repository contains the **vision and design documentation** for OBSERVA4J. Implementation has not yet begun. See the [roadmap](VISION.md#roadmap) and [open questions](OPEN_QUESTIONS.md) before contributing.

---

## Sources

This library is grounded in the following reference material:

- Taylor Scott — *"Logging in Production"*, SolidusConf 2020
- Chris Richardson — [Microservices Observability Patterns](https://microservices.io/patterns/observability/)
- Iluwatar — [java-design-patterns: microservices-log-aggregation](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-log-aggregation)
- Iluwatar — [java-design-patterns: microservices-distributed-tracing](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-distributed-tracing)
- [OpenTelemetry Project](https://opentelemetry.io/)
- [Quarkus 3.x Documentation](https://quarkus.io/guides/)
