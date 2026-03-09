# Coding Standards

> Standards for teams consuming OBSERVA4J and for contributors developing the library itself.

---

## Language and Platform

| Requirement | Specification |
| --- | --- |
| Java version | Java 21 (minimum) |
| Framework | Quarkus 3.20 |
| Dependency injection | CDI (`@ApplicationScoped`, `@RequestScoped`, `@Dependent`) — no Spring annotations |
| Build tool | Maven 3.9+ or Gradle 8+ |
| JSON library | Jackson (via Quarkus) |

### Java 21 Features in Use

The library actively uses Java 21 language capabilities:

- **Records** — for all immutable value objects (`AuditRecord`, `ObservabilityContext`, `TraceContext`)
- **Sealed interfaces** — for the `ObservabilityEvent` type hierarchy (`TechnicalEvent`, `BusinessEvent`)
- **Pattern matching for `instanceof`** — in event dispatchers and formatters
- **Virtual threads (Project Loom)** — for non-blocking context propagation in high-concurrency scenarios

---

## Prohibited Patterns

The following patterns are strictly prohibited. Code review must reject any pull request that introduces them.

### 1. `System.out.println` and `System.err.println`

```java
// FORBIDDEN
System.out.println("Order saved: " + orderId);
System.err.println("Error: " + e.getMessage());
```

Use `StructuredLogger` or SLF4J exclusively.

### 2. Manual String Assembly for Log Messages

```java
// FORBIDDEN — string concatenation
log.info("Order " + orderId + " saved by user " + userId);

// FORBIDDEN — String.format pseudo-JSON
log.info(String.format("{\"order_id\":\"%s\", \"user_id\":\"%s\"}", orderId, userId));

// CORRECT — structured key-value fields
logger.atInfo()
    .addKeyValue("order_id", orderId)
    .addKeyValue("user_id",  userId)
    .log("Order saved");
```

### 3. Logging Only the Exception Message

```java
// FORBIDDEN — discards class, stack trace, and cause chain
log.error(e.getMessage());
log.error("Error: " + e.getMessage());

// CORRECT — pass the full exception object
log.error("Failed to process order #{}", orderId, e);
```

### 4. Generic Log Messages Without Entity Identifiers

```java
// FORBIDDEN — provides no diagnostic value
log.error("Error saving");
log.warn("Validation failed");
log.info("Started");

// CORRECT — includes specific context
log.error("Error saving Order#{}: {}", orderId, e.getMessage(), e);
log.warn("Validation failed for Order#{}: field '{}' is required", orderId, fieldName);
log.info("Order processing started: Order#{} for User#{}", orderId, userId);
```

### 5. Log-and-Throw (Exception Duplication)

```java
// FORBIDDEN — causes the same exception to be logged multiple times
catch (OrderException e) {
    log.error("Order processing failed", e);
    throw e;  // will be logged again by the caller
}

// CORRECT — log at the boundary where you handle the exception
catch (OrderException e) {
    log.error("Order processing failed: Order#{}", orderId, e);
    return Response.serverError().entity(ErrorResponse.from(e)).build();
}
```

### 6. Logging Sensitive Data

```java
// FORBIDDEN — logs raw sensitive data
log.info("Payment initiated for card: {}", creditCardNumber);
log.debug("User authenticated: password={}", password);
log.info("User data: {}", userObject);  // if userObject contains PAN, CPF, etc.

// CORRECT — mask or use identifiers only
log.info("Payment initiated for card ending in: {}", last4Digits);
log.info("User #{} authenticated successfully", userId);
```

### 7. Manual UUID Generation as Trace ID

```java
// FORBIDDEN — creates a fake, non-distributed identifier
String traceId = UUID.randomUUID().toString();
MDC.put("traceId", traceId);

// CORRECT — extract the real trace ID from OpenTelemetry
String traceId = TraceContextExtractor.currentTraceId();
if (traceId != null) {
    MDC.put("trace_id", traceId);
}
```

### 8. MDC Without Cleanup

```java
// FORBIDDEN — MDC context leaks into subsequent requests on the same thread
MDC.put("user_id", userId);
processRequest();
// missing: MDC.clear()

// CORRECT — always clean up in a finally block
MDC.put("user_id", userId);
try {
    processRequest();
} finally {
    MDC.clear();
}
```

---

## Required Patterns

### Structured Key-Value Logging (SLF4J 2.x)

```java
logger.atInfo()
    .addKeyValue("action",    "processarPedido")
    .addKeyValue("order_id",  orderId)
    .addKeyValue("user_id",   userId)
    .log("Order processing started");
```

Or via MDC for context that applies to all log lines in a scope:

```java
MDC.put("order_id", orderId.toString());
log.info("Processing started");
// ... multiple log statements, all carry order_id automatically
MDC.remove("order_id");
```

### Exception Logging

```java
try {
    orderService.process(order);
} catch (Exception e) {
    log.error("Order processing failed: Order#{}", order.getId(), e);
    exceptionReporter.report(e, Map.of("order_id", order.getId()));
    throw new ServiceException("Order processing failed", e);
}
```

### Business Event Logging

```java
structuredLogger.businessEvent("ORDER_COMPLETED",
    Map.of(
        "order_id",    order.getId(),
        "order_value", order.getTotal(),
        "currency",    "BRL",
        "items_count", order.getItems().size()
    ));
```

---

## Immutability

All OBSERVA4J value objects are immutable Java records. Do not add mutable state to them:

```java
// Library defines this — do not modify
public record AuditRecord(
    String actorId,
    String action,
    String entityType,
    String entityId,
    Object stateBefore,
    Object stateAfter,
    Instant timestamp,
    String traceId
) {}
```

---

## No Silent Failures in Observability Code

Observability infrastructure failures must never propagate as business exceptions. If the exception tracking backend is unreachable, log the failure locally and continue:

```java
try {
    trackingBackend.report(exceptionRecord);
} catch (Exception backendException) {
    fallbackLogger.warn("Exception tracking backend unavailable: {}", backendException.getMessage());
    // do NOT re-throw — business logic must not fail because of observability failures
}
```

---

## Performance

- Context injection must be O(1) — no database lookups, no synchronous network calls
- JSON serialisation must use a pre-compiled schema (Jackson's `ObjectMapper` with registered modules, not reflection-based serialisation per event)
- MDC operations are O(1) thread-local map insertions — do not create complex objects in the MDC path
- Log level guards for expensive computations:

```java
// CORRECT — avoids expensive serialisation if DEBUG is disabled
if (log.isDebugEnabled()) {
    log.debug("Order state: {}", objectMapper.writeValueAsString(order));
}
```

---

## Code Review Checklist

Before approving any pull request touching observability code, verify:

- [ ] No `System.out.println` or `System.err.println`
- [ ] No manual string assembly in log messages
- [ ] No `log.error(e.getMessage())` — full exception object passed
- [ ] No generic messages — entity identifiers present
- [ ] No log-and-throw duplication
- [ ] No sensitive data (passwords, tokens, PAN, CPF) in log fields
- [ ] No manual UUID as `trace_id` — OpenTelemetry context used
- [ ] MDC cleared in `finally` block
- [ ] Canonical field names from [Field Name Registry](FIELD_NAMES.md) used
- [ ] Business events use `structuredLogger.businessEvent()` not raw `log.info()`

---

## See Also

- [Field Name Registry](FIELD_NAMES.md) — canonical field names
- [Structured Logging](STRUCTURED_LOGGING.md) — MDC and JSON format guidance
- [5 Ws Framework](FIVE_WS.md) — the model behind every log decision
