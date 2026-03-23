# The 5 Ws Framework

> _"When you apply the 5 Ws, your logs stop being a wall of text and become a queryable database."_
> — Taylor Scott, SolidusConf 2020

---

## Overview

The 5 Ws is the foundational logging model for OBSERVA4J. Borrowed from journalism, it asserts that every log event must be able to answer five questions:

| Dimension | Question | Example Fields |
| --- | --- | --- |
| **Who** | Who triggered this event? | `user_id`, `hostname` |
| **What** | What happened? | `event_type`, `message`, `order_id` |
| **When** | When did it happen? | `@timestamp` (UTC, millisecond precision) |
| **Where** | Where in the system? | `request_id`, `trace_id`, `queue_name`, `stack_trace` |
| **Why** | Why did this happen? | The narrative formed by combining the other four Ws |

Without all five dimensions, a log entry is a clue without a case. The _Why_ is the only emergent dimension — it cannot be stored directly, but it becomes visible when the other four are queryable together.

---

## 1. Who — Identity

The _Who_ answers: **who is involved in this event, and on which machine?**

Without it, you know that an error occurred but cannot tell whether it affected a single customer or your entire user base.

### User Identity

```json
{
  "user_id": "USR-445"
  
}
```

### Infrastructure Identity

In distributed and cloud environments, the _Who_ extends to the machine:

```json
{
  "hostname": "app-node-03",
  "pid": 14523
}
```

This makes it possible to isolate whether an error is random or tied to a specific instance (e.g., disk full on `app-node-03`).

---

## 2. What — Event Description

The _What_ answers: **what specifically happened?**

The most common failure here is being too generic.

### Static vs. Dynamic Messages

```java
// WRONG — tells you nothing actionable
log.error("Error processing payment");

// CORRECT — includes the entity ID and reason
log.error("Error processing payment: Order#{} — Card declined", orderId);
```

### Technical Events vs. Business Events

The `What` covers both:

| Type | Example `event_type` | Purpose |
| --- | --- | --- |
| Technical | `EXCEPTION`, `DB_QUERY_FAILED` | Debugging and incident response |
| Business | `ORDER_COMPLETED`, `CART_ABANDONED` | Analytics, KPIs, business intelligence |

Business events are first-class citizens in OBSERVA4J — not an afterthought. See [Section 6: Business Value](#6-business-value-beyond-debugging).

### Entity State

Where relevant, include the entity's state at the moment of the event:

```json
{
  "event_type": "ORDER_SAVE_FAILED",
  "order_id": "ORD-9912",
  "order_status": "pending",
  "order_value": 349.90
}
```

This allows post-incident reconstruction without querying the database.

---

## 3. When — Timestamp

The _When_ answers: **exactly when did this happen?**

### Requirements

- **Millisecond precision.** Second-level granularity is insufficient for diagnosing fast-moving failures.
- **UTC timezone.** Mixed timezones in distributed systems produce misleading timelines.
- **ISO-8601 format.** Standard format for interoperability with Elasticsearch, Loki, and other tools.

```json
{
  "@timestamp": "2026-03-09T14:32:01.123Z"
}
```

### Clock Synchronisation Warning

In microservice environments, if server clocks diverge even slightly, the _When_ dimension can confuse rather than clarify event ordering across services. Ensure NTP synchronisation across all nodes, especially in cloud environments with autoscaling.

> ⚠️ Some default Quarkus/Logback formatters omit the timestamp or include it without millisecond precision. Always verify formatter configuration explicitly.

---

## 4. Where — Location

The _Where_ answers: **where in the system — both in the code and in the infrastructure — did this event occur?**

This is the most multi-dimensional of the five Ws. It has four distinct sub-locations:

### 4.1 Location in Code (Stack Trace)

```java
// WRONG — discards all location information
logger.error(e.getMessage());

// CORRECT — pass the exception object; the formatter extracts class, backtrace, and cause chain
logger.error("Failed to process order", e);
```

The backtrace also reveals whether the failure originated in your own code (`app/`) or in a library dependency — which determines whether you fix your code or open an issue upstream.

### 4.2 Location in the Request Flow (Request ID)

In a server handling hundreds of concurrent requests, log lines from different users interleave. The Request ID isolates all log lines belonging to a single request:

```json
{ "request_id": "a3f9c2d1-7b44-4e2a-9c13-8d5f1b2e3a4c" }
```

This UUID is generated at the start of every HTTP request by the library's JAX-RS `ContainerRequestFilter` and is automatically included in every subsequent log line on that request's execution path.

### 4.3 Location in the Infrastructure (Hostname / PID)

```json
{
  "hostname": "app-node-03",
  "pid": 14523
}
```

Enables isolation of instance-specific failures (memory leaks, full disks) from logic bugs.

### 4.4 Location in Background Jobs

```json
{
  "queue_name": "critical",
  "job_id": "job_7f3a9c",
  "worker_class": "OrderProcessingWorker"
}
```

Background job logs are notoriously difficult to trace. These three fields make it possible to distinguish a logic error from queue congestion.

---

## 5. Why — Causality

The _Why_ is the only dimension that cannot be stored in a single field. It is the **narrative that emerges** when the other four Ws are queried together.

### Example

Individual log lines:

```text
[14:31:58.001] INFO  user_id=USR-445  event=CHECKOUT_STARTED     order_id=ORD-9912
[14:31:58.240] INFO  user_id=USR-445  event=PAYMENT_INITIATED    order_id=ORD-9912
[14:31:59.112] ERROR user_id=USR-445  event=PAYMENT_FAILED       order_id=ORD-9912  reason="card_declined"
[14:31:59.200] INFO  user_id=USR-445  event=CHECKOUT_ABANDONED   order_id=ORD-9912
```

None of these lines individually explains why the checkout was abandoned. Queried together, the sequence tells the full story: the payment was declined, which caused the abandonment.

### Business-Level Why

Structured logs also enable answering business questions:

> _"Why did sales drop 40% this afternoon?"_

Querying structured logs reveals that the shipping service returned `503` errors for all orders in the South region between 14:00 and 16:30 — even though no error alerts were triggered, because the failures affected one region's checkout flow, not the entire platform.

---

## 6. Business Value Beyond Debugging

Taylor Scott identifies four categories of value that emerge when logs are properly structured. These extend observability beyond incident response:

### 6.1 Business Analytics

Structured events enable dashboards showing conversion funnels, cart abandonment rates, and regional revenue — without a separate analytics pipeline:

```json
{
  "event_type": "CHECKOUT_STARTED",
  "user_id": "USR-445",
  "cart_value": 500.00,
  "region": "south"
}
```

A Kibana query over `event_type = CHECKOUT_STARTED` grouped by `region` gives the regional checkout funnel — no separate database table required.

### 6.2 Audit and Proof of Innocence

When a third-party API (payment gateway, logistics provider) blames your service for a failure, the logged request body and timestamp provide irrefutable technical evidence:

> _"Here is the log: we sent the correct JSON at 10:05:32.441Z and received a 500 from your API at 10:05:32.619Z."_

### 6.3 Proactive Anomaly Detection

Consistent field names and formats allow ML-based or threshold-based alerting:

- A spike in `event_type = LOGIN_FAILED` for a single `user_id` in a short `@timestamp` window → brute-force attack alert
- An 80% drop in `event_type = ORDER_CREATED` vs. the hourly baseline → UI bug blocking checkout, even with zero error logs

### 6.4 Performance Optimisation

Timestamp deltas at request start and end allow aggregate latency analysis by route:

```text
P99 latency for /api/orders/search = 3,200ms  ← identified bottleneck
P99 latency for all other routes   = 180ms
```

This prioritises refactoring based on real production data, not assumptions.

---

## Summary

| Dimension | Common Failure | Library Enforcement |
| --- | --- | --- |
| **Who** | No user or machine identity | Automatic context injection via `@RequestScoped ObservabilityContext` |
| **What** | Generic messages without entity IDs | Field schema validation; `BusinessEvent` type |
| **When** | Missing or low-precision timestamps | UTC millisecond `@timestamp` in every event |
| **Where** | Only exception message, no stack trace | Full exception object capture; Request ID filter; Job metadata |
| **Why** | Not applicable — it's emergent | Enabled by the presence of all other four Ws |

---

## See Also

- [Structured Logging](STRUCTURED_LOGGING.md) — JSON format, MDC, and field consistency
- [Field Name Registry](FIELD_NAMES.md) — canonical field names for all dimensions
- [Distributed Tracing](DISTRIBUTED_TRACING.md) — how `trace_id` extends the _Where_ across services
