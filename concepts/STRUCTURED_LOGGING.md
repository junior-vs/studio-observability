# Structured Logging

> Logging is not a debugging mechanism — it is a **structural component of software engineering**.

---

## Overview

Structured logging means every log event is a machine-parseable data structure (JSON) with named fields, rather than a free-form text line. This transforms logs from a passive record into an active, queryable data source.

OBSERVA4J enforces structured logging at the library boundary. It is not optional.

---
## Why JSON?

Plain-text logs require custom parsing rules for every field you want to
extract. They break when messages contain unexpected characters and diverge
silently across services.

JSON eliminates this class of problem entirely:

| Approach | Queryable? | Machine-parseable? | Cross-service consistent? |
|---|---|---|---|
| `System.out.println("Order saved: " + id)` | No | No | No |
| `log.info("Order saved: {}", id)` | No | No | No |
| `logger.atInfo().addKeyValue("order_id", id).log("Order saved")` | **Yes** | **Yes** | **Yes** |

JSON output is activated by `quarkus-logging-json`, which is declared as
a dependency in the project. This extension replaces the default Quarkus
text formatter with the JBoss LogManager `JsonFormatter`.

The correct activation property is:
```properties
quarkus.log.console.format=json
```

> ⚠️ `quarkus.log.console.json=true` is **not** the correct property when
> using `quarkus-logging-json`. That property belongs to a different
> formatter and has no effect here. Using it silently produces no JSON
> output.

### GELF output (Graylog / ELK)

The project also declares `quarkus-logging-gelf`, which sends log events
**directly** from the application to Graylog or Logstash via the GELF
protocol (UDP or TCP) — without a Fluentd or Logstash intermediary.

Both outputs are active simultaneously and independently:

| Output | Protocol | Destination | Format |
|---|---|---|---|
| Console | — | stdout | JSON (`quarkus-logging-json`) |
| GELF handler | UDP / TCP | Graylog or Logstash | GELF |

GELF configuration in `application.properties`:
```properties
quarkus.log.handler.gelf.enabled=true
quarkus.log.handler.gelf.host=localhost
quarkus.log.handler.gelf.port=12201
quarkus.log.handler.gelf.version=1.1
```

> ⚠️ **GELF does not support nested JSON objects.** The nested fields
> defined in [Field Names — Nested Object Fields](../reference/FIELD_NAMES.md#nested-object-fields)
> are automatically flattened by the `GraylogFieldNameAdapter` before
> transmission. Configure `observa4j.fields.standard=graylog` when GELF
> is the active log destination.
>
> Example: `exception.class` → `exception_class`

---

## Field Consistency

Consistency in field names across services is as important as the presence of the fields themselves. A service that logs `order_id` and another that logs `order_number` for the same concept will produce split results in analytics tools — two separate facets in Kibana, two separate metric labels in Prometheus.

The library maintains a [canonical field name registry](FIELD_NAMES.md). Using synonyms is a violation of the consistency principle.

---

## Mandatory Minimum Structure

Every log event must carry the following fields. The library injects the context fields automatically:

| Field | Source | Injected automatically? |
| --- | --- | --- |
| `@timestamp` | System clock (UTC) | ✅ |
| `severity` | Log level at call site | ✅ |
| `message` | Developer-provided | ❌ |
| `event_type` | Developer-provided | ❌ |
| `request_id` | JAX-RS filter | ✅ |
| `trace_id` | OpenTelemetry | ✅ |
| `user_id` | CDI interceptor (if authenticated) | ✅ |
| `hostname` | Environment | ✅ |

---

## MDC — Mapped Diagnostic Context

The Mapped Diagnostic Context (MDC) is a thread-local key-value store in
SLF4J that automatically appends its contents to every log event emitted
on that thread. It is the mechanism by which OBSERVA4J injects context
without requiring developers to repeat fields on every log statement.

### Minimum MDC Contents

```java
MDC.put("request_id", requestId);
MDC.put("service",    serviceName);
MDC.put("user_id",    userId);     // when authenticated
```

> **`trace_id` and `span_id` are not registered by OBSERVA4J.**
> These fields are injected into the MDC by Quarkus via
> `quarkus-opentelemetry` automatically. The library reads them as-is.
> Writing to `trace_id` or `span_id` from within OBSERVA4J is prohibited
> — doing so risks collision with the framework-managed values.

### MDC Lifecycle

The MDC must be cleared at the end of every request scope. Failure to do
so causes context from one request to leak into subsequent requests on the
same thread — a particularly insidious bug in thread-pooled servers.

```java
try {
    chain.doFilter(request, response);
} finally {
    MDC.clear();  // MANDATORY — prevents context leakage
}
```

The library's `ContainerRequestFilter` handles this automatically for HTTP
requests.

### Reactive Environments

**MDC alone is insufficient in reactive environments.** In applications
using:

- RESTEasy Reactive
- Mutiny
- Asynchronous execution

...the execution may switch threads mid-request, silently dropping the MDC
context. The required solution is:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-context-propagation</artifactId>
</dependency>
```

With SmallRye Context Propagation enabled, Quarkus propagates MDC and OpenTelemetry context across thread boundaries automatically

---

## Log Levels

### Guidance

| Level | When to use |
| --- | --- |
| `TRACE` | Very fine-grained internal state; never enabled in production |
| `DEBUG` | Internal flow decisions, intermediate data; disabled in production by default |
| `INFO` | State changes: persistence operations (create/update/delete), external calls, authentication, authorisation events |
| `WARN` | Unexpected but recoverable situations; degraded functionality |
| `ERROR` | The service failed to fulfil its contract; a real failure occurred |
| `FATAL` | The service must shut down |

### Anti-Patterns

**Log and throw.** Logging an exception at `ERROR` and then re-throwing it causes the same exception to be logged multiple times across layers. Log once, at the boundary where you handle it.

```java
// WRONG — will be logged again by the caller
try {
    processOrder(id);
} catch (OrderException e) {
    log.error("Order processing failed", e);
    throw e;  // ← now it gets logged again upstream
}

// CORRECT — log only where you handle (or at the top boundary)
try {
    processOrder(id);
} catch (OrderException e) {
    log.error("Order processing failed: Order#{}", id, e);
    return Response.serverError().build();
}
```

**Logging sensitive data.** See [Security and Governance](#security-and-governance) below.

---

## CDI Interceptors for Automatic Context

Use CDI interceptors to inject class/method context and manage MDC state transversally — without polluting business code:

```java
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LoggingInterceptor {

    @AroundInvoke
    public Object intercept(InvocationContext ctx) throws Exception {
        MDC.put("class",  ctx.getMethod().getDeclaringClass().getSimpleName());
        MDC.put("method", ctx.getMethod().getName());

        long start = System.nanoTime();
        try {
            return ctx.proceed();
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            // emit duration as structured field
            LoggingContextManager.clear();
        }
    }
}
```

---

## Log Rotation

Log rotation must be configured to prevent storage exhaustion from causing service failure. Configure both:

- **By size:** rotate when the log file exceeds a maximum size (e.g., 100 MB)
- **By time:** rotate daily or weekly

Some Quarkus default configurations do not enable rotation. Always verify explicitly in production deployments.

---

## Security and Governance

### Prohibited Fields

The following data must **never** appear in log output:

- Passwords and authentication tokens
- Full credit card numbers (PAN)
- CVV / security codes
- LGPD-protected personal data in raw form (CPF, full address, etc.)
- Session tokens and API keys

### Permitted Approaches

| Technique | Example |
| --- | --- |
| **Masking** | `"card_number": "****-****-****-4242"` |
| **Partial redaction** | `"email": "j***@example.com"` |
| **Hashing** | `"user_id_hash": "sha256:9f86d0..."` |

### Data Minimisation Principle

Log only what is necessary to diagnose the event. If a field's presence in the log cannot be justified by a specific observability use case, it should not be there.

> ⚠️ **Ambiguity — PII Handling**
> Whether PII masking should be opt-in (developer explicitly marks fields) or opt-out (library masks known-sensitive field names by default) is an [open question](OPEN_QUESTIONS.md#5-pii-handling). The list of field names treated as sensitive by default also requires definition.

---

## Dynamic Log Level Changes

Applications should support runtime log level adjustment without restart, via an administrative endpoint:

```properties
# Quarkus dynamic logging endpoint
quarkus.management.enabled=true
```

This is essential for temporarily enabling `DEBUG` in production during an incident investigation — without a deployment.

---

## Continuous Improvement

Logging is a living component of architecture. After every production incident:

1. Review the logs generated during the incident
2. Identify information gaps — what did you wish you had logged?
3. Update the logging guidelines or library accordingly
4. Incorporate the improvement as an organisational standard

---

## See Also

- [5 Ws Framework](FIVE_WS.md) — the model that defines what every log event must contain
- [Field Name Registry](FIELD_NAMES.md) — canonical field names
- [Coding Standards](CODING_STANDARDS.md) — prohibited patterns and best practices
- [Open Questions](OPEN_QUESTIONS.md) — unresolved design decisions
