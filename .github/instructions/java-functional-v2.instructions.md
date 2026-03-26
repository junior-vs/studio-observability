---
description: 'Functional-first guidelines for Java JDK 21 LTS — immutability, pure functions, algebraic data types, declarative pipelines, and functional error handling'
applyTo: '**/*.java'
---

# Java Functional Programming Guidelines (JDK 21 LTS)

Enforce strict functional programming principles as the **primary design approach**: immutability, pure functions, algebraic data types, and declarative pipelines.

> **Scope**: This is the **functional-first (purista)** instruction. For mixed OOP + functional guidance, see `java.instructions.md`.
>
> **Conflict Resolution Order**
> 1. Repository build configuration (Maven/Gradle Java version, dependencies)
> 2. Static analysis rules (SonarQube, Checkstyle)
> 3. Project-specific security and logging policies
> 4. This file
>
> **Safety**: Never generate hard-coded secrets, tokens, or passwords. Use `{{API_KEY}}`, `{{DB_PASSWORD}}`, `user@example.com` as placeholders. If a request would weaken validation, introduce data races, or remove error handling, prefer the safer option and flag the trade-off.

---

## General Instructions

- **Default to Immutability**: Treat mutability as an explicit, justified exception (*Effective Java*, Item 17).
- **Enforce Pure Functions**: Methods must produce the same output for the same input with zero observable side effects.
- **Push Side Effects to Boundaries**: Keep the domain core pure. Perform I/O, logging, and DB operations only at the outermost architectural layers (adapters, controllers).
- **Logging Boundary**: Log only at the application layer — never inside domain logic or pure functions.
- **Model Failures as Values**: Use sealed `Result<T, E>` for expected business failures. Do not throw exceptions across domain boundaries.
- **Static Analysis**: All generated code must pass SonarQube/SonarLint without suppression. When a Sonar rule conflicts with a guideline here, the Sonar rule takes precedence to keep CI green.

---

## JDK 21 Stable Feature Baseline

| Feature | Stable Since | Primary Functional Use |
| :--- | :--- | :--- |
| Records | JDK 16 | Immutable value objects and DTOs without boilerplate |
| Sealed Classes | JDK 17 | Closed-hierarchy domain states and ADTs (sum types) |
| Pattern Matching for `instanceof` | JDK 16 | Type check + binding without manual cast |
| Pattern Matching for `switch` | JDK 21 | Exhaustive structural dispatch over sealed types |
| Record Patterns | JDK 21 | Destructure records directly in `switch` and `instanceof` |
| Sequenced Collections | JDK 21 | First/last access with `getFirst()`, `getLast()` |
| `Stream.toList()` | JDK 16 | Terminal collector returning unmodifiable list |
| Text Blocks | JDK 15 | Multiline SQL, JSON, templates without concatenation |
| `var` | JDK 10 | Local type inference when type is obvious |

> **Not available in JDK 21:** Unnamed Variables `_` (stable JDK 22), `Stream.gather()` (stable JDK 24), Primitive Patterns in switch (JDK 25). Do not generate these for JDK 21 targets.

---

## Core Functional Principles

### 1. Immutability First

- Declare all fields `final`. Use `record` for value objects — they are implicitly immutable.
- Use `List.of()`, `Set.of()`, `Map.of()` for fixed collections.
- Use `Stream.toList()` as the default terminal collector — returns an unmodifiable list.
- Apply defensive copies in compact constructors when accepting mutable collections.

```java
// Good: Immutable record with validation and defensive copy
record PageRequest(int page, int size) {
    PageRequest {
        if (page < 0)  throw new IllegalArgumentException("page must be >= 0");
        if (size < 1)  throw new IllegalArgumentException("size must be >= 1");
        size = Math.min(size, 100);
    }
}

record Config(List<String> hosts) {
    Config { hosts = List.copyOf(hosts); } // caller mutations have no effect
}

// Avoid: Mutable class exposes state to uncontrolled modification
class PageRequest {
    public int page;
    public int size;
}
```

### 2. Pure Functions

- Separate computation from I/O — compute in pure methods, perform I/O at the call boundary.
- Never mutate method arguments.
- Use `@implSpec` in Javadoc to declare purity explicitly.

```java
// Good: Pure transformation — no I/O, no mutation
static BigDecimal applyDiscount(BigDecimal price, double rate) {
    return price.multiply(BigDecimal.valueOf(1 - rate))
                .setScale(2, RoundingMode.HALF_UP);
}

// Avoid: Side effect contaminates domain logic
static BigDecimal applyDiscount(BigDecimal price, double rate) {
    auditLog.record("discount applied"); // impure — side effect
    return price.multiply(BigDecimal.valueOf(1 - rate));
}
```

Use **memoization** for expensive pure computations to preserve performance:

```java
public class TaxCalculator {
    private final Map<Double, BigDecimal> cache = new ConcurrentHashMap<>();

    public BigDecimal calculate(double rate) {
        return cache.computeIfAbsent(rate, this::heavyComputation);
    }
}
```

### 3. Algebraic Data Types — Sealed Classes and Records

Use `sealed interface` with `record` subtypes to model a closed set of domain variants. The compiler enforces exhaustive handling.

- Never add a `default` clause when switching over a sealed hierarchy.
- Use Record Patterns (JDK 21) for direct field deconstruction in `switch`.

```java
// Good: Closed type hierarchy
sealed interface PaymentResult permits PaymentResult.Success, PaymentResult.Failure {
    record Success(String transactionId, BigDecimal amount) implements PaymentResult {}
    record Failure(String reason, int errorCode)            implements PaymentResult {}
}

// Good: Exhaustive switch with record deconstruction — no default needed
String message(PaymentResult result) {
    return switch (result) {
        case PaymentResult.Success(var id, var amt) -> "Paid %s: %s".formatted(id, amt);
        case PaymentResult.Failure(var reason, var code) -> "Failed [%d]: %s".formatted(code, reason);
    };
}

// Avoid: default hides missing cases when new variants are added
String message(PaymentResult result) {
    return switch (result) {
        case PaymentResult.Success s -> "Paid";
        default -> "Unknown"; // silent mismatch on new variants
    };
}
```

### 4. Result<T, E> — Failures as Values

> **Critical:** Always declare `Result<T, E>` with an **unbounded** `E`. Never use `E extends Exception` — it forces domain errors to be throwable objects, contradicting the principle that failures are values.

```java
// Correct: E is unbounded — domain errors are plain sealed values
sealed interface Result<T, E> permits Result.Ok, Result.Err {
    record Ok<T, E>(T value)  implements Result<T, E> {}
    record Err<T, E>(E error) implements Result<T, E> {}

    // Functor: transform the success value
    default <U> Result<U, E> map(Function<T, U> f) {
        return switch (this) {
            case Ok(var v)  -> new Ok<>(f.apply(v));
            case Err(var e) -> new Err<>(e);
        };
    }

    // Monad: chain operations that may also fail — short-circuits on first Err
    default <U> Result<U, E> flatMap(Function<T, Result<U, E>> f) {
        return switch (this) {
            case Ok(var v)  -> f.apply(v);
            case Err(var e) -> new Err<>(e);
        };
    }

    static <T, E> Result<T, E> ok(T value)  { return new Ok<>(value); }
    static <T, E> Result<T, E> err(E error) { return new Err<>(error); }
}

// Wrong: E extends Exception binds domain model to the exception hierarchy
sealed interface Result<T, E extends Exception> permits Result.Ok, Result.Err { ... }
```

Model domain errors as sealed value hierarchies — not as exception hierarchies:

```java
// Good: Sealed domain error type
sealed interface OrderError permits OrderError.OutOfStock, OrderError.InvalidCart {
    record OutOfStock(List<Item> items) implements OrderError {}
    record InvalidCart(String reason)   implements OrderError {}
}

// Honest signature: compiler forces callers to handle both cases
Result<Order, OrderError> placeOrder(Cart cart) {
    if (!inventory.has(cart.items()))
        return Result.err(new OrderError.OutOfStock(cart.items()));
    if (!cart.isValid())
        return Result.err(new OrderError.InvalidCart("Cart is empty"));
    return Result.ok(repository.save(new Order(cart)));
}

// Monadic chaining: short-circuits on first Err
Result<OrderConfirmation, OrderError> confirm = validateCart(cart)
    .flatMap(this::checkInventory)
    .flatMap(this::applyDiscount)
    .map(this::toConfirmation);

// Avoid: signature lies — compiler cannot enforce error handling at the call site
Order placeOrder(Cart cart) {
    if (!inventory.has(cart.items())) throw new OutOfStockException("Out of stock");
    return repository.save(new Order(cart));
}
```

**When to use `Result<T, E>` vs `Optional<T>` vs exceptions:**

| Situation | Tool |
| :--- | :--- |
| Expected business failure with a reason (`OutOfStock`, `InvalidCart`) | `Result<T, E>` |
| Simple absence with no reason (`User not found`) | `Optional<T>` |
| Infrastructure failure outside the domain (DB down, network timeout) | Unchecked exception with cause |
| Programming bug (illegal argument, null contract violated) | Unchecked exception |

### 5. Declarative Pipelines with Streams

- Use method references for clarity. Avoid stateful lambdas.
- Name `Predicate` and `Function` variables by their behavior — they must read naturally inside a pipeline.

```java
// Good: Named predicates compose into readable pipelines
Predicate<User> isActive   = User::isActive;
Predicate<User> isPremium  = u -> u.tier() == Tier.PREMIUM;
Predicate<User> isEligible = isActive.and(isPremium);

Function<Order, Invoice> toInvoice = order ->
    new Invoice(order.id(), order.total(), LocalDate.now());

List<Invoice> invoices = orders.stream()
    .filter(isEligible)
    .map(toInvoice)
    .toList();

// Avoid: Inline logic duplicated across call sites, hard to compose and test
List<Invoice> invoices = orders.stream()
    .filter(u -> u.isActive() && u.tier() == Tier.PREMIUM)
    .map(o -> new Invoice(o.id(), o.total(), LocalDate.now()))
    .toList();
```

Use `IntStream`, `LongStream`, `DoubleStream` for numeric processing to avoid boxing (*Effective Java*, Item 45).

### 6. Optional for Simple Absence

Use `Optional<T>` only as a return type when a value may be absent and no reason is needed. Never use in fields or parameters.

```java
// Good: Functional chain without manual unwrapping
String city = findUser(id)
    .flatMap(User::address)
    .flatMap(Address::city)
    .map(String::toUpperCase)
    .orElse("Unknown");

// Good: Lazy alternative with or() — remoteConfig() called only if local is empty
Optional<Config> config = localConfig()
    .or(() -> remoteConfig())
    .or(() -> defaultConfig());

// Avoid: Imperative unwrapping defeats the purpose of Optional
Optional<User> opt = findUser(id);
if (opt.isPresent()) {
    return opt.get().name(); // equivalent to a null check
}
return null;

// Avoid: orElse() evaluates eagerly — fetchRemoteConfig() is always called
Config cfg = localConfig().orElse(fetchRemoteConfig());
```

### 7. Higher-Order Functions and Composition

Use `.and()`, `.or()`, `.andThen()`, and `.compose()` to build complex behavior from small, testable, named functions.

```java
// Good: Composed transformations
Function<String, String> normalize  = String::trim;
Function<String, String> upperFirst = s -> s.isEmpty() ? s :
    Character.toUpperCase(s.charAt(0)) + s.substring(1);
Function<String, String> formatName = normalize.andThen(upperFirst);

// Good: Currying and partial application
Function<Double, Function<BigDecimal, BigDecimal>> computeTax =
    rate -> amount -> amount.multiply(BigDecimal.valueOf(1 + rate));

var applyVAT = computeTax.apply(0.23); // Specialized function
BigDecimal total = applyVAT.apply(new BigDecimal("100.00"));
```

### 8. Lazy Evaluation

Defer computation until it is actually needed using `Supplier<T>`. Always use `.orElseGet(Supplier)` on `Optional` for alternative computations — never `.orElse(methodCall())`.

```java
// Good: Supplier defers expensive computation
Supplier<String> expensiveReport = () -> generateFullReport(data);
if (log.isDebugEnabled()) {
    log.debug("Report: {}", expensiveReport.get());
}

// Avoid: Report generated regardless of debug flag
log.debug("Report: {}", generateFullReport(data));
```

### 9. Recursion and Tail-Call Considerations

Java (through JDK 25) does **not** support tail-call optimization. Prefer iterative alternatives or the Trampoline pattern for deep recursion.

```java
// Avoid: StackOverflowError for large inputs — no JVM tail-call optimization
long factorial(long n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}

// Good: Stream-based iterative alternative
long factorial(long n) {
    return LongStream.rangeClosed(1, n).reduce(1, Math::multiplyExact);
}

// Good: Trampoline for naturally recursive algorithms
sealed interface Trampoline<T> {
    record Done<T>(T value) implements Trampoline<T> {}
    record More<T>(Supplier<Trampoline<T>> next) implements Trampoline<T> {}

    default T evaluate() {
        Trampoline<T> current = this;
        while (current instanceof More<T> m) {
            current = m.next().get();
        }
        return ((Done<T>) current).value();
    }
}

Trampoline<Long> factTrampoline(long n, long acc) {
    if (n <= 1) return new Trampoline.Done<>(acc);
    return new Trampoline.More<>(() -> factTrampoline(n - 1, n * acc));
}
// Usage: factTrampoline(10_000, 1).evaluate() — safe for any depth
```

### 10. Validation Styles

- **Monadic** (`flatMap`): short-circuits on the first error — use when subsequent steps depend on previous ones.
- **Applicative** (accumulative): collects all errors — use when validating independent fields simultaneously.

```java
// Good: Accumulative validation collects all field errors
record ValidationResult<T>(T value, List<String> errors) {
    static <T> ValidationResult<T> combine(
            ValidationResult<T> a, ValidationResult<T> b, BinaryOperator<T> merge) {
        var allErrors = Stream.concat(a.errors.stream(), b.errors.stream()).toList();
        return new ValidationResult<>(merge.apply(a.value, b.value), allErrors);
    }
}
```

---

## Exception Handling

**Expected business failures are values. Unexpected infrastructure errors are exceptions.**

### Rule 1 — Infrastructure Failures Use Domain-Specific Unchecked Exceptions

Always chain the original cause. Never use bare `RuntimeException` or `Exception`.

```java
// Good: Cause chained; specific type allows precise handling by adapters
Order save(Order order) {
    try {
        return db.insert(order);
    } catch (SQLException e) {
        throw new DataAccessException("Failed to persist order id=" + order.id(), e);
    }
}

// Good: InterruptedException always restores the interrupt flag
Response call(HttpRequest request) {
    try {
        return httpClient.send(request, bodyHandler);
    } catch (IOException e) {
        throw new IntegrationException("External call failed: " + request.uri(), e);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // mandatory — restore interrupt flag
        throw new IntegrationException("Call interrupted: " + request.uri(), e);
    }
}

// Avoid: Generic exception loses all context
throw new RuntimeException("Error");

// Avoid: Original cause discarded; root stack trace permanently lost
throw new DataAccessException("Failed to save"); // missing ", e"
```

### Rule 2 — Never Swallow Exceptions

Every catch block must: rethrow with context, log and rethrow, or handle with an explicit alternative. Returning `null` or an empty fallback silently corrupts flow.

```java
// Good
try {
    return Files.readString(path);
} catch (IOException e) {
    throw new DataLoadException("Failed to read config: " + path, e);
}

// Avoid: caller cannot distinguish "file empty" from "read failed"
try {
    return Files.readString(path);
} catch (IOException e) {
    return null;
}
```

### Rule 3 — Sealed Exception Hierarchy at the Adapter Boundary

Define a sealed exception hierarchy per domain so that adapters and error handlers can discriminate precisely. Throw domain exceptions **only at the adapter boundary** — never inside the functional core.

```java
// Good: Sealed exception hierarchy mirrors domain structure
sealed class DomainException extends RuntimeException
    permits UserNotFoundException, OrderProcessingException {
    DomainException(String message)                  { super(message); }
    DomainException(String message, Throwable cause) { super(message, cause); }
}

final class UserNotFoundException extends DomainException {
    UserNotFoundException(String userId) { super("User not found: " + userId); }
}

// Avoid: Generic exception — cannot be handled differently by framework error mappers
throw new RuntimeException("Processing failed");
```

---

## Functor and Monad Patterns

`Optional`, `Stream`, `CompletableFuture`, and `Result<T, E>` implement Functor and Monad patterns. Understanding the laws prevents incorrect implementations.

**Functor laws for `map`:**
- **Identity**: `functor.map(x -> x)` equals `functor`.
- **Composition**: `functor.map(f).map(g)` equals `functor.map(f.andThen(g))`.

**Monad laws for `flatMap`:**
- **Left Identity**: `Optional.of(a).flatMap(f)` equals `f.apply(a)`.
- **Right Identity**: `m.flatMap(Optional::of)` equals `m`.
- **Associativity**: `m.flatMap(f).flatMap(g)` equals `m.flatMap(x -> f.apply(x).flatMap(g))`.

```java
// Functor: map transforms the inner value, preserving the wrapper
Optional<String> name  = findUser(id).map(User::name);           // Optional<User> → Optional<String>
List<String>     emails = users.stream().map(User::email).toList(); // Stream<User> → Stream<String>

// Monad: flatMap sequences operations that each produce a wrapped value
Optional<String> city = findUser(id)
    .flatMap(User::address)    // User → Optional<Address>
    .flatMap(Address::city)    // Address → Optional<String>
    .map(String::toUpperCase); // String → String (Functor map)

// Result monad: monadic chaining short-circuits on first Err
Result<OrderConfirmation, OrderError> result = validateCart(cart)
    .flatMap(this::checkInventory)
    .flatMap(this::applyDiscount)
    .map(this::toConfirmation);

// Stream monad: flatMap flattens nested collections
List<String> allTags = articles.stream()
    .flatMap(article -> article.tags().stream())
    .map(Tag::name)
    .distinct()
    .toList();
```

---

## Functional Domain Modeling

Model the domain with types so that invalid states are unrepresentable.

- Validate invariants inside compact constructors — the type is always valid after construction.
- Isolate pure domain logic (calculations, rules) from infrastructure side effects (I/O, database).

```java
// Good: Self-validating domain type
record Email(String value) {
    Email {
        if (value == null || !value.contains("@"))
            throw new IllegalArgumentException("Invalid email: " + value);
        value = value.strip().toLowerCase();
    }
}

// Good: Pure domain core + I/O at the edge
record Order(List<LineItem> items, Discount discount) {
    BigDecimal total() {
        return items.stream()
            .map(LineItem::subtotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .multiply(BigDecimal.ONE.subtract(discount.rate()));
    }
}

class OrderService {
    OrderConfirmation place(Order order) {
        var saved = repository.save(order);             // I/O at the edge
        eventBus.publish(new OrderPlaced(saved.id()));  // I/O at the edge
        return new OrderConfirmation(saved.id(), saved.total());
    }
}

// Avoid: Anemic model relies on external validation; invalid state is constructable
class Email {
    public String value; // mutable, unvalidated
}
```

---

## Code Standards

### Naming Conventions

Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) with these functional-first additions.

| Identifier | Style | Guidance |
| :--- | :--- | :--- |
| Class / Interface / Record / Sealed error type | `UpperCamelCase` | Nouns: `OrderService`, `OutOfStock`, `Email` |
| Method | `lowerCamelCase` verb | Transformation names for pure methods: `applyDiscount`, `toInvoice`, `normalize` |
| Record component | `lowerCamelCase` noun (no `get` prefix) | `id`, `createdAt`, `totalAmount` |
| `Function` / `Predicate` variable | `lowerCamelCase` named by behavior | `isEligible`, `toInvoice`, `formatName` |
| Boolean method / predicate | question form | `isActive()`, `hasDiscount()`, `isEmpty()` |
| Constant (`static final`) | `UPPER_SNAKE_CASE` | `MAX_RETRIES`, `MIN_ORDER_AMOUNT` |
| Test method | `lowerCamelCase` | `methodName_StateUnderTest_ExpectedBehavior` |

- Pure methods should be named as **transformations**, not commands — they return a new value, they do not mutate.
- Name `Function` and `Predicate` variables by behavior, not by type (`isEligible`, not `predicate1`).
- Avoid abbreviations that force the reader to guess context (`registration`, not `reg`; `confirmation`, not `res`).

### `var` — Local Type Inference

Use `var` only when the inferred type is **immediately obvious** from the right-hand side. In functional code, the type of a `Function`, `Predicate`, or `Result` communicates the transformation contract — do not obscure it.

```java
// Good: Type is unambiguous
var users   = new ArrayList<User>();
var timeout = Duration.ofSeconds(30);

// Avoid: Return type is invisible; reader must navigate to the method signature
var result = userRepository.fetch();   // Optional<User>? List<User>? Result<User, ?>?
```

### Documentation (Javadoc)

- Document the **contract, invariants, and purity** — not the implementation.
- Use `@implSpec` to declare that a method is a pure function.
- Use `@param`, `@return`, and `@throws` on all public API members.

```java
/**
 * Applies a percentage discount to the given price.
 *
 * @implSpec Pure function. No I/O, no mutation. Safe inside Stream pipelines.
 * @param price the original price; must be non-null and non-negative
 * @param rate  the discount rate in [0.0, 1.0]
 * @return the discounted price, rounded to 2 decimal places (HALF_UP)
 * @throws IllegalArgumentException if {@code rate} is outside [0.0, 1.0]
 */
public static BigDecimal applyDiscount(BigDecimal price, double rate) { ... }
```

### Annotations

- Use `@Override` on every method that overrides or implements a supertype member.
- Use `@Nullable` / `@NotNull` (JSR-305) to signal nullability to static analysis.
- Never suppress warnings without a comment explaining the specific, justified reason.

### Collection Standards

- Declare variables as `List`, `Set`, `Map` — never `ArrayList`, `HashSet`, `HashMap` (*Effective Java*, Item 64).
- Use `Stream.toList()` as the default terminal collector.
- Never expose internal mutable collections through accessor methods.

### Resource Management

Use try-with-resources for **all** `AutoCloseable` types. Manual `close()` calls are silently skipped when an exception is thrown before them.

```java
// Good
try (var reader = new BufferedReader(new FileReader(path))) {
    return reader.readLine();
}

// Avoid: close() not reached if readLine() throws — resource leak (Sonar S2095)
BufferedReader reader = new BufferedReader(new FileReader(path));
String line = reader.readLine();
reader.close();
```

### Static Analysis — Key Sonar Rules

| Sonar Rule | Description | Functional Mitigation |
| :--- | :--- | :--- |
| `S2095` | Resource not closed | Always use try-with-resources |
| `S4973` | Reference equality on objects | Use `.equals()` or `Objects.equals()` |
| `S2583` / `S2589` | Condition always true/false | Eliminate dead branches; use sealed exhaustiveness |
| `S3776` | Cognitive complexity too high | Extract named functions; flatten with early returns |
| `S1854` | Unused assignment | Remove; in JDK 22+ targets use `_` |

---

## Common Code Smells

### 1. Primitive Obsession

Replace raw primitives used as domain concepts with self-validating Records.

```java
// Avoid: Raw string passed anywhere; validation scattered across callers
void process(String email, double amount) { ... }

// Good: Self-validating domain types
record Email(String value) {
    public Email { Objects.requireNonNull(value); /* add regex */ }
}
void process(Email email, Money amount) { ... }
```

### 2. Deep Nesting (Arrow Code)

Use guard clauses, pattern matching, and functional chains.

```java
// Avoid: Arrow anti-pattern
Optional<Discount> getDiscount(Order order) {
    if (order.isEligible()) {
        if (order.total().compareTo(MINIMUM_ORDER) >= 0) {
            return Optional.of(new Discount(DISCOUNT_RATE));
        }
    }
    return Optional.empty();
}

// Good: Guard clauses flatten the happy path
Optional<Discount> getDiscount(Order order) {
    if (!order.isEligible()) return Optional.empty();
    if (order.total().compareTo(MINIMUM_ORDER) < 0) return Optional.empty();
    return Optional.of(new Discount(DISCOUNT_RATE));
}
```

### 3. Long Parameter Lists

Keep method parameters to **≤ 4**. Group related parameters into a Record.

```java
// Avoid
void registerUser(String name, String email, String role, Locale locale,
                  boolean sendWelcome, String referralCode) { ... }

// Good
record UserRegistration(String name, String email, String role, Locale locale) {}
void registerUser(UserRegistration registration) { ... }
```

### 4. Anti-Patterns Table

| Anti-Pattern | Reason | Solution |
| :--- | :--- | :--- |
| `System.currentTimeMillis()` in logic | Non-deterministic, untestable | Pass `Instant` or `Clock` as argument |
| `Optional.get()` without guard | Throws `NoSuchElementException` | Use `orElseThrow()`, `map()`, or `ifPresent()` |
| Deep recursion | `StackOverflowError` — no JVM TCO | Use `Stream.iterate()` or Trampoline |
| `default` in sealed `switch` | Hides unhandled variants on type additions | Remove `default`; let the compiler enforce |
| `Collectors.toList()` | Returns mutable list — misleading | Use `Stream.toList()` (JDK 16+) |
| `orElse(methodCall())` | Method always evaluated eagerly | Use `orElseGet(() -> methodCall())` |

---

## Architecture Guidelines

- Apply **Single Responsibility**: each class has one reason to change.
- Depend on **interfaces**, not concrete implementations (*Effective Java*, Item 64).
- Organize packages by **feature/domain** (`com.example.orders`), not by layer (`com.example.controllers`).
- Prefer **composition over inheritance**. Use functional interfaces + lambdas before class hierarchies.
- Use `module-info.java` in large codebases to enforce strict API boundaries.

```java
// module-info.java — Functional domain module
module com.example.orders {
    exports com.example.orders.api;     // Public: interfaces, records, Result types
    // com.example.orders.internal is NOT exported — encapsulated by default
}
```

---

## Performance

### 1. Profile Before Optimizing

Use **Java Flight Recorder (JFR)** (`-XX:StartFlightRecording`) and **JMH** for benchmarks. Never use `System.currentTimeMillis()` (*Effective Java*, Item 67).

### 2. Lazy vs Eager

Use `findFirst()`, `anyMatch()`, and `Supplier<T>` to avoid processing elements that are not needed.

```java
// Lazy: stops at first match
Optional<User> admin = users.stream()
    .filter(User::isActive)
    .filter(User::isAdmin)
    .findFirst();

// Avoid: processes the entire stream even if only one result is needed
List<User> admins = users.stream()
    .filter(User::isActive)
    .filter(User::isAdmin)
    .toList();
```

### 3. Capturing Lambdas and GC Pressure

Lambdas that capture local variables create a new object per invocation. Non-capturing lambdas and method references are JVM-cached singletons.

```java
// Avoid: Capturing lambda creates a new object per call in a hot path
orders.stream()
    .filter(o -> o.total().compareTo(threshold) > 0) // captures 'threshold'
    .toList();

// Good: Extract to a static final field — created once
private static final Predicate<Order> HIGH_VALUE =
    o -> o.total().compareTo(HIGH_VALUE_THRESHOLD) > 0;
```

### 4. Streams vs Imperative Loops

Streams introduce pipeline object overhead. For **small collections or tight inner loops**, an imperative loop has lower constant overhead. For **large collections or complex transformations**, Streams are comparable or better due to JIT optimization. Switch to imperative only if JMH profiling shows measurable overhead on a hot path.

---

## Testing Standards

Pure functions require no mocks and no setup — test them in complete isolation.

- Use **JUnit 5** (`@Test`, `@ParameterizedTest`, `@Nested`, `@DisplayName`).
- Use **AssertJ** for fluent assertions.
- Follow **Arrange-Act-Assert (AAA)** structure.
- Use **Mockito** only at infrastructure boundaries (Repositories, HTTP clients). Never mock Records or domain value objects — instantiate them directly.

```java
// Good: Parameterized pure function test — no mocks, no setup
class DiscountTest {

    @ParameterizedTest(name = "rate {1} on {0} -> {2}")
    @CsvSource({
        "100.00, 0.10, 90.00",
        "100.00, 0.00, 100.00",
        "100.00, 1.00, 0.00"
    })
    void applyDiscount_ReturnsExpectedPrice(BigDecimal price, double rate, BigDecimal expected) {
        assertThat(Pricing.applyDiscount(price, rate))
            .isEqualByComparingTo(expected);
    }
}

// Good: Infrastructure boundary test with Mockito
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository repository;
    @InjectMocks OrderService orderService;

    @Test
    void placeOrder_WhenItemsAvailable_ReturnsConfirmedOrder() {
        var order = new Order(List.of(new Item("SKU-1", 2)));
        when(repository.save(order)).thenReturn(order.withStatus(CONFIRMED));

        var result = orderService.placeOrder(order);

        assertThat(result.status()).isEqualTo(CONFIRMED);
    }
}

// Avoid: Mocking domain Records — instantiate them directly
Order mockOrder = mock(Order.class); // unnecessary and misleading
```

---

## Build and Verification

| Build Tool | Command |
| :--- | :--- |
| Maven | `mvn clean verify` |
| Gradle (macOS/Linux) | `./gradlew build` |
| Gradle (Windows) | `gradlew.bat build` |
| SonarScanner | `sonar-scanner -Dsonar.projectKey=<key>` |

- Maven: `<java.version>21</java.version>`
- Gradle: `sourceCompatibility = JavaVersion.VERSION_21`
- A green build with failing static analysis is **not acceptable** for merge.

---

## Additional Resources

- [JDK 21 Release Notes](https://openjdk.org/projects/jdk/21/)
- [JEP 441 — Pattern Matching for switch](https://openjdk.org/jeps/441)
- [JEP 409 — Sealed Classes](https://openjdk.org/jeps/409)
- [JEP 395 — Records](https://openjdk.org/jeps/395)
- [JEP 431 — Sequenced Collections](https://openjdk.org/jeps/431)
- [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [AssertJ Documentation](https://assertj.github.io/doc/)
- [SonarQube Java Rules](https://rules.sonarsource.com/java/)
- *Effective Java* — Joshua Bloch
- *Modern Java in Action* — Raoul-Gabriel Urma
- *Functional Programming in Java* — Pierre-Yves Saumont
- *Functional and Reactive Domain Modeling* — Debasish Ghosh
