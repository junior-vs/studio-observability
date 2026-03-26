---
description: 'Java exception handling guidelines for JDK 21 LTS — functional Result<T,E> for business failures, domain-specific exceptions for infrastructure errors, adapter boundary rules. Based on Effective Java 3rd ed. ch. 10 and Functional Programming in Java (Manning) ch. 7.'
applyTo: '**/*.java'
---

# Java Exception Handling Guidelines (JDK 21 LTS)

> **Two sources, one strategy**
> *Effective Java* ch. 10 (Bloch) defines when and how to throw exceptions.
> *Functional Programming in Java* ch. 7 (Manning) introduces modeling failures as values.
> These guidelines unify both: functional `Result<T, E>` for the domain core,
> domain-specific unchecked exceptions for the infrastructure boundary.

---

## The Core Distinction

Every failure falls into exactly one of three categories. Assigning a failure to the
wrong category is the root cause of most exception-handling bugs.

| Category | Definition | Examples | Model as |
| :--- | :--- | :--- | :--- |
| **Expected business failure** | A predictable outcome the domain anticipates | User not found, validation rejected, out of stock, duplicate email | `Result<T, E>` or `Optional<T>` |
| **Infrastructure failure** | An unrecoverable condition outside the domain's control | Database unreachable, network timeout, filesystem error | Domain-specific unchecked exception |
| **Programming bug** | A violated contract that should never occur in correct code | Null where forbidden, illegal state, index out of bounds | Let it propagate — fix the code |

> **Golden rule** (*Effective Java*, Item 70): If you write a `catch` block to react to
> a business condition, that condition should have been modeled as `Result<T, E>` instead.

---

## The Evolution: Optional → Either → Result

Understanding *why* `Result` exists requires understanding the problem it solves.

**`Optional<T>`** handles *absence* — it answers "is there a value?", but not *why* the
value is missing. When you need to distinguish "not found" from "invalid input",
`Optional` is not enough.

**`Either<L, R>`** is the generalization: it holds *either* a value of type `L` *or* a
value of type `R`, never both. By convention, `Right` represents success and `Left`
represents error. Both sides can hold any type.

**`Result<T, E>`** is a *success-biased* `Either` specialized for computations that may
fail. The success side holds `T`; the error side holds a typed domain error `E`. The
compiler forces every caller to handle both cases — the error path is impossible to ignore.

```
Optional<T>  →  present or absent                  (no reason for absence)
Either<L, R> →  left(error) or right(success)      (both sides generic)
Result<T, E> →  Ok(value) or Err(failure)          (biased toward success, typed error)
```

---

## The `Result<T, E>` Type

Place this in your shared domain module. `E` must always be **unbounded** — domain
errors are plain sealed values, not exceptions. Never declare `Result<T, E extends Exception>`.

```java
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    record Ok<T, E>(T value)  implements Result<T, E> {}
    record Err<T, E>(E error) implements Result<T, E> {}

    // Functor: transform the success value — Err passes through unchanged
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

    // Extract value or compute a default lazily — never use an eager alternative
    default T getOrElse(Supplier<T> fallback) {
        return switch (this) {
            case Ok(var v)  -> v;
            case Err(var e) -> fallback.get();
        };
    }

    // Transform the error type without touching the success value
    // Useful when crossing layer boundaries that use different error types
    default <F> Result<T, F> mapError(Function<E, F> f) {
        return switch (this) {
            case Ok(var v)  -> new Ok<>(v);
            case Err(var e) -> new Err<>(f.apply(e));
        };
    }

    // Lift a plain function A→B to work over Result<A,E>→Result<B,E>
    // Reuse existing pure functions inside Result pipelines without modifying them
    static <A, B, E> Function<Result<A, E>, Result<B, E>> lift(Function<A, B> f) {
        return result -> result.map(f);
    }

    // Lift a binary curried function (A→B→C) to combine two independent Results
    // Short-circuits on the first Err — does not accumulate errors
    static <A, B, C, E> Result<C, E> lift2(
            Function<A, Function<B, C>> f,
            Result<A, E> ra,
            Result<B, E> rb) {
        return ra.flatMap(a -> rb.map(b -> f.apply(a).apply(b)));
    }

    static <T, E> Result<T, E> ok(T value)  { return new Ok<>(value); }
    static <T, E> Result<T, E> err(E error) { return new Err<>(error); }
}
```

> **`Result<Void, E>`**: Use `Result<Void, E>` for operations that succeed without
> returning a value (delete, fire-and-forget). Return `Result.ok(null)` for the success
> case — `Void` cannot be instantiated; `null` is its only valid value.

---

## Modeling Domain Errors as Sealed Types

Domain errors are a **closed, named set of variants** — exactly what sealed interfaces
model. Each variant carries structured data. When a new variant is added, every
`switch` that must be updated becomes a **compile error** (*Effective Java*, Item 73).

```java
// Good: Sealed domain error type — closed set, structured data, compiler-enforced handling
public sealed interface UserError
    permits UserError.NotFound, UserError.EmailAlreadyExists, UserError.InvalidInput {

    record NotFound(String userId)                   implements UserError {}
    record EmailAlreadyExists(String email)          implements UserError {}
    record InvalidInput(String field, String reason) implements UserError {}
}

// Avoid: Open exception hierarchy — new subtypes added silently; switch falls to default
public class UserNotFoundException extends RuntimeException { ... }
public class DuplicateEmailException extends RuntimeException { ... }
```

---

## `Optional<T>` vs `Result<T, E>` — Decision Rule

| Use | When |
| :--- | :--- |
| `Optional<T>` | Absence is the only information — the caller does not need to know *why* |
| `Result<T, E>` | The failure carries a structured reason the caller must act on |

```java
// Optional: simple absence — "found or not found", no reason needed
Optional<User> findById(String id);

// Result: structured failure — caller must distinguish NotFound from InvalidInput
Result<User, UserError> findByEmail(String email);
```

---

## Lifting: Reusing Pure Functions Inside Result Pipelines

`lift` and `lift2` allow reusing existing pure functions — that know nothing about
`Result` — inside a `Result` pipeline, without modifying them
(*Functional Programming in Java*, ch. 7).

```java
// Plain pure functions — unaware of Result
Function<String, String> trim       = String::trim;
Function<String, String> upperFirst = s -> s.isEmpty() ? s :
    Character.toUpperCase(s.charAt(0)) + s.substring(1);

// Use them directly with .map() — no wrapper needed
Result<String, UserError> displayName = findName(id)
    .map(trim)
    .map(upperFirst);

// lift2: combine two independent Results with a binary function
// Fails fast on the first Err — does not accumulate; use applicative validation for that
Result<FullName, UserError> fullName = Result.lift2(
    first -> last -> new FullName(first, last),
    validateFirstName(input),
    validateLastName(input)
);
```

---

## Complete Example: User CRUD Across All Layers

The following applies the strategy end-to-end. Each layer has a single, clear
responsibility regarding failures.

### Layer 1 — Domain Errors

```java
public sealed interface UserError
    permits UserError.NotFound, UserError.EmailAlreadyExists, UserError.InvalidInput {

    record NotFound(String userId)                   implements UserError {}
    record EmailAlreadyExists(String email)          implements UserError {}
    record InvalidInput(String field, String reason) implements UserError {}
}
```

### Layer 2 — Domain Model

```java
// Invariants enforced at construction — an invalid User cannot be created
public record User(String id, String name, String email, boolean active) {
    public User {
        Objects.requireNonNull(id,    "id");
        Objects.requireNonNull(name,  "name");
        Objects.requireNonNull(email, "email");
        if (name.isBlank())        throw new IllegalArgumentException("name must not be blank");
        if (!email.contains("@")) throw new IllegalArgumentException("invalid email: " + email);
    }
}
```

### Layer 3 — Repository Interface (domain layer)

```java
// The domain layer sees only Result — it never catches or throws infrastructure exceptions
public interface UserRepository {
    Result<User, UserError>       findById(String id);
    Result<User, UserError>       findByEmail(String email);
    Result<List<User>, UserError> findAll();
    Result<User, UserError>       save(User user);
    Result<Void, UserError>       deleteById(String id); // Result.ok(null) on success
}
```

### Layer 4 — Service (application layer)

```java
public class UserService {

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    // flatMap chains: each step runs only if the previous returned Ok
    public Result<User, UserError> create(String name, String email) {
        return validateName(name)
            .flatMap(validName -> validateEmail(email))
            .flatMap(validEmail -> ensureEmailNotTaken(email))
            .flatMap(ignored -> {
                var user = new User(UUID.randomUUID().toString(), name, email, true);
                return repository.save(user);
            });
    }

    public Result<User, UserError> findById(String id) {
        return validateId(id).flatMap(repository::findById);
    }

    public Result<List<User>, UserError> findAll() {
        return repository.findAll();
    }

    public Result<User, UserError> update(String id, String newName, String newEmail) {
        return validateId(id)
            .flatMap(validId -> validateName(newName))
            .flatMap(validName -> validateEmail(newEmail))
            .flatMap(validEmail -> repository.findById(id))
            .flatMap(existing -> repository.save(
                new User(existing.id(), newName, newEmail, existing.active())));
    }

    public Result<Void, UserError> delete(String id) {
        return validateId(id)
            .flatMap(repository::findById)       // confirm existence before deleting
            .flatMap(existing -> repository.deleteById(id));
    }

    // Validation helpers — return Result.ok(value) or Result.err(UserError.InvalidInput)
    private Result<String, UserError> validateId(String id) {
        if (id == null || id.isBlank())
            return Result.err(new UserError.InvalidInput("id", "must not be blank"));
        return Result.ok(id);
    }

    private Result<String, UserError> validateName(String name) {
        if (name == null || name.isBlank())
            return Result.err(new UserError.InvalidInput("name", "must not be blank"));
        return Result.ok(name);
    }

    private Result<String, UserError> validateEmail(String email) {
        if (email == null || !email.contains("@"))
            return Result.err(new UserError.InvalidInput("email", "invalid format"));
        return Result.ok(email);
    }

    private Result<Void, UserError> ensureEmailNotTaken(String email) {
        // Ok(user) → email is already taken → return Err
        // Err(NotFound) → email is available → return Ok
        return switch (repository.findByEmail(email)) {
            case Result.Ok<User, UserError> ok   -> Result.err(new UserError.EmailAlreadyExists(email));
            case Result.Err<User, UserError> err -> Result.ok(null);
        };
    }
}
```

### Layer 5 — Repository Implementation (infrastructure layer)

```java
// Business failures → Result.Err  (never throw for these)
// Infrastructure failures → domain-specific unchecked exception (never Result.Err for these)
public class JdbcUserRepository implements UserRepository {

    private final DataSource dataSource;

    public JdbcUserRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Result<User, UserError> findById(String id) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
            stmt.setString(1, id);
            var rs = stmt.executeQuery();
            return rs.next()
                ? Result.ok(mapRow(rs))
                : Result.err(new UserError.NotFound(id));  // business: not found → Err
        } catch (SQLException e) {
            throw new DataAccessException("Failed to find user id=" + id, e); // infra → exception
        }
    }

    @Override
    public Result<User, UserError> save(User user) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "INSERT INTO users (id, name, email, active) VALUES (?, ?, ?, ?) " +
                 "ON CONFLICT (id) DO UPDATE SET name=?, email=?, active=?")) {
            stmt.setString(1, user.id());    stmt.setString(2,  user.name());
            stmt.setString(3, user.email()); stmt.setBoolean(4, user.active());
            stmt.setString(5, user.name());  stmt.setString(6,  user.email());
            stmt.setBoolean(7, user.active());
            stmt.executeUpdate();
            return Result.ok(user);
        } catch (SQLException e) {
            if (e.getSQLState().startsWith("23"))            // unique constraint violation
                return Result.err(new UserError.EmailAlreadyExists(user.email()));
            throw new DataAccessException("Failed to save user id=" + user.id(), e);
        }
    }

    @Override
    public Result<Void, UserError> deleteById(String id) {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            stmt.setString(1, id);
            stmt.executeUpdate();
            return Result.ok(null); // Void: null is the only valid value
        } catch (SQLException e) {
            throw new DataAccessException("Failed to delete user id=" + id, e);
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        return new User(rs.getString("id"), rs.getString("name"),
                        rs.getString("email"), rs.getBoolean("active"));
    }
}
```

### Layer 6 — Controller (adapter layer)

```java
// The ONLY place that converts Result → HTTP response
// Exhaustive switch: adding a new UserError variant without updating this → compile error
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateUserRequest req) {
        return toResponse(service.create(req.name(), req.email()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> findById(@PathVariable String id) {
        return toResponse(service.findById(id));
    }

    @GetMapping
    public ResponseEntity<?> findAll() {
        return toResponse(service.findAll());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestBody UpdateUserRequest req) {
        return toResponse(service.update(id, req.name(), req.email()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return toResponse(service.delete(id));
    }

    private <T> ResponseEntity<?> toResponse(Result<T, UserError> result) {
        return switch (result) {
            case Result.Ok(var value) ->
                ResponseEntity.ok(value);
            case Result.Err(UserError.NotFound e) ->
                ResponseEntity.status(404).body(new ErrorResponse(e.userId() + " not found"));
            case Result.Err(UserError.EmailAlreadyExists e) ->
                ResponseEntity.status(409).body(new ErrorResponse("Email in use: " + e.email()));
            case Result.Err(UserError.InvalidInput e) ->
                ResponseEntity.status(400).body(new ErrorResponse(e.field() + ": " + e.reason()));
        };
    }
}

// Handles infrastructure exceptions globally — controllers stay free of try/catch
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> handleDataAccess(DataAccessException ex) {
        log.error("Data access failure", ex);
        return ResponseEntity.status(503).body(new ErrorResponse("Service temporarily unavailable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(500).body(new ErrorResponse("Internal server error"));
    }
}
```

---

## Infrastructure Exception Rules (*Effective Java*, Items 70–73)

Define a sealed exception hierarchy per domain so adapters and handlers can discriminate
precisely. Generic exceptions (`RuntimeException`, `IllegalStateException`) carry no
domain context and cannot be handled differently by upper layers.

```java
// Good: Sealed exception hierarchy — closed set, discriminable by framework handlers
public sealed class DomainException extends RuntimeException
    permits DataAccessException, IntegrationException {
    protected DomainException(String message, Throwable cause) { super(message, cause); }
}

public final class DataAccessException extends DomainException {
    public DataAccessException(String msg, Throwable cause) { super(msg, cause); }
}

public final class IntegrationException extends DomainException {
    public IntegrationException(String msg, Throwable cause) { super(msg, cause); }
}
```

**Rules — apply to every catch block in the infrastructure layer:**

- **Chain the cause**: always `throw new DataAccessException("msg", e)` — never discard `e`. Discarding the cause permanently loses the root stack trace (*Effective Java*, Item 75).
- **Restore the interrupt flag**: always call `Thread.currentThread().interrupt()` before rethrowing `InterruptedException` (*Effective Java*, Item 72).
- **Catch the most specific type**: never catch `Exception` or `Throwable` in business or service code — only in global handlers.
- **Never swallow**: a catch block that returns `null`, an empty string, or a zero as a fallback hides failures and makes the system impossible to debug.
- **Log once, at the boundary**: never log and rethrow the same exception — it produces duplicate stack traces in the log.

```java
// Good: Specific catches, cause always chained, interrupt flag restored
try {
    return httpClient.send(request, bodyHandler);
} catch (IOException e) {
    throw new IntegrationException("External call failed: " + request.uri(), e);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt(); // mandatory — never omit
    throw new IntegrationException("Call interrupted: " + request.uri(), e);
}

// Avoid: Cause discarded — root trace permanently lost
throw new DataAccessException("Failed to save"); // missing ", e"

// Avoid: Log AND rethrow — produces duplicate entries in the log
log.error("Error saving user", e);
throw new DataAccessException("Failed to save user", e); // logged again at the boundary
```

---

## Decision Flowchart

```
Is the failure a predictable business outcome?
│
├── YES → Return Result<T, E>
│         ├── E must be unbounded — NOT E extends Exception
│         ├── Use a sealed type for E with one record per variant
│         ├── Chain operations with flatMap — short-circuits on first Err
│         └── Convert to HTTP / response only at the adapter boundary
│
└── NO  → Is it an infrastructure failure (DB, network, I/O)?
          │
          ├── YES → Throw a domain-specific unchecked exception
          │         ├── Always chain the original cause
          │         ├── Never catch Exception/Throwable in service code
          │         └── Handle globally via @ExceptionHandler / ExceptionMapper
          │
          └── NO  → Programming bug (null contract, illegal state)?
                    └── Let it propagate as-is — fix the code, never swallow
```

---

## Common Mistakes

| Mistake | Correct Approach |
| :--- | :--- |
| `Result<T, E extends Exception>` | Unbounded `E` — domain errors are plain sealed values |
| `throw new OutOfStockException(...)` for a business condition | `return Result.err(new OrderError.OutOfStock(...))` |
| `Optional` when the caller needs to know *why* it failed | `Result<T, E>` with a sealed error type |
| Catching `Exception` to wrap in `Result.err(...)` | Business failures and infra errors must stay in separate channels |
| `catch (e) { return null; }` | Rethrow with context or return a meaningful `Result.Err` |
| `throw new MyException("msg")` — cause missing | Always chain: `throw new MyException("msg", e)` |
| `optional.get()` without checking | Use `.map()`, `.flatMap()`, `.orElseGet(supplier)` |
| Logging AND rethrowing the same exception | Log once at the boundary — never both |
| Forgetting `Thread.currentThread().interrupt()` | Always restore before rethrowing `InterruptedException` |
| `default` clause in a sealed `switch` | Remove it — let the compiler enforce exhaustiveness |

---

## Additional Resources

- *Effective Java*, 3rd edition — Joshua Bloch, Chapter 10 (Items 69–77)
- *Functional Programming in Java* — Manning, Chapter 7
- [JEP 409 — Sealed Classes](https://openjdk.org/jeps/409)
- [JEP 441 — Pattern Matching for switch](https://openjdk.org/jeps/441)
- [SonarQube Rule S2139 — Exceptions should not be logged and rethrown](https://rules.sonarsource.com/java/RSPEC-2139/)
- [SonarQube Rule S2142 — InterruptedException should not be ignored](https://rules.sonarsource.com/java/RSPEC-2142/)
