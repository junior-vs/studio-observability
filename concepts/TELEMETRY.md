# Telemetry & Metrics

> Source pattern: [Application Metrics — microservices.io](https://microservices.io/patterns/observability/application-metrics.html)

---

## Overview

Metrics are numerical measurements collected over time. While logs capture discrete events and traces capture request flows, metrics capture the **aggregate behaviour** of a service — request rates, error rates, latency distributions, and resource utilisation.

> **Pattern definition (microservices.io):** Instrument a service to gather statistics about individual operations. Aggregate metrics in a centralised metrics service, which provides reporting and alerting.

Observability in a modern system requires all three signals: logs, traces, and metrics. OBSERVA4J integrates all three.

---

## The Three Pillars of Observability

| Signal | What it tells you | Tool |
| --- | --- | --- |
| **Logs** | What happened, with full context | Elasticsearch / Loki |
| **Traces** | How a request moved through the system | Jaeger / Zipkin |
| **Metrics** | How the system is performing in aggregate | Prometheus / Grafana |

---

## Metric Types

### Counter

A value that only increases. Used to count occurrences.

```text
http_requests_total{method="POST", route="/orders", status="200"} 14532
http_errors_total{method="POST", route="/orders", error="timeout"} 23
```

### Histogram

Records the distribution of values (e.g., request latencies). Enables percentile calculations.

```text
http_request_duration_seconds_bucket{route="/orders", le="0.1"}  12100
http_request_duration_seconds_bucket{route="/orders", le="1.0"}  14501
http_request_duration_seconds_bucket{route="/orders", le="+Inf"} 14532
```

Histograms answer: _"What percentage of requests complete in under 200ms?"_

### Gauge

A value that can increase or decrease. Used for current state measurements.

```text
jvm_memory_used_bytes{area="heap"}       245123456
db_connection_pool_active                12
queue_depth{queue="critical"}            4
```

---

## Aggregation Models

Per microservices.io, there are two models for aggregating metrics. With
the declared dependencies, **both models are active simultaneously** — this
is not a choice between one or the other.

### Pull Model (Prometheus)

The Prometheus registry (`quarkus-micrometer-registry-prometheus`) exposes
a scrape endpoint that Prometheus polls periodically:
```
Prometheus ──── scrapes ───▶ /q/metrics (Quarkus)
     │
     └──▶ Grafana (visualisation and alerting)
```

**Advantages:** Simple service configuration; no network egress cost from
the service; Prometheus controls the scrape interval.

### Push Model (OpenTelemetry Collector via OTLP)

The `quarkus-micrometer-opentelemetry` bridge exports all Micrometer
metrics via OTLP/gRPC to the OpenTelemetry Collector:
```
Service instance ──── OTLP/gRPC ───▶ OTel Collector ───▶ backends
```

**Advantages:** Unified pipeline with traces and logs; no Prometheus
infrastructure required for backends that support OTLP natively (Azure
Application Insights, Grafana Cloud, Elastic).

### Combined flow (this project)
```
Micrometer
    │
    ├──▶ Prometheus registry  ──▶ /q/metrics  ──▶ Prometheus → Grafana
    │
    └──▶ OTel bridge  ──▶ OTLP/gRPC  ──▶ OTel Collector → all OTLP backends
```

> When both channels are active, ensure that dashboards and alerts are
> not double-counting metrics by reading from both Prometheus and an OTLP
> backend simultaneously for the same service.
---
## Quarkus Integration

The project declares three Micrometer-related dependencies that work
together:
```xml
<!-- Micrometer core facade -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer</artifactId>
</dependency>

<!-- Prometheus scrape endpoint -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>

<!-- OpenTelemetry bridge — exports Micrometer metrics via OTLP -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-opentelemetry</artifactId>
</dependency>
```

### How they interact

`quarkus-micrometer-opentelemetry` is a bridge that causes all Micrometer
metrics to be exported via OTLP in addition to the Prometheus endpoint.
Both channels are active simultaneously:
```
Micrometer (facade)
        │
        ├──▶ Prometheus registry  ──▶ /q/metrics  ──▶ Prometheus scrape
        │
        └──▶ OTel bridge  ──▶ OTLP (gRPC)  ──▶ OTel Collector / backends
```

This means metrics, traces, and logs all flow through the same OTLP
pipeline when the Collector is active — a single unified telemetry channel
alongside the independent Prometheus scrape endpoint.

> The documentation does not treat Prometheus and OpenTelemetry as
> separate, mutually exclusive channels. Both are active by design.
---

## Automatic Metrics via Interceptor

OBSERVA4J's `@Logged` interceptor automatically records method execution time as a Micrometer timer. No developer instrumentation required for standard service methods:

```java
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LoggingInterceptor {

    @Inject
    MeterRegistry registry;

    @AroundInvoke
    public Object intercept(InvocationContext ctx) throws Exception {
        long start = System.nanoTime();
        try {
            return ctx.proceed();
        } finally {
            long duration = System.nanoTime() - start;
            registry.timer(
                "method.execution",
                "class",  ctx.getMethod().getDeclaringClass().getSimpleName(),
                "method", ctx.getMethod().getName()
            ).record(duration, TimeUnit.NANOSECONDS);
        }
    }
}
```

This produces:

```text
method_execution_seconds{class="OrderService", method="processOrder"} 0.023
```

---

## Business KPI Metrics

Structured log events can also emit corresponding metrics, creating a bridge between observability and business intelligence:

```java
// Emitting a business event also increments a Micrometer counter
structuredLogger.businessEvent("ORDER_COMPLETED",
    Map.of("order_value", 349.90, "currency", "BRL", "region", "south"));

// Under the hood, OBSERVA4J also records:
// orders_completed_total{currency="BRL", region="south"} += 1
// order_value_brl_total += 349.90
```

This enables Grafana dashboards for business KPIs — order volumes, revenue totals, regional breakdowns — directly from service metrics, without a separate analytics pipeline.

---

## Performance Optimisation with Metrics

Aggregate latency metrics expose which routes are responsible for the most user-facing slowness:

```text
P50 latency /api/orders/search  = 85ms
P99 latency /api/orders/search  = 3200ms  ← bottleneck
P99 latency /api/orders/create  = 180ms
```

This guides refactoring decisions based on real production data rather than assumptions. Per Taylor Scott (SolidusConf 2020): _"You can discover that 15% of your requests take more than 2 seconds on the search route — enabling you to prioritise refactoring based on actual usage data."_

---

## Infrastructure Health Metrics

OBSERVA4J also tracks infrastructure-level metrics relevant to the Health Check API:

| Metric | Type | Description |
| --- | --- | --- |
| `db.pool.active` | Gauge | Active database connections |
| `db.pool.max` | Gauge | Maximum pool size |
| `jvm.memory.used` | Gauge | JVM heap and non-heap usage |
| `jvm.gc.pause` | Histogram | GC pause duration |
| `http.client.requests` | Counter | Outbound HTTP calls to downstream services |
| `queue.depth` | Gauge | Current depth of each background job queue |

---

## JVM Metrics — Java Flight Recorder

The project declares `quarkus-jfr`, which integrates Java Flight Recorder
(JFR) with Quarkus. JFR exposes deep JVM events that complement the
Micrometer infrastructure metrics:

| JFR Event category | Examples |
|---|---|
| **GC** | Pause duration, GC cause, heap before/after |
| **Threads** | Thread count, blocked threads, lock contention |
| **Heap** | Allocation rate, TLAB statistics |
| **JIT** | Compilation time, deoptimisation events |
| **Network I/O** | Socket read/write durations |

JFR events are exposed at the Quarkus JFR endpoint and can be correlated
with Micrometer metrics and OTel traces by timestamp.

> JFR data is particularly useful for diagnosing latency outliers
> identified in trace histograms — the JFR heap and GC events often
> explain P99 spikes that have no obvious application-level cause.

No additional configuration is required beyond the declared dependency.

## Limitations

Per microservices.io: _"Aggregating metrics can require significant infrastructure."_ Prometheus + Grafana + Alertmanager is a non-trivial operational commitment. Teams should evaluate whether a managed metrics service (AWS CloudWatch, Datadog, Grafana Cloud) is more appropriate than self-hosted infrastructure.

Additionally, per microservices.io: metrics instrumentation code is inherently interwoven with business logic. OBSERVA4J minimises this coupling through the `@Logged` interceptor and automatic business event metric emission, but some explicit instrumentation will always be necessary for custom KPIs.

---

## See Also

- [Health Check API](HEALTH_CHECK.md) — infrastructure metrics feed the health endpoint
- [5 Ws Framework — Business Value](FIVE_WS.md#6-business-value-beyond-debugging) — business KPIs via structured events
- [Distributed Tracing](DISTRIBUTED_TRACING.md) — span duration data complements aggregate metrics
- [microservices.io: Application Metrics Pattern](https://microservices.io/patterns/observability/application-metrics.html)
- [Micrometer Documentation](https://micrometer.io/docs)
- [Prometheus Data Model](https://prometheus.io/docs/concepts/data_model/)
