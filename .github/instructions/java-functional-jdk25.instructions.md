---
description: 'JDK 25 LTS addendum to java-functional.instructions.md — covers only stable functional features introduced between JDK 22 and JDK 25. Apply together with java-functional.instructions.md.'
applyTo: '**/*.java'
---

# Java Functional Programming — JDK 25 LTS Addendum

> **How to use this file**
> This file complements `java-functional.instructions.md`, which covers the JDK 21 LTS baseline.
> All JDK 21 principles — immutability, pure functions, `Result<T, E>`, sealed ADTs, `Optional` chains — remain in force and are not repeated here.
> Apply the rules in this file only when the project build explicitly targets JDK 25 (`<java.version>25</java.version>` or `sourceCompatibility = JavaVersion.VERSION_25`).
>
> **Preview in JDK 25 — do not use in production:** Structured Concurrency (JEP 505), Primitive Types in Patterns (JEP 507), Stable Values (JEP 502), PEM Encodings (JEP 470).

---

## JDK 25 Stable Additions to the Functional Baseline

| Feature | JEP | Impact on Functional Code |
| :--- | :--- | :--- |
| Unnamed Variables & Patterns `_` | JEP 456 (stable JDK 22) | Discard unused bindings in patterns and catch blocks |
| `Stream.gather()` — Stream Gatherers | JEP 485 (stable JDK 24) | Custom stateful intermediate stream operations |
| Scoped Values | JEP 506 | Immutable, bounded context — replaces `ThreadLocal` in functional pipelines |
| Flexible Constructor Bodies | JEP 513 | Validation before `super()` without static helpers |
| Module Import Declarations | JEP 511 | `import module java.base;` reduces boilerplate |

---

## Unnamed Variables and Patterns (`_`)

Use `_` to explicitly discard unused bindings in pattern deconstruction, catch blocks, and lambda parameters. This signals intent and eliminates "variable declared but never used" warnings.

- Use `_` only when the variable is **genuinely** unused — not as a shortcut for lazy naming.
- For JDK 21 targets, replace `_` with a named variable (`ignored`, `unused`, `ex`).

```java
// Good: Discard unused catch parameter
try {
    return Integer.parseInt(input);
} catch (NumberFormatException _) {
    return 0;
}

// Good: Discard unused lambda parameter
list.forEach(_ -> counter.increment());

// Good: Partial record deconstruction — only 'reason' is needed from Failure
String message(PaymentResult result) {
    return switch (result) {
        case PaymentResult.Success(var id, var amt) -> "Paid %s: %s".formatted(id, amt);
        case PaymentResult.Failure(var reason, _)   -> "Failed: " + reason;
    };
}

// Good: Discard unused Ok value — only the error matters here
case Result.Err(var error)  -> handleError(error);
case Result.Ok(_)           -> "Success";
```

### `_` in `Result<T, E>` and Sealed ADTs

The unnamed variable integrates naturally with `Result` and sealed hierarchies to eliminate noise when only one field of a record variant is needed:

```java
// JDK 21: named binding even when unused
case OrderError.OutOfStock(var items) -> "No stock";       // 'items' not used

// JDK 25: _ explicitly signals the field is intentionally ignored
case OrderError.OutOfStock(_) -> "No stock";
```

---

## Stream Gatherers — `Stream.gather()` (JEP 485)

`Stream.gather(Gatherer)` is a new intermediate operation that enables custom, stateful transformations within a declarative pipeline. It fills the gap between the standard `map`/`filter`/`flatMap` and breaking out into an imperative loop.

**When to use `gather()` over standard operations:**
- Windowing (sliding or tumbling windows over elements)
- Stateful scanning (running totals, state machines)
- Custom folding without breaking the pipeline into multiple passes

```java
// Good: Sliding window of size 3 — keeps pipeline declarative
List<List<Integer>> windows = Stream.of(1, 2, 3, 4, 5)
    .gather(Gatherers.windowSliding(3))
    .toList();
// Result: [[1,2,3], [2,3,4], [3,4,5]]

// Good: Fixed-size tumbling (non-overlapping) windows
List<List<Integer>> chunks = Stream.of(1, 2, 3, 4, 5, 6)
    .gather(Gatherers.windowFixed(2))
    .toList();
// Result: [[1,2], [3,4], [5,6]]

// Good: Running total as a scan — stateful, but stays in the pipeline
List<Integer> runningTotals = Stream.of(1, 2, 3, 4)
    .gather(Gatherers.scan(() -> 0, Integer::sum))
    .toList();
// Result: [1, 3, 6, 10]
```

### Custom Gatherers

Implement `Gatherer<T, A, R>` for domain-specific stateful operations that do not fit standard operators. A Gatherer has four optional components: `initializer`, `integrator`, `combiner`, and `finisher`.

```java
// Good: Custom Gatherer — emit only elements where value increased (monotonic filter)
Gatherer<Integer, int[], Integer> onlyIncreasing = Gatherer.of(
    () -> new int[]{Integer.MIN_VALUE},  // initializer: holds previous value
    (state, element, downstream) -> {   // integrator
        if (element > state[0]) {
            state[0] = element;
            return downstream.push(element);
        }
        return true; // skip non-increasing values
    }
);

List<Integer> increasing = Stream.of(1, 3, 2, 5, 4, 6)
    .gather(onlyIncreasing)
    .toList();
// Result: [1, 3, 5, 6]
```

**Rules for Gatherers:**
- Keep the `integrator` pure — avoid external state mutations inside it.
- Document whether the Gatherer is **stateless**, **stateful sequential**, or **stateful parallel-safe**.
- Prefer `Gatherers.*` built-in factory methods (`windowSliding`, `windowFixed`, `scan`, `fold`) before writing custom implementations.
- Avoid `gather()` for simple transformations that `map`, `filter`, or `flatMap` already cover cleanly.

```java
// Avoid: gather() for what filter() already does
stream.gather(Gatherer.of(
    (state, e, down) -> { if (e > 0) down.push(e); return true; }
));

// Good: use filter() directly
stream.filter(e -> e > 0)
```

---

## Scoped Values (JEP 506)

`ScopedValue` provides immutable, bounded context scoping within a thread and its callees. In functional code, it eliminates the need to thread contextual parameters (request ID, user identity, locale) through every method signature.

### When to use `ScopedValue` over `ThreadLocal`

| Concern | `ThreadLocal` | `ScopedValue` |
| :--- | :--- | :--- |
| Mutability | Mutable — `.set()` / `.remove()` | Immutable — bound once per `where()` scope |
| Lifetime | Thread lifetime (risk of leaks) | Bounded to the `where().run()` / `where().call()` scope |
| Functional purity | Breaks referential transparency | Preserves purity — the scope is explicit and bounded |
| Virtual thread safety | Risky (inherits across children) | Safe — scope is clearly delimited |

```java
// Good: Scoped value for immutable request context
public class RequestContext {
    public static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

    public void handle(User user, Runnable task) {
        ScopedValue.where(CURRENT_USER, user).run(task);
    }
}

// Any method within the scope can access the value without parameter threading
class AuditService {
    void audit(String action) {
        var user = RequestContext.CURRENT_USER.get();
        log.info("Action '{}' by {}", action, user.id());
    }
}

// Good: Multiple values bound in the same scope
ScopedValue.where(CURRENT_USER, user)
           .where(REQUEST_ID, requestId)
           .run(() -> processRequest());

// Good: orElse for when the value may not be bound
var user = RequestContext.CURRENT_USER.orElse(User.anonymous());
```

**Rules for Scoped Values:**
- Do **not** use `ScopedValue` as mutable shared state — it is immutable by design.
- Bind only at the outermost entry point (controller, message consumer). Inner domain logic must only read, never rebind.
- `ThreadLocal` remains valid for mutable, thread-local accumulation (formatters, parsers) — do not replace it wholesale.

---

## Flexible Constructor Bodies (JEP 513)

Statements that do not reference `this` or instance fields may now appear **before** `super()` or `this()`. In functional domain modeling, this eliminates static helper methods used solely to validate or transform constructor arguments before delegation.

```java
// JDK 21: Required a static helper to validate before super()
public class NonEmptyList<T> extends AbstractList<T> {
    public NonEmptyList(List<T> items) {
        super(validate(items)); // static helper was the only option
    }
    private static <T> List<T> validate(List<T> items) {
        if (items.isEmpty()) throw new IllegalArgumentException("List must not be empty");
        return items;
    }
}

// JDK 25: Validation directly before super() — no static helper needed
public class NonEmptyList<T> extends AbstractList<T> {
    public NonEmptyList(List<T> items) {
        if (items.isEmpty()) throw new IllegalArgumentException("List must not be empty");
        super(items);
    }
}
```

**Rules:**
- Statements before `super()`/`this()` **cannot** reference `this` or instance fields.
- Use this feature for validation and argument transformation only — not for complex setup logic.
- Records with compact constructors are still the preferred idiom for value objects; this feature targets class-based hierarchies where extension is necessary.

---

## Naming Convention — `_` Addendum

The unnamed variable `_` is now a stable language keyword. Update the naming convention table from `java-functional.instructions.md`:

| Identifier | JDK 21 | JDK 25 |
| :--- | :--- | :--- |
| Unused catch parameter | Named: `IOException ignored` | Use `_`: `IOException _` |
| Unused lambda parameter | Named: `__` or `ignored` | Use `_`: `_ ->` |
| Unused pattern binding | Named: `var unused` | Use `_` in deconstruction |

---

## Result<T, E> — Cleaner Exhaustive Handling

With `_` available, exhaustive `Result` switches become less noisy when a field is not needed:

```java
// JDK 21
String respond(Result<Order, OrderError> result) {
    return switch (result) {
        case Result.Ok(var order)                        -> "Order: " + order.id();
        case Result.Err(OrderError.OutOfStock(var items))-> "No stock: " + items;
        case Result.Err(OrderError.InvalidCart(var msg)) -> "Invalid: " + msg;
    };
}

// JDK 25: _ discards the unused order value in the Ok arm
String respond(Result<Order, OrderError> result) {
    return switch (result) {
        case Result.Ok(var order)                        -> "Order: " + order.id();
        case Result.Err(OrderError.OutOfStock(_))        -> "Out of stock";
        case Result.Err(OrderError.InvalidCart(var msg)) -> "Invalid: " + msg;
    };
}
```

---

## Stream Gatherers — Performance Notes

- `Gatherers.windowSliding()` and `Gatherers.windowFixed()` buffer elements internally. For very large streams, profile memory allocation with JFR.
- Custom Gatherers with a `combiner` support parallel streams. Without a `combiner`, the stream runs sequentially even if `parallel()` is called — document this explicitly.
- Prefer sequential streams for stateful Gatherers unless the domain permits unordered processing.

---

## Build and Verification

| Build Tool | JDK 25 Configuration |
| :--- | :--- |
| Maven | `<java.version>25</java.version>` |
| Gradle | `sourceCompatibility = JavaVersion.VERSION_25` |

- Preview features require `--enable-preview` at both compile and runtime.
- Never enable preview features in production without explicit team sign-off and a migration plan for the next LTS.
- Regenerate AOT caches after each deployment — they are tied to the specific JAR and JVM version.

---

## Additional Resources

- [JDK 25 Release Notes](https://openjdk.org/projects/jdk/25/)
- [JEP 456 — Unnamed Variables & Patterns](https://openjdk.org/jeps/456)
- [JEP 485 — Stream Gatherers](https://openjdk.org/jeps/485)
- [JEP 506 — Scoped Values](https://openjdk.org/jeps/506)
- [JEP 513 — Flexible Constructor Bodies](https://openjdk.org/jeps/513)
- [Gatherers Javadoc](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/Gatherers.html)
