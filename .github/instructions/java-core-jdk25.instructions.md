---
description: 'JDK 25 LTS addendum to java.instructions.md — covers only stable features introduced between JDK 22 and JDK 25. Apply together with java.instructions.md.'
applyTo: '**/*.java'
---

# Java JDK 25 LTS — Addendum

> **How to use this file**
> This file is a **complement** to `java.instructions.md`, which covers the JDK 21 LTS baseline.
> Do not repeat JDK 21 patterns here. Only apply rules from this file when the project build explicitly targets JDK 25 (`<java.version>25</java.version>` in Maven or `sourceCompatibility = JavaVersion.VERSION_25` in Gradle).
>
> **Preview in JDK 25 — do not use in production:** Structured Concurrency (JEP 505), Primitive Types in Patterns (JEP 507), Stable Values (JEP 502), PEM Encodings (JEP 470).

---

## JDK 25 Stable Feature Baseline

Features **finalized** in JDK 25 that are safe to use in production:

| Feature | JEP | Notes |
| :--- | :--- | :--- |
| Unnamed Variables & Patterns | JEP 456 | Finalized in JDK 22 |
| Scoped Values | JEP 506 | Replaces `ThreadLocal` for immutable context |
| Module Import Declarations | JEP 511 | `import module java.base;` |
| Compact Source Files & Instance Main | JEP 512 | Simplified entry points |
| Flexible Constructor Bodies | JEP 513 | Statements before `super()`/`this()` |
| Key Derivation Function API | JEP 510 | `javax.crypto.KDF` |
| Compact Object Headers | JEP 519 | JVM flag `-XX:+UseCompactObjectHeaders` |
| Generational Shenandoah GC | JEP 521 | `-XX:+UseShenandoahGC` |
| AOT Command-Line Ergonomics | JEP 514 | `-XX:AOTCache=app.aot` |
| AOT Method Profiling | JEP 515 | Profile-guided JIT warmup |

---

## Unnamed Variables and Patterns (`_`)

Use `_` to explicitly discard unused variables in patterns, catch blocks, and lambda parameters. This signals intent and eliminates "variable declared but never used" warnings.

```java
// Good: Unused catch parameter
try {
    return Integer.parseInt(input);
} catch (NumberFormatException _) {
    return 0;
}

// Good: Unused lambda parameter
list.forEach(_ -> counter.increment());

// Good: Partial deconstruction — only 'name' is needed
if (event instanceof UserEvent(var name, _, _)) {
    log.info("User event for: {}", name);
}
```

- Use `_` only when the variable is **genuinely** unused — not as a shortcut for lazy naming.
- For JDK 21 targets, replace `_` with a named variable (e.g., `ignored`, `unused`).

---

## Scoped Values (JEP 506)

Use `ScopedValue` to share **immutable, bounded context** within a thread and its children. Prefer it over `ThreadLocal` for new code, especially alongside virtual threads.

**When to use `ScopedValue` over `ThreadLocal`:**

| Concern | `ThreadLocal` | `ScopedValue` |
| :--- | :--- | :--- |
| Mutability | Mutable | Immutable |
| Lifetime | Entire thread lifetime (risk of leaks) | Bounded to `where()`/`run()` scope |
| Virtual Thread safety | Risky (inherited by child threads) | Safe — explicitly scoped |
| Readable intent | Unclear | Self-documenting scope |

```java
// Good: Scoped value for request context
public class RequestContext {
    public static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

    public void handle(User user, Runnable task) {
        ScopedValue.where(CURRENT_USER, user).run(task);
    }
}

// Access anywhere within the scope — no parameter threading
class AuditService {
    void audit(String action) {
        var user = RequestContext.CURRENT_USER.get(); // throws if not bound
        log.info("Action '{}' by {}", action, user.id());
    }
}
```

- Use `ScopedValue.orElse(default)` when the value may not be bound.
- Do **not** use `ScopedValue` as a general-purpose mutable state store — it is immutable by design.
- `ThreadLocal` remains valid for mutable, thread-local accumulation (e.g., formatters, parsers).

---

## Flexible Constructor Bodies (JEP 513)

Statements that do not reference `this` may now appear **before** `super()` or `this()` in a constructor. Use this to compute and validate arguments before delegating to a superclass, without static helper methods.

```java
// Before JDK 25: required a static helper to validate before super()
public class PositiveInteger extends Number {
    public PositiveInteger(int value) {
        super(validate(value)); // static helper needed
    }
    private static int validate(int v) {
        if (v <= 0) throw new IllegalArgumentException("Must be positive");
        return v;
    }
}

// JDK 25: statements before super() are now valid
public class PositiveInteger extends Number {
    public PositiveInteger(int value) {
        if (value <= 0) throw new IllegalArgumentException("Must be positive");
        super(value); // explicit super() now allowed after statements
    }
}
```

- Statements before `super()`/`this()` **cannot** reference `this` or instance fields.
- This is not a license to add complex logic before delegation — keep pre-super blocks focused on validation and argument transformation.

---

## Module Import Declarations (JEP 511)

Use `import module <name>;` to import all packages exported by a module in a single declaration. This is particularly useful when working heavily with a module's API.

```java
// Before JDK 25: individual imports
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// JDK 25: import the entire module
import module java.base;
```

- Use module imports to reduce boilerplate in files that use many types from one module.
- Resolve **ambiguous types** explicitly: if two modules export the same class name, qualify it fully (e.g., `java.util.Date` vs `java.sql.Date`).
- Module imports do **not** require `module-info.java` in the importing code.

---

## Compact Source Files & Instance Main (JEP 512)

For scripts, utilities, and single-class programs, omit the class declaration and `public static` boilerplate. The file is compiled as an unnamed class.

```java
// JDK 25: valid top-level program — no class wrapper needed
void main() {
    System.out.println("Hello from JDK 25");
}
```

- Use this **only** for scripts, tooling, and exploratory code — never in production application source trees.
- Do not apply to `@SpringBootApplication`, service classes, or anything that needs to be part of a module or package.

---

## Key Derivation Function API (JEP 510)

Use `javax.crypto.KDF` for deriving cryptographic keys from a shared secret. Prefer KDF over manual `SecretKeyFactory` chains for protocols like TLS key expansion, password hashing workflows, and post-quantum schemes.

```java
// Good: Derive a key using HKDF
KDF kdf = KDF.getInstance("HKDF-SHA256");
SecretKey derived = kdf.deriveKey("AES",
    KDFParameters.ofHkdf(inputKeyMaterial, salt, info, 32));
```

- Never implement custom key derivation logic — use `KDF` or established libraries (Bouncy Castle).
- Store only derived keys, never raw secrets.
- Validate the algorithm name against [JCA Standard Names](https://docs.oracle.com/en/java/docs/api/java.base/javax/crypto/KDF.html).

---

## Performance: JVM and GC Updates

### Compact Object Headers (JEP 519)

Reduces object header size from 96–128 bits to 64 bits on 64-bit JVMs. This decreases heap usage and improves GC throughput for workloads with many small objects.

- Enable with `-XX:+UseCompactObjectHeaders` (opt-in in JDK 25).
- Avoid relying on `System.identityHashCode()` for identity-sensitive caches when this flag is active — identity hash behavior is preserved, but the internal layout changes.

### Generational Shenandoah GC (JEP 521)

Shenandoah now supports generational collection, aligning it with G1 and ZGC. Use it as a low-pause alternative to ZGC for workloads where pause predictability is critical.

```
# Enable Generational Shenandoah
-XX:+UseShenandoahGC
```

**GC selection guide for JDK 25:**

| GC | Use case |
| :--- | :--- |
| G1GC (default) | General-purpose, balanced throughput/latency |
| ZGC Generational | Very large heaps, ultra-low latency requirement |
| Shenandoah Generational | Predictable pause times, mid-to-large heaps |

### AOT Cache (JEP 514 + JEP 515)

Dramatically reduces startup time by persisting class-loading and JIT method profiles across runs.

```bash
# Step 1: Run with cache output enabled
java -XX:AOTCacheOutput=app.aot -cp app.jar com.example.Main

# Step 2: Subsequent runs use the cache
java -XX:AOTCache=app.aot -cp app.jar com.example.Main
```

- Use AOT caching in containerized or serverless deployments where cold-start latency matters.
- Regenerate the cache after each deployment — the cache is tied to the specific JAR and JVM version.
- AOT caching is distinct from GraalVM native image; it still runs on the standard HotSpot JVM.

---

## Build and Verification

| Build Tool | JDK 25 Configuration |
| :--- | :--- |
| Maven | `<java.version>25</java.version>` |
| Gradle | `sourceCompatibility = JavaVersion.VERSION_25` |

- Preview features require `--enable-preview` at both compile and runtime — never enable in production without explicit team sign-off.
- Verify the project compiles cleanly without `--enable-preview` before enabling any preview feature.

---

## Additional Resources

- [JDK 25 Release Notes](https://openjdk.org/projects/jdk/25/)
- [JEP 456 — Unnamed Variables & Patterns](https://openjdk.org/jeps/456)
- [JEP 506 — Scoped Values](https://openjdk.org/jeps/506)
- [JEP 511 — Module Import Declarations](https://openjdk.org/jeps/511)
- [JEP 512 — Compact Source Files and Instance Main Methods](https://openjdk.org/jeps/512)
- [JEP 513 — Flexible Constructor Bodies](https://openjdk.org/jeps/513)
- [JEP 510 — Key Derivation Function API](https://openjdk.org/jeps/510)
- [JEP 519 — Compact Object Headers](https://openjdk.org/jeps/519)
- [JEP 521 — Generational Shenandoah](https://openjdk.org/jeps/521)
- [JEP 514 — AOT Command-Line Ergonomics](https://openjdk.org/jeps/514)
