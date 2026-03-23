# Exception Tracking

> Source pattern: [Exception Tracking — microservices.io](https://microservices.io/patterns/observability/exception-tracking.html)

---

## Overview

Exceptions must be de-duplicated, recorded, investigated by developers, and their underlying causes resolved. Simply logging exceptions is insufficient — without centralised tracking, the same exception can occur thousands of times before anyone notices, and there is no mechanism for assigning ownership or tracking resolution.

> **Pattern definition (microservices.io):** Report all exceptions to a centralised exception tracking service that aggregates and tracks exceptions and notifies developers.

---

## Exception Tracking vs. Log Aggregation

These two patterns are **complementary**, not competing. An exception should be both:

1. **Logged** — in the structured log stream, with the full 5 Ws context, for correlation with the request timeline
2. **Reported** — to the exception tracking service, for de-duplication, assignment, and resolution tracking

| Concern | Log Aggregation | Exception Tracking |
| --- | --- | --- |
| Storage | Append-only time series | Grouped by fingerprint |
| De-duplication | None — every occurrence is a separate entry | Identical exceptions are grouped |
| Notification | Requires manual alert configuration | Automatic on new exception types |
| Resolution tracking | No | Yes — open/resolved/ignored states |
| Volume | All events | Only exceptions |

---

## The Silent Degradation Problem

Without exception tracking, the following scenario is common:

1. A code change introduces a bug that throws `NullPointerException` for 3% of requests
2. The exception is logged — but it is one line among thousands, with no alert
3. The error rate in Prometheus crosses a threshold — but no one has configured an alert for it
4. Users complain; the team begins investigating hours later
5. The investigation involves grepping logs across multiple service instances

With centralised exception tracking:

1. The `NullPointerException` is reported on first occurrence
2. The team receives a notification: _"New exception: NullPointerException in OrderService.processOrder() — 142 occurrences in the last 5 minutes"_
3. Investigation begins with a direct link to the grouped stack trace and associated request context

---

## The `ExceptionReporter`

OBSERVA4J provides an `ExceptionReporter` CDI bean that is wired into a global CDI exception handler. It automatically:

1. Receives all uncaught exceptions from the Quarkus exception handler
2. Enriches the exception with the current `ObservabilityContext` (Who, Where, Trace ID)
3. Computes a **fingerprint** to identify duplicate exceptions
4. Forwards to the configured tracking backend

No developer action is required for uncaught exceptions. Developers can also report caught exceptions explicitly:

```java
try {
    paymentGateway.charge(order);
} catch (PaymentGatewayException e) {
    exceptionReporter.report(e, Map.of("order_id", order.getId()));
    return PaymentResult.failed(e.getReason());
}
```

---

## Fingerprinting and De-duplication

A fingerprint is a stable identifier for a class of exceptions — so that the 1,000th occurrence of the same bug is recognised as the same bug, not as 1,000 separate issues.

The fingerprint is computed from:

- Exception class name
- Top N stack frames (from your own code, ignoring framework and library frames)
- (Optionally) the exception message, if it contains stable identifiers rather than dynamic data

> ⚠️ **Ambiguity — Exception De-duplication Strategy**
> Whether the fingerprint should include user identity (per-user exceptions grouped separately) or be global (all users who hit the same bug grouped together) is an [open question](OPEN_QUESTIONS.md#8-exception-deduplication-strategy). Both approaches have legitimate use cases.

---

## Enrichment with Observability Context

Every exception report is enriched with the current `ObservabilityContext`:

```json
{
  "exception_class": "java.lang.NullPointerException",
  "exception_message": "Cannot invoke method getId() on null",
  "stack_trace": [
    "br.com.orders.OrderService.processOrder(OrderService.java:87)",
    "br.com.orders.OrderController.createOrder(OrderController.java:43)",
    "..."
  ],
  "user_id": "USR-445",
  "request_id": "a3f9c2d1-...",
  "trace_id": "7d2c8e4f-...",
  "hostname": "app-node-03",
  "@timestamp": "2026-03-09T14:32:01.123Z",
  "occurrence_count": 142,
  "first_seen": "2026-03-09T14:30:45.000Z"
}
```

The `trace_id` field is particularly valuable: it links the exception report directly to the full distributed trace in Jaeger, so the engineer can see exactly what the user was doing when the exception occurred.

---

## Notification

The `ExceptionReporter` supports configurable notification channels for new exception types:

| Channel | Trigger |
| --- | --- |
| **Webhook** | HTTP POST to a configured URL (Slack, PagerDuty, Teams) |
| **Email** | Notification to a configured address |
| **Kafka topic** | Event published for downstream consumers |

Notifications fire on **new** exception types (by fingerprint), not on every occurrence. This prevents alert storms in degraded scenarios.

---

## Passing the Full Exception Object

A critical best practice that directly impacts exception tracking quality:

```java
// WRONG — discards class name, backtrace, and cause chain
logger.error(e.getMessage());
exceptionReporter.report(e.getMessage());

// CORRECT — preserves all diagnostic information
logger.error("Order processing failed", e);
exceptionReporter.report(e);
```

The exception object contains:

- `getClass().getName()` — for fingerprinting and grouping
- `getStackTrace()` — for locating the bug
- `getCause()` — for understanding root causes in wrapped exceptions
- `getMessage()` — for human-readable context

Discarding the object discards all of this.

---

## Supported Backends

OBSERVA4J's `ExceptionReporter` ships with adapters for:

| Backend | Type | Notes |
| --- | --- | --- |
| **Sentry** | SaaS / self-hosted | Industry standard; excellent grouping and context |
| **Rollbar** | SaaS | Strong deployment tracking integration |
| **Bugsnag** | SaaS | Good for multi-platform teams |
| **Custom webhook** | Any | Generic HTTP POST with JSON payload |

The backend is configured via `application.properties`:

```properties
observa4j.exceptions.reporter=sentry
observa4j.exceptions.sentry.dsn=https://...@sentry.io/...
```

---

## Limitations

Per microservices.io: _"The exception tracking service is additional infrastructure."_ Self-hosted exception tracking (e.g., self-hosted Sentry) adds operational overhead. Evaluate whether a SaaS provider is more appropriate for your team's capacity.

---

## See Also

- [Structured Logging](STRUCTURED_LOGGING.md) — exceptions are logged _and_ reported; these are complementary
- [5 Ws Framework](FIVE_WS.md) — the _What_ and _Where_ dimensions of an exception
- [Open Questions](OPEN_QUESTIONS.md#8-exception-deduplication-strategy) — fingerprinting strategy
- [microservices.io: Exception Tracking Pattern](https://microservices.io/patterns/observability/exception-tracking.html)
