# Fault Tolerance

> Extension: [SmallRye Fault Tolerance](https://quarkus.io/guides/smallrye-fault-tolerance) via `quarkus-smallrye-fault-tolerance`

---

## Overview

Fault tolerance patterns — Retry, Circuit Breaker, Timeout, and Fallback — are a first-class concern in microservice architectures. When combined with distributed tracing and structured logging, they require deliberate design: each retry attempt generates new spans, circuit state transitions must be logged as structured events, and fallback paths must carry the same observability context as the primary path.

OBSERVA4J is aware of the SmallRye Fault Tolerance lifecycle and provides structured log emission and span annotation for every relevant transition.

---

## Dependency

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-fault-tolerance</artifactId>
</dependency>
```

When `quarkus-opentelemetry` and `quarkus-smallrye-fault-tolerance` are both present, Quarkus automatically instruments fault tolerance operations and produces child spans for each attempt.

---

## How Each Pattern Affects Observability

### Retry

A `@Retry` annotation causes the interceptor to re-execute the method up to `maxRetries` times. Each attempt is a separate invocation — and with `quarkus-opentelemetry` active, **each attempt produces a separate child span** under the same parent span.

```
Trace: 7d2c8e4f
  └── [root] POST /orders                              200ms
        └── [child] PaymentService.charge()            RETRY_EXCEEDED  180ms
              ├── [attempt 1] charge() attempt         ERROR   30ms  ← timeout
              ├── [attempt 2] charge() attempt         ERROR   30ms  ← timeout
              └── [attempt 3] charge() attempt         ERROR   30ms  ← timeout
```

All three attempt spans share the same `trace_id`. The parent span is marked `ERROR` only after all attempts are exhausted.

#### What OBSERVA4J emits per attempt

```json
{
  "@timestamp": "2026-03-09T14:32:01.030Z",
  "event_type": "RETRY_ATTEMPT",
  "service_name": "order-service",
  "trace_id": "7d2c8e4f1a3b9c2d",
  "span_id": "b1c3d4e5",
  "attempt_number": 2,
  "max_retries": 3,
  "exception_class": "java.util.concurrent.TimeoutException",
  "exception_message": "Operation timed out after 5000ms",
  "method": "br.com.payments.PaymentService.charge",
  "severity": "WARN"
}
```

On final exhaustion:

```json
{
  "@timestamp": "2026-03-09T14:32:01.090Z",
  "event_type": "RETRY_EXHAUSTED",
  "service_name": "order-service",
  "trace_id": "7d2c8e4f1a3b9c2d",
  "span_id": "b1c3d4e5",
  "total_attempts": 3,
  "total_duration_ms": 180,
  "final_exception_class": "java.util.concurrent.TimeoutException",
  "severity": "ERROR"
}
```

#### Critical consideration for ExceptionReporter

An exception that is retried and **eventually succeeds** must not be forwarded to the `ExceptionReporter`. Only exceptions that propagate past the final `@Retry` boundary — meaning all attempts failed — should trigger exception reporting. OBSERVA4J intercepts the SmallRye `RetryContext` to determine whether the exception was ultimately swallowed by a successful retry.

> ⚠️ This is particularly important for transient failures (connection resets, DNS hiccups). Reporting every retry attempt as a separate exception creates noise and masks signal.

---

### Circuit Breaker

A Circuit Breaker monitors the success/failure ratio of a method. When the failure threshold is exceeded, it **opens** the circuit — rejecting calls immediately with a `CircuitBreakerOpenException` without attempting the downstream operation.

The circuit transitions through three states:

```
CLOSED ──[failure threshold exceeded]──▶ OPEN
  ▲                                         │
  │                                   [delay elapsed]
  │                                         ▼
  └──[success threshold met]────── HALF_OPEN
```

| State | Behaviour | OBSERVA4J event |
|---|---|---|
| `CLOSED` | Normal operation | No event emitted |
| `OPEN` | Calls rejected immediately | `CIRCUIT_BREAKER_OPENED` |
| `HALF_OPEN` | Single probe call allowed | `CIRCUIT_BREAKER_HALF_OPEN` |
| Re-`CLOSED` | Probe succeeded; circuit healed | `CIRCUIT_BREAKER_CLOSED` |

#### Structured log for state transitions

```json
{
  "@timestamp": "2026-03-09T14:32:05.000Z",
  "event_type": "CIRCUIT_BREAKER_OPENED",
  "service_name": "order-service",
  "trace_id": "7d2c8e4f1a3b9c2d",
  "circuit_name": "PaymentService.charge",
  "failure_count": 5,
  "request_volume_threshold": 5,
  "failure_rate_pct": 100,
  "delay_ms": 10000,
  "severity": "ERROR"
}
```

#### Impact on traces

When the circuit is `OPEN`, a call to the protected method produces a span with status `ERROR` and attribute `circuit_breaker.state=OPEN`. The span duration will be near-zero — the call was not attempted. This is expected and correct. Do not confuse zero-duration `OPEN` spans with instrumentation failures.

#### Metric emitted

OBSERVA4J registers a Micrometer counter per circuit state transition:

```
observa4j_circuit_breaker_state_transitions_total{circuit="PaymentService.charge", state="OPEN"} 1
observa4j_circuit_breaker_state_transitions_total{circuit="PaymentService.charge", state="CLOSED"} 1
```

This counter feeds directly into Grafana alert rules for sustained circuit-open states.

---

### Timeout

`@Timeout` defines the maximum duration allowed for a method execution. When exceeded, a `TimeoutException` is thrown and the span is marked `ERROR` with attribute `fault_tolerance.timeout=true`.

```json
{
  "@timestamp": "2026-03-09T14:32:01.060Z",
  "event_type": "TIMEOUT_EXCEEDED",
  "service_name": "order-service",
  "trace_id": "7d2c8e4f1a3b9c2d",
  "span_id": "c9d1e2f3",
  "method": "br.com.inventory.InventoryService.check",
  "timeout_ms": 2000,
  "actual_duration_ms": 2001,
  "severity": "WARN"
}
```

> A `@Timeout` combined with `@Retry` means the timeout applies **per attempt**. A `maxRetries=3` with `timeout=2s` can block a thread for up to 6 seconds before exhaustion. Log both dimensions (`timeout_ms` and `total_duration_ms`) to diagnose this correctly.

---

### Fallback

`@Fallback` provides an alternative result when all other mechanisms (Retry, Circuit Breaker, Timeout) fail. The fallback execution runs in the **same trace context** as the original call — it shares the `trace_id` and `span_id`.

OBSERVA4J emits a structured event when the fallback path is activated:

```json
{
  "@timestamp": "2026-03-09T14:32:01.095Z",
  "event_type": "FALLBACK_ACTIVATED",
  "service_name": "order-service",
  "trace_id": "7d2c8e4f1a3b9c2d",
  "span_id": "c9d1e2f3",
  "method": "br.com.payments.PaymentService.charge",
  "fallback_method": "br.com.payments.PaymentService.chargeFallback",
  "triggering_exception": "io.smallrye.faulttolerance.api.CircuitBreakerOpenException",
  "severity": "WARN"
}
```

The fallback span is a child of the original span and is annotated with attribute `fault_tolerance.fallback=true`. This makes it queryable in Jaeger: _"show me all traces that used the fallback path in the last hour."_

---

## Interaction Summary

| Pattern | Spans produced | OBSERVA4J events | ExceptionReporter triggered? |
|---|---|---|---|
| `@Retry` (succeeds on attempt N) | N attempt spans + 1 parent | `RETRY_ATTEMPT` × N-1 | **No** |
| `@Retry` (all attempts fail) | N attempt spans + 1 parent | `RETRY_ATTEMPT` × N + `RETRY_EXHAUSTED` | **Yes** (once, on exhaustion) |
| `@CircuitBreaker` (OPEN, call rejected) | 1 span (near-zero duration) | `CIRCUIT_BREAKER_OPENED` on transition | **No** (no downstream call was made) |
| `@Timeout` (exceeded) | 1 span | `TIMEOUT_EXCEEDED` | **Yes** |
| `@Fallback` (activated) | 1 additional child span | `FALLBACK_ACTIVATED` | **No** |

---

## Developer Contract

OBSERVA4J intercepts fault tolerance events through the SmallRye `FaultToleranceOperationListener` SPI. No annotation or configuration change is required from the developer beyond the standard SmallRye annotations.

The developer remains responsible for:

1. **Setting span status explicitly** in `catch` blocks that handle exceptions **before** the fault tolerance boundary:

    ```java
    try {
        return inventoryService.check(sku);
    } catch (InventoryException e) {
        Span.current().setStatus(StatusCode.ERROR, e.getMessage());
        throw e; // let @Retry handle it
    }
    ```

2. **Not suppressing exceptions silently in fallback methods** — a fallback that swallows an error and returns a default value should still emit a `FALLBACK_ACTIVATED` log event. OBSERVA4J emits this automatically, but if the fallback method itself throws, the developer must log it.

3. **Not nesting fault-tolerant calls inside fallback methods** — this creates non-linear span trees that are difficult to reason about in trace visualisation tools.

---

## Configuration Reference

```properties
# Retry
mp.fault-tolerance.PaymentService/charge/Retry/maxRetries=3
mp.fault-tolerance.PaymentService/charge/Retry/delay=500
mp.fault-tolerance.PaymentService/charge/Retry/delayUnit=MILLIS
mp.fault-tolerance.PaymentService/charge/Retry/retryOn=java.io.IOException

# Circuit Breaker
mp.fault-tolerance.PaymentService/charge/CircuitBreaker/requestVolumeThreshold=5
mp.fault-tolerance.PaymentService/charge/CircuitBreaker/failureRatio=0.5
mp.fault-tolerance.PaymentService/charge/CircuitBreaker/delay=10000
mp.fault-tolerance.PaymentService/charge/CircuitBreaker/successThreshold=2

# Timeout
mp.fault-tolerance.InventoryService/check/Timeout/value=2000
mp.fault-tolerance.InventoryService/check/Timeout/unit=MILLIS

# OBSERVA4J fault tolerance logging
observa4j.fault-tolerance.log-attempts=true
observa4j.fault-tolerance.log-state-transitions=true
observa4j.fault-tolerance.metrics.enabled=true
```

---

## Limitations

- SmallRye Fault Tolerance does not propagate `ThreadLocal` state across retry threads in reactive (Mutiny) execution contexts. `ObservabilityContext`, which is `@RequestScoped`, is not automatically available inside reactive retry branches. OBSERVA4J provides a `ReactiveObservabilityContextPropagator` for Mutiny pipelines — see [Open Questions #7](../reference/OPEN_QUESTIONS.md#7-background-job-framework-scope) for the current status of reactive support.
- Circuit state is in-process only — each JVM instance maintains its own circuit. In a multi-instance deployment, a circuit may be `OPEN` on node A and `CLOSED` on node B simultaneously. Centralised circuit state (e.g., via Redis) is not provided by SmallRye and is outside OBSERVA4J's scope.
- JFR events from `quarkus-jfr` include lock contention and thread blocking data that can correlate with sustained circuit-open periods — consult JFR dashboards when diagnosing why a circuit opened under apparently normal load.

---

## See Also

- [Distributed Tracing](DISTRIBUTED_TRACING.md) — span hierarchy and context propagation
- [Exception Tracking](EXCEPTION_TRACKING.md) — when `RETRY_EXHAUSTED` triggers exception reporting
- [Telemetry & Metrics](TELEMETRY.md) — circuit breaker transition counters in Micrometer
- [Open Questions #7](../reference/OPEN_QUESTIONS.md#7-background-job-framework-scope) — reactive context propagation
- [SmallRye Fault Tolerance Guide](https://quarkus.io/guides/smallrye-fault-tolerance)
- [MicroProfile Fault Tolerance Specification](https://microprofile.io/project/eclipse/microprofile-fault-tolerance)
