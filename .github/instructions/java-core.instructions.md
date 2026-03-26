---
description: 'Guidelines for building high-quality Java applications targeting JDK 21 LTS, covering modern language features, architecture, testing, and static analysis'
applyTo: '**/*.java'
---

# Java Development Guidelines (JDK 21 LTS)

Write clean, maintainable, and modern Java code targeting **JDK 21 LTS**.

> **Scope & Safety**
> - Target: JDK 21 LTS stable features only. Do **not** generate code using preview or JDK 22+ features unless the build files explicitly target that version and the user opts in.
> - Never include real secrets, API keys, or passwords in examples. Use placeholders: `{{API_KEY}}`, `{{DB_PASSWORD}}`, `user@example.com`.
> - All generated code must pass **SonarLint** and **Checkstyle** analysis.
>
> **Conflict Resolution Order**
> 1. Repository build configuration (Maven/Gradle Java version, dependencies)
> 2. Static analysis rules (SonarQube, Checkstyle)
> 3. These guidelines
> 4. Google Java Style Guide

---

## General Instructions

- **Immutability First**: Use `record`, `final` fields, and `List.of()` / `Map.of()`. Design classes to be immutable by default (*Effective Java*, Item 17).
- **Declarative over Imperative**: Use Streams and Lambdas for collection processing. Pipelines must be side-effect free.
- **Modern Syntax**: Use Switch Expressions, Pattern Matching, Text Blocks, and Records to reduce boilerplate.
- **Null Safety**: Never return `null` for collections (return `List.of()`). Use `Optional<T>` only as a return type, never in fields or parameters. Use `Objects.requireNonNull()` for mandatory arguments.
- **Composition over Inheritance**: Use `sealed` interfaces for strict hierarchies and favor composition (*Effective Java*, Item 18).
- **Logging Boundary**: Log only at the application layer (services, controllers, adapters). Never log inside pure domain/business logic.
- **Static Analysis**: Avoid high cognitive complexity. Always use try-with-resources for `AutoCloseable`. When a Sonar rule conflicts with style, the Sonar rule takes precedence to keep CI green.

---

## JDK 21 Stable Feature Baseline

Use these features freely — they are **not** preview:

| Feature | JEP | Stable Since |
| :--- | :--- | :--- |
| Records | JEP 395 | JDK 16 |
| Sealed Classes | JEP 409 | JDK 17 |
| Pattern Matching for `instanceof` | JEP 394 | JDK 16 |
| Pattern Matching for `switch` | JEP 441 | **JDK 21** |
| Text Blocks | JEP 378 | JDK 15 |
| `var` (local type inference) | JEP 286 | JDK 10 |
| Sequenced Collections | JEP 431 | **JDK 21** |
| `Stream.toList()` | — | JDK 16 |

> **Preview in JDK 21 — do not use in production:** Structured Concurrency (JEP 453), Scoped Values (JEP 446), Unnamed Variables (JEP 443), Unnamed Classes (JEP 445).

---

## Best Practices

### 1. Data Modeling with Records

Use `record` for transparent, immutable data carriers. Use compact constructors for validation.

```java
// Good: Immutable value object with invariant validation
public record Money(BigDecimal amount, String currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) throw new IllegalArgumentException("Amount cannot be negative");
    }
}
```

- Do not use `get` prefix on record accessors (use `order.id()`, not `order.getId()`).
- Use Record Patterns (JDK 21) for clean deconstruction in `switch` and `instanceof`.

```java
// Good: Record deconstruction in switch (JDK 21)
return switch (payment) {
    case CreditCard(var num, var expiry) when isExpired(expiry) -> throw new PaymentException("Expired card");
    case CreditCard(var num, var expiry) -> processCredit(num);
    case DigitalWallet(var provider) -> processDigital(provider);
};
```

### 2. Sealed Hierarchies for Domain Modeling

Use `sealed` interfaces to model Algebraic Data Types (ADTs) where the set of subtypes is fixed.

```java
// Good: Closed type hierarchy with exhaustive handling
public sealed interface OrderResult permits OrderResult.Success, OrderResult.Failure {
    record Success(Order order) implements OrderResult {}
    record Failure(String reason, ErrorCode code) implements OrderResult {}
}

// Exhaustive switch — no default branch needed
var message = switch (result) {
    case OrderResult.Success(var order) -> "Loaded: " + order.id();
    case OrderResult.Failure(var reason, var code) -> "Error [%s]: %s".formatted(code, reason);
};
```

### 3. Sequenced Collections (JDK 21+)

Use `SequencedCollection` APIs when element order matters.

- Use `list.getFirst()` and `list.getLast()` — avoid `list.get(0)` and `list.get(list.size() - 1)`.
- Use `list.reversed()` instead of manual reversal.

### 4. Streams and Functional Pipelines

- Use `Stream.toList()` (JDK 16+) for unmodifiable results — not `Collectors.toList()`.
- Use `IntStream`, `LongStream`, `DoubleStream` to avoid boxing overhead (*Effective Java*, Item 45).
- Keep lambdas short. Extract multi-line lambdas into named private methods.

```java
// Good: Clear declarative pipeline
List<String> activeNames = users.stream()
    .filter(User::isActive)
    .map(User::name)
    .toList();

// Avoid: Over-nested functional composition
users.stream().collect(groupingBy(User::getDept,
    mapping(User::getName, filtering(n -> n.length() > 5, toList()))));
```

### 5. Enum Patterns

Use Enums over `int` or `String` constants. Prefer behavioral enums with functional fields over large `switch` blocks (*Effective Java*, Item 34).

```java
// Good: Strategy Enum
public enum Operation {
    PLUS  ((x, y) -> x + y),
    MINUS ((x, y) -> x - y);

    private final BinaryOperator<Double> operator;
    Operation(BinaryOperator<Double> op) { this.operator = op; }
    public double apply(double x, double y) { return operator.apply(x, y); }
}
```

---

## Code Standards

### Naming Conventions

Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html).

| Identifier | Style | Example |
| :--- | :--- | :--- |
| Classes / Records / Interfaces | `UpperCamelCase` | `OrderService`, `UserRecord` |
| Methods | `lowerCamelCase` verb | `calculateTotal`, `isAvailable` |
| Record Components | `lowerCamelCase` noun (no `get` prefix) | `id`, `createdAt` |
| Constants | `UPPER_SNAKE_CASE` | `MAX_RETRIES` |
| Local variables / `var` | `lowerCamelCase`, descriptive | Avoid `data`, `list1` |

- Use `var` only when the type is obvious from the right-hand side (e.g., `var list = new ArrayList<String>()`).

### Exception Handling

- Prefer unchecked exceptions for programming errors (*Effective Java*, Item 70).
- Use checked exceptions only for truly recoverable business conditions.
- Never catch `Throwable` or bare `Exception`. Catch the most specific subtype.
- Catch blocks must log, rethrow, or handle — never silently swallow.

```java
// Good: Try-with-resources + specific exception + cause chaining
try (var lines = Files.lines(path)) {
    return lines.map(this::parse).toList();
} catch (IOException e) {
    throw new UncheckedIOException("Failed to read config: " + path, e);
}
```

### Documentation (Javadoc)

- Document the **contract and invariants**, not the implementation (*Effective Java*, Item 56).
- Use `@param`, `@return`, and `@throws` on all public API methods.
- Use `@Override` on every method that overrides or implements.
- Use `@Nullable` / `@NotNull` (JSR-305) to assist static analysis.
- Mark deprecated APIs with `@Deprecated(since="x.y", forRemoval=true)` and plan removal for a major version.

### Collection Standards

- Declare variables as `List`, `Set`, `Map` — never `ArrayList` or `HashMap` (*Effective Java*, Item 64).
- Use `List.of()`, `Set.of()`, `Map.of()` for read-only collections.
- Store defensive copies in constructors: `this.items = List.copyOf(items)`.

### Resource Management

Use try-with-resources for **all** `AutoCloseable` types (files, sockets, JDBC connections).

```java
// Good
try (var conn = dataSource.getConnection();
     var stmt = conn.prepareStatement(SQL)) {
    // use conn and stmt
}
```

## Defensive Programming

Treat every input as potentially incorrect until proven otherwise. The goal is to make invalid states unrepresentable and to fail loudly at the earliest possible point — never silently.

### 1. Input Validation

Validate all public API arguments immediately at the entry point. Never assume that callers respect contracts.

- Use `Objects.requireNonNull(value, "descriptive message")` for mandatory references.
- Use guard clauses with `IllegalArgumentException` for domain invariants.
- Validate at the **boundary** — don't propagate unvalidated data into domain logic.
```java
// Good: Validation at the entry point — invalid state never enters the domain
public OrderService(InventoryService inventory, AuditLog auditLog) {
    this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
    this.auditLog  = Objects.requireNonNull(auditLog,  "auditLog must not be null");
}

Result<Order, OrderError> place(Cart cart, Customer customer) {
    Objects.requireNonNull(cart,     "cart must not be null");
    Objects.requireNonNull(customer, "customer must not be null");
    if (cart.items().isEmpty()) return Result.err(new OrderError.InvalidCart("Cart is empty"));
    // domain logic runs only with validated inputs
}

// Avoid: Validation deferred or absent — NullPointerException surfaces deep in the call stack
Result<Order, OrderError> place(Cart cart, Customer customer) {
    return inventory.check(cart.items()) // NPE here if cart is null
        .flatMap(this::createOrder);
}
```

### 2. Invariant Enforcement

Use compact constructors in Records and constructors in classes to encode invariants at construction time. An object that has been constructed is always valid — no external validation step is needed.
```java
// Good: Invariants encoded in the type — Email is always valid after construction
record Email(String value) {
    Email {
        Objects.requireNonNull(value, "email must not be null");
        if (!value.contains("@"))
            throw new IllegalArgumentException("Invalid email: " + value);
        value = value.strip().toLowerCase();
    }
}

// Good: Numeric invariant with normalization
record PageRequest(int page, int size) {
    PageRequest {
        if (page < 0) throw new IllegalArgumentException("page must be >= 0, was: " + page);
        if (size < 1) throw new IllegalArgumentException("size must be >= 1, was: " + size);
        size = Math.min(size, 100); // normalize — never trust caller-provided limits
    }
}

// Avoid: Invariant enforced externally — invalid state is constructable and spreadable
record Email(String value) {}
// Callers must remember to validate — and they often don't
```

### 3. Defensive Copies

When accepting or returning mutable objects, always copy them. A `record` with a `final` field pointing to a mutable collection is only shallowly immutable — the contents can still be modified by the caller.
```java
// Good: Defensive copy at construction — caller mutations have no effect
record Department(String name, List<Employee> employees) {
    Department {
        Objects.requireNonNull(employees, "employees must not be null");
        employees = List.copyOf(employees); // snapshot; unmodifiable
    }
}

// Good: Defensive copy on return from a class with mutable internal state
public final class Schedule {
    private final List<LocalDate> holidays = new ArrayList<>();

    public List<LocalDate> holidays() {
        return List.copyOf(holidays); // caller cannot modify internal state
    }
}

// Avoid: Record is shallowly immutable — caller retains a reference to the internal list
record Department(String name, List<Employee> employees) {
    // No compact constructor: employees field is final but the List itself is mutable
}

List<Employee> staff = new ArrayList<>(List.of(alice, bob));
var dept = new Department("Engineering", staff);
staff.add(mallory); // dept.employees() now contains mallory — unintended
```

### 4. Exception Handling as a Defensive Layer

Exceptions are a defensive mechanism for conditions that the domain cannot prevent. Follow these rules to ensure failures are never silently absorbed.

- Catch the **most specific** subtype available — never `Exception` or `Throwable`.
- Always chain the original cause to preserve the full stack trace for diagnosis.
- Restore the interrupt flag when catching `InterruptedException`.
- Never return `null`, an empty string, or a zero as a silent fallback from a catch block.
```java
// Good: Specific catch, cause chained, meaningful context in the message
try (var lines = Files.lines(configPath)) {
    return lines.map(this::parse).toList();
} catch (IOException e) {
    throw new ConfigLoadException("Failed to load config from: " + configPath, e);
}

// Good: InterruptedException always restores the interrupt flag
try {
    return httpClient.send(request, bodyHandler);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // mandatory — restore the flag
    throw new IntegrationException("Request interrupted: " + request.uri(), e);
}

// Avoid: Generic catch swallows the original type and loses context
try {
    return Files.lines(configPath).map(this::parse).toList();
} catch (Exception e) {         // too broad
    return List.of();            // silent fallback — caller cannot distinguish success from failure
}
```

---

## Common Bug Patterns

### 1. Shallow Immutability in Records

Records with mutable components (e.g., `List`, `Date`) are not truly immutable without defensive copies.

```java
// Avoid: Caller can mutate the internal list
public record Order(List<String> items) {}

// Good: Defensive copy in compact constructor
public record Order(List<String> items) {
    public Order { items = List.copyOf(items); }
}
```

### 2. Non-Exhaustive Pattern Matching on Sealed Types

Use `switch` expressions, not `if/instanceof` chains, for sealed hierarchies. The compiler enforces exhaustiveness.

```java
// Avoid: Silent failure if a new Shape subtype is added
if (shape instanceof Circle c) { ... }
else if (shape instanceof Square s) { ... }

// Good: Compiler-enforced exhaustiveness
return switch (shape) {
    case Circle c  -> Math.PI * c.radius() * c.radius();
    case Square s  -> s.side() * s.side();
};
```

### 3. Side Effects in Stream Pipelines

Never modify external state inside `.map()`, `.filter()`, or `.flatMap()`. Use collectors to accumulate results (*Effective Java*, Item 48).

```java
// Avoid: Side effect makes the stream thread-unsafe
List<String> results = new ArrayList<>();
users.stream().filter(User::isActive).forEach(u -> results.add(u.name()));

// Good: Pure pipeline
List<String> results = users.stream()
    .filter(User::isActive)
    .map(User::name)
    .toList();
```

### 4. `Optional.get()` Without Guard

Calling `.get()` on an empty `Optional` throws `NoSuchElementException`. Use safe unwrapping instead.

```java
// Avoid
String name = findUser(id).get();

// Good
String name = findUser(id).orElseThrow(() -> new UserNotFoundException(id));
```

### 5. Identity Checks on Value-Based Classes

Never use `==` or `synchronized` on `Optional`, `LocalDate`, `Duration`, or similar value-based classes.

```java
// Avoid
if (optA == optB) { ... }           // Undefined behavior
synchronized (localDate) { ... }    // May throw or deadlock

// Good
if (optA.equals(optB)) { ... }
```

### 6. Inefficient SequencedCollection Access

`list.get(0)` on a `LinkedList` is O(n). Use the Sequenced Collections API.

```java
// Avoid
String first = linkedList.get(0);

// Good (JDK 21+)
String first = linkedList.getFirst();
```

---

## Common Code Smells

### 1. Primitive Obsession

Replace raw primitives (`String`, `int`) used as domain concepts with typed Records.

```java
// Avoid: String passed anywhere, no validation guarantee
void process(String email, double amount) { ... }

// Good: Self-validating domain types
record Email(String value) {
    public Email { Objects.requireNonNull(value); /* add regex */ }
}
void process(Email email, Money amount) { ... }
```

### 2. Deep Nesting (Arrow Code)

Use guard clauses, pattern matching, and functional pipelines to flatten logic.

```java
// Avoid: Three levels of nesting
if (user != null) {
    if (user.isActive()) {
        if (user.hasRole(ADMIN)) { ... }
    }
}

// Good: Guard clauses flatten the happy path
if (user == null || !user.isActive() || !user.hasRole(ADMIN)) return;
// proceed with admin logic
```

### 3. High Cognitive Complexity

Keep methods under ~20 lines. Extract private helpers. Decompose into Records for intermediate state.

### 4. The "Return Null" Habit

- **Collections**: Return `List.of()`, `Set.of()`, or `Map.of()` (*Effective Java*, Item 54).
- **Single values**: Return `Optional<T>` to force the caller to handle absence.

### 5. Inheritance Abuse

Use `extends` only for true "is-a" relationships. Use composition + interfaces for code reuse (*Effective Java*, Item 18). Use `sealed` to control hierarchies.

---

## Architecture Guidelines

### 1. Functional Domain Modeling

Model the domain with `sealed` interfaces and `record` classes. The compiler enforces exhaustive handling.

```java
public sealed interface PaymentMethod permits CreditCard, PayPal, Crypto {}
public record CreditCard(String number, String vaultId) implements PaymentMethod {}
public record PayPal(String email) implements PaymentMethod {}
public record Crypto(String walletAddress, String currency) implements PaymentMethod {}
```

### 2. Encapsulation and Access Control

- Mark classes `final` unless designed for extension (*Effective Java*, Item 15).
- Use `sealed` to allow controlled extension within a module or package.
- Keep implementation classes package-private; expose only interfaces or records.
- Use `module-info.java` in large systems to enforce API boundaries.

### 3. Dependency Inversion and Composition

- Depend on interfaces, not implementations (*Effective Java*, Item 64).
- Inject dependencies via constructors (prefer records for service components where stateless).

### 4. Service Layer Design

- Design services as **stateless** to ensure thread-safety and scalability.
- Use records as DTOs between layers (Controller → Service → Repository) to prevent accidental mutation.

### 5. Error Handling Strategy

- **Expected business failures** (`UserNotFound`, `InsufficientFunds`): model as sealed `Result<T, E>` types — the compiler enforces handling.
- **Infrastructure/programming errors**: use unchecked exceptions with proper cause chaining.

---

## Performance

### 1. Profile Before Optimizing (*Effective Java*, Item 67)

- Use **Java Flight Recorder** (`-XX:StartFlightRecording`) for production profiling with near-zero overhead.
- Use **JMH** for benchmarks — never `System.currentTimeMillis()`.

### 2. Avoid Unnecessary Boxing

Use `IntStream`, `LongStream`, `DoubleStream` for numeric processing.

```java
// Avoid: Boxes 1 million integers
List<Integer> boxed = IntStream.range(0, 1_000_000).boxed().toList();

// Good: Stays primitive
int[] primitive = IntStream.range(0, 1_000_000).toArray();
```

### 3. Collection Initialization

Pre-size `ArrayList` and `HashMap` when the size is known to avoid expensive resizing/rehashing.

```java
// Good
var map = new HashMap<String, User>(expectedSize * 4 / 3 + 1);
```

### 4. String Handling

- Use `+` for simple concatenation (compiler uses `StringConcatFactory`).
- Use `StringBuilder` only inside loops.
- Use `.formatted()` or Text Blocks for multiline strings — processed at compile-time, zero runtime overhead.

### 5. GC Selection

- **G1GC**: reliable default for most applications.
- **ZGC (Generational)**: use `-XX:+UseZGC -XX:+ZGenerational` (stable in JDK 21) for low-latency requirements with large heaps.

---

## Testing Standards

### 1. Tooling Stack

- **Framework**: JUnit 5 (Jupiter) — use `@ParameterizedTest`, `@Nested`, `@DisplayName`.
- **Assertions**: AssertJ — use fluent assertions. Avoid `junit.jupiter.api.Assertions`.
- **Mocking**: Mockito — use `@Mock`, `@InjectMocks`, and `MockitoExtension`.

### 2. Structure: AAA Pattern

- **Naming**: `methodName_Scenario_ExpectedBehavior` (e.g., `withdraw_InsufficientFunds_ThrowsException`).
- **Isolation**: Tests must not share mutable state. Reset with `@BeforeEach`.
- **Readability**: Use `@DisplayName` to describe business requirements in plain language.

### 3. Testing Records and Sealed Classes

- Do **not** test auto-generated `equals`, `hashCode`, or `toString` on records — test compact constructor invariants instead.
- Use `switch` expressions in tests to ensure all sealed subtypes are covered.

```java
@Test
@DisplayName("Money compact constructor rejects negative amounts")
void constructor_NegativeAmount_ThrowsException() {
    assertThatThrownBy(() -> new Money(new BigDecimal("-1.00"), "USD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be negative");
}
```

### 4. AssertJ Fluent Assertions

- **Collections**: `containsExactly()`, `hasSize()`, `allSatisfy()`.
- **Optionals**: `isPresent()`, `contains()`, `isEmpty()`.
- **Exceptions**: `assertThatThrownBy()` with cause chain verification.

### 5. Parameterized Tests

Avoid duplicated test logic. Use `@ParameterizedTest` for boundary conditions.

```java
@ParameterizedTest
@CsvSource({"10, 2, 5", "20, 4, 5", "100, 10, 10"})
void divide_ValidInputs_ReturnsExpected(int a, int b, int expected) {
    assertThat(calculator.divide(a, b)).isEqualTo(expected);
}
```

### 6. Common Issues

| Issue | Solution | Example |
| :--- | :--- | :--- |
| Expensive collection resizing | Pre-size collections | `new ArrayList<>(expectedSize)` |
| Boolean parameter hell | Use Enum or Sealed types | `switch(status)` |
| Deep inheritance | Favor composition | Private field + Interface delegation |

---

## Build and Verification

| Build Tool | Command |
| :--- | :--- |
| Maven | `mvn clean install` |
| Gradle (macOS/Linux) | `./gradlew build` |
| Gradle (Windows) | `gradlew.bat build` |
| SonarScanner | `sonar-scanner -Dsonar.projectKey=<key>` |

- Maven: declare `<java.version>21</java.version>` in `pom.xml`.
- Gradle: set `sourceCompatibility = JavaVersion.VERSION_21`.
- A green build with failing static analysis is **not acceptable** for merge.



---

## Additional Resources

- [JDK 21 Release Notes](https://openjdk.org/projects/jdk/21/)
- [JEP 441 — Pattern Matching for switch](https://openjdk.org/jeps/441)
- [JEP 431 — Sequenced Collections](https://openjdk.org/jeps/431)
- [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- [Effective Java, 3rd Edition — Joshua Bloch](https://www.oreilly.com/library/view/effective-java/9780134686097/)
- [JUnit 5 User Guide](https://junit.org/junit5/docs/current/user-guide/)
- [AssertJ Documentation](https://assertj.github.io/doc/)
- [SonarQube Java Rules](https://rules.sonarsource.com/java/)
