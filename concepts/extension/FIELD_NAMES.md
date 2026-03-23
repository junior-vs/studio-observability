# Field Name Registry

> **These field names are canonical and reserved.** Using synonyms (e.g., `order_number` instead of `order_id`) is a violation of the consistency principle and causes split results in analytics tools, Kibana dashboards, and Prometheus labels.

---

## Identity Fields — Who

| Field | Type | Description | Injected automatically? |
| --- | --- | --- | --- |
| `user_id` | `string` | Authenticated user's persistent identifier | ✅ (from CDI security context) |
| `session_id` | `string` | Session identifier (used in audit records) | ✅ |
| `actor_ip` | `string` | Source IP address of the current request | ✅ (from JAX-RS filter) |

---

## Event Fields — What

| Field | Type | Description | Example |
| --- | --- | --- | --- |
| `event_type` | `string` | Machine-readable event name | `ORDER_COMPLETED`, `LOGIN_FAILED` |
| `message` | `string` | Human-readable event description | `"Order saved successfully"` |
| `severity` | `string` | Log level | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL` |
| `exception_class` | `string` | Fully qualified exception class name | `java.lang.NullPointerException` |
| `exception_message` | `string` | Exception message string | `"Cannot invoke method getId() on null"` |
| `outcome` | `string` | Result of the operation (audit records) | `SUCCESS`, `FAILURE` |

---

## Timestamp Fields — When

| Field | Type | Description | Format |
| --- | --- | --- | --- |
| `@timestamp` | `string` | UTC timestamp of the event | ISO-8601 with milliseconds: `2026-03-09T14:32:01.123Z` |
| `duration_ms` | `number` | Duration of the operation in milliseconds | `23` |

> `@timestamp` uses the `@` prefix for compatibility with Elasticsearch and Logstash conventions.

---

### Request and Trace

| Field | Type | Description | Injected automatically? |
| --- | --- | --- | --- |
| `request_id` | `string` | UUID for one HTTP request lifecycle on one service | ✅ (JAX-RS filter — OBSERVA4J) |
| `trace_id` | `string` | OpenTelemetry trace identifier (cross-service) | ✅ (Quarkus via `quarkus-opentelemetry` — **not OBSERVA4J**) |
| `span_id` | `string` | OpenTelemetry span identifier (per operation) | ✅ (Quarkus via `quarkus-opentelemetry` — **not OBSERVA4J**) |
| `parent_span_id` | `string` | Parent span identifier | ✅ (Quarkus via `quarkus-opentelemetry` — **not OBSERVA4J**) |

> `trace_id`, `span_id`, and `parent_span_id` are owned by Quarkus /
> OpenTelemetry. OBSERVA4J reads these values from MDC but never writes
> to them. Overwriting these fields from within the library is prohibited.

### Infrastructure

| Field | Type | Description | Injected automatically? |
| --- | --- | --- | --- |
| `hostname` | `string` | Hostname or container name of the emitting instance | ✅ (environment) |
| `pid` | `number` | OS process identifier | ✅ (JVM runtime) |
| `service_name` | `string` | Name of the service emitting this event | ✅ (config: `quarkus.application.name`) |
| `service_version` | `string` | Version of the service | ✅ (config: `quarkus.application.version`) |

### Code Location

| Field | Type | Description |
| --- | --- | --- |
| `stack_trace` | `array<string>` | Full stack trace as an array of frame strings |
| `exception_cause` | `object` | Nested cause exception (same structure) |

### Background Jobs

| Field | Type | Description |
| --- | --- | --- |
| `queue_name` | `string` | Background job queue name |
| `job_id` | `string` | Unique identifier of the background job |
| `worker_class` | `string` | Fully qualified class name of the worker |

---

## Business Entity Fields

These fields are not injected automatically — developers provide them. The naming convention must be consistent across all services.

| Field | Type | Description |
| --- | --- | --- |
| `order_id` | `string` | Order identifier |
| `payment_id` | `string` | Payment transaction identifier |
| `product_id` | `string` | Product identifier |
| `cart_id` | `string` | Shopping cart identifier |
| `customer_id` | `string` | Customer identifier (may differ from `user_id`) |

> **Convention:** Entity identifiers use the pattern `{entity_type}_id` in snake_case. Never use `{entity_type}_number`, `{entity_type}_code`, or `{entity_type}Id` (camelCase). Consistency across services is mandatory.

---

## Audit-Specific Fields

These fields appear only in audit records, not in standard log events:

| Field | Type | Description |
| --- | --- | --- |
| `action` | `string` | Audit action type: `CREATE`, `UPDATE`, `DELETE`, `READ`, `LOGIN`, `LOGOUT` |
| `entity_type` | `string` | Type of the affected entity |
| `entity_id` | `string` | Identifier of the affected entity |
| `state_before` | `object` | Entity state snapshot before the action |
| `state_after` | `object` | Entity state snapshot after the action |

---

## Field Name Standard Alignment

The canonical standard is **flat snake_case notation**: `trace_id`,
`span_id`, `user_id`, `request_id`.

ECS dot-notation (`trace.id`) and camelCase (`traceId`) are prohibited
in native output.

### Platform Adapters

A `FieldNameAdapter` class remaps canonical names to platform-specific
conventions at output time. The active adapter is selected via
`application.properties`:

```properties
# Default — no remapping (flat snake_case)
observa4j.fields.standard=default

# Elasticsearch Common Schema
observa4j.fields.standard=ecs

# Datadog
observa4j.fields.standard=datadog
```

Built-in adapters ship for `default`, `ecs`, and `datadog`. A custom
adapter may be provided via CDI.

| Canonical (default) | ECS | Datadog |
| --- | --- | --- |
| `trace_id` | `trace.id` | `dd.trace_id` |
| `span_id` | `span.id` | `dd.span_id` |
| `user_id` | `user.id` | `usr.id` |
| `exception.class` | `error.type` | `error.type` |
| `exception.message` | `error.message` | `error.message` |

> **Known limitation:** The adapter remaps field **names** only — it does
> not transform values. Datadog's `dd.trace_id` expects a 64-bit decimal
> string while OpenTelemetry produces a 128-bit hex string. Value
> transformation is out of scope for v1 and must be handled at the
> infrastructure layer if required.
---

## Nested Object Fields

Composite fields use nested JSON objects, not flat dot-notation keys.

### Exception

```json
{
  "exception": {
    "class":       "java.lang.NullPointerException",
    "message":     "Cannot invoke getId() on null",
    "stack_trace": [
      "br.com.orders.OrderService.processOrder(OrderService.java:87)",
      "br.com.orders.OrderController.createOrder(OrderController.java:43)"
    ],
    "cause": {
      "class":   "java.sql.SQLException",
      "message": "Connection refused"
    }
  }
}
```

### Audit State Snapshots

```json
{
  "state_before": { "email": "old@example.com", "status": "active" },
  "state_after":  { "email": "new@example.com", "status": "active" }
}
```

> **Platform note:** Elasticsearch index templates must map these fields
> as `object` type, not `flattened`. Logback appenders (e.g.,
> `logstash-logback-encoder`) must be configured to serialise nested
> objects correctly. This is a platform-side configuration concern, not
> a library concern.

---

## Prohibited Synonyms

The following names are explicitly prohibited. If your service uses any
of these, migrate to the canonical name.

| Prohibited | Canonical | Reason |
| --- | --- | --- |
| `order_number` | `order_id` | Inconsistent naming causes split facets |
| `user_identifier` | `user_id` | Verbose; inconsistent |
| `traceId` | `trace_id` | camelCase violates snake_case convention |
| `requestId` | `request_id` | camelCase violates snake_case convention |
| `correlationId` | `request_id` or `trace_id` | Ambiguous; use the specific identifier |
| `timestamp` | `@timestamp` | Missing `@` prefix breaks Elasticsearch/ECS compatibility |
| `log_level` | `severity` | Inconsistent with OpenTelemetry and ECS conventions |
| `error_message` | `exception.message` | Use the nested exception object |
| `error_class` | `exception.class` | Use the nested exception object |
| `trace.id` | `trace_id` | ECS notation — use adapter instead of hardcoding |
| `span.id` | `span_id` | ECS notation — use adapter instead of hardcoding |
| `dd.trace_id` | `trace_id` | Datadog notation — use adapter instead of hardcoding |

---

## See Also

- [5 Ws Framework](FIVE_WS.md) — how fields map to the five dimensions
- [Structured Logging](STRUCTURED_LOGGING.md) — field injection via MDC
- [Coding Standards](CODING_STANDARDS.md) — rules for field usage in code
- [Open Questions](OPEN_QUESTIONS.md) — PII handling (#5), exception de-duplication (#8)