---
description: 'Guidelines for controlling object creation in Java JDK 21 LTS — static factories, Builder pattern, dependency injection, lazy initialization, and immutability. Based on Effective Java 3rd edition, Chapter 2.'
applyTo: '**/*.java'
---

# Java Object Construction Guidelines (JDK 21 LTS)

Control how objects are created. A constructed object must always be **valid, complete,
and ready to use** — with no additional setup required by the caller.

> **Foundation**: These guidelines are grounded in *Effective Java*, 3rd edition (Joshua Bloch),
> Chapter 2 — Items 1, 2, 5, and 6.
>
> **Core rule**: Never allow an object to exist in a partially-initialized state.
> Construction is the single moment where invariants are enforced, dependencies are wired,
> and the object becomes ready. A setter, an `init()` method, or any post-construction
> step is a violation of this rule.

A constructed object must always be valid. The construction site is the single moment
where invariants are enforced, dependencies are wired, and the object becomes ready
to use — with no additional setup required by the caller (*Effective Java*, Items 1, 2, 5).

Never require a caller to call a setter, an `init()` method, or any other post-construction
step before using the object. An object that can exist in a partially-initialized state
is a source of bugs that are difficult to reproduce and impossible for the compiler to prevent.

---

## 1. Static Factory Methods over Public Constructors (Item 1)

Prefer static factory methods as the primary way to obtain instances. Unlike constructors,
they have names that communicate intent, can return subtypes, and can cache or reuse
instances transparently.

**Naming conventions:**

| Name | Intent |
| :--- | :--- |
| `of(...)` | Aggregation — multiple parameters, returns an instance containing them |
| `from(...)` | Type conversion — single parameter of a different type |
| `valueOf(...)` | Richer alternative to `of` / `from` |
| `create()` / `newInstance()` | Guarantees a fresh instance on every call |
| `getInstance()` | May return a cached or shared instance |
| `get<Type>()` | Returns a different type than the declaring class |

```java
// Good: Named factories communicate intent; constructor is private
public final class Money {
    private final BigDecimal amount;
    private final Currency   currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount   = Objects.requireNonNull(amount,   "amount");
        this.currency = Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0)
            throw new IllegalArgumentException("amount must be >= 0, was: " + amount);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    // Converts from minor units (cents) — intent impossible to express with a constructor name
    public static Money ofMinorUnits(long cents, Currency currency) {
        var amount = BigDecimal.valueOf(cents)
                               .movePointLeft(currency.getDefaultFractionDigits());
        return new Money(amount, currency);
    }

    // Cached constant — transparent to the caller
    public static final Money ZERO_BRL =
        Money.of(BigDecimal.ZERO, Currency.getInstance("BRL"));
}

// Usage reads like domain language
var price = Money.of(new BigDecimal("49.90"), brl);
var fee   = Money.ofMinorUnits(199, brl); // R$ 1.99 — self-documenting

// Avoid: Raw constructor — no intent, validation scattered at call sites
var price = new Money(new BigDecimal("49.90"), brl);
```

**Returning a subtype without exposing the implementation:**

```java
// Good: Factory returns the interface; sealed subtypes are internal
public sealed interface Discount permits Discount.Percentage, Discount.Fixed {

    BigDecimal apply(BigDecimal price);

    record Percentage(int rate) implements Discount {
        public BigDecimal apply(BigDecimal price) {
            return price.multiply(BigDecimal.valueOf(1 - rate / 100.0));
        }
    }

    record Fixed(BigDecimal value) implements Discount {
        public BigDecimal apply(BigDecimal price) {
            return price.subtract(value).max(BigDecimal.ZERO);
        }
    }

    static Discount percentage(int rate) {
        if (rate < 0 || rate > 100)
            throw new IllegalArgumentException("rate must be in [0, 100], was: " + rate);
        return new Percentage(rate);
    }

    static Discount fixed(BigDecimal value) {
        return new Fixed(Objects.requireNonNull(value, "value"));
    }
}

// Caller depends on the interface only — the record type is invisible
Discount promo = Discount.percentage(20);
```

---

## 2. Builder for Objects with Many Parameters (Item 2)

When a class has more than ~4 parameters — especially optional ones — use the Builder
pattern. The Builder is mutable during construction; the final object is not.

**When to use Builder:**
- More than ~4 constructor parameters
- Several parameters are optional with sensible defaults
- The construction process is a meaningful multi-step operation

```java
// Good: Immutable object constructed exclusively through its Builder
public final class EmailMessage {
    private final String       from;
    private final List<String> to;
    private final List<String> cc;
    private final String       subject;
    private final String       body;
    private final boolean      highPriority;

    private EmailMessage(Builder b) {
        this.from         = b.from;
        this.to           = List.copyOf(b.to);  // defensive copy
        this.cc           = List.copyOf(b.cc);
        this.subject      = b.subject;
        this.body         = b.body;
        this.highPriority = b.highPriority;
    }

    public String       from()          { return from; }
    public List<String> to()            { return to; }
    public List<String> cc()            { return cc; }
    public String       subject()       { return subject; }
    public String       body()          { return body; }
    public boolean      isHighPriority(){ return highPriority; }

    // Required fields are parameters of the factory — impossible to forget them
    public static Builder builder(String from, String to) {
        return new Builder(from, to);
    }

    public static final class Builder {
        private final String       from;
        private final List<String> to;
        private List<String> cc           = List.of();
        private String       subject      = "";
        private String       body         = "";
        private boolean      highPriority = false;

        private Builder(String from, String to) {
            this.from = Objects.requireNonNull(from, "from");
            this.to   = List.of(Objects.requireNonNull(to, "to"));
        }

        public Builder cc(String... addresses) { this.cc = List.of(addresses); return this; }
        public Builder subject(String s)       { this.subject = Objects.requireNonNull(s); return this; }
        public Builder body(String b)          { this.body    = Objects.requireNonNull(b); return this; }
        public Builder highPriority()          { this.highPriority = true; return this; }

        public EmailMessage build() {
            if (subject.isBlank())
                throw new IllegalStateException("subject must not be blank");
            return new EmailMessage(this);
        }
    }
}

// Usage: reads like a sentence; required fields enforced at compile time
var message = EmailMessage
    .builder("sender@example.com", "recipient@example.com")
    .cc("manager@example.com")
    .subject("Q3 Report")
    .body("Please find attached...")
    .highPriority()
    .build();

// Avoid: Telescoping constructor — which boolean is highPriority? which is readReceipt?
var message = new EmailMessage(
    "sender@example.com", "recipient@example.com",
    null, "Q3 Report", "Please find attached...", true, false
);

// Avoid: Mutable JavaBean — object is invalid between new() and the last setter call
var message = new EmailMessage();
message.setFrom("sender@example.com"); // invalid state — no subject, no body yet
message.setSubject("Q3 Report");       // still invalid
message.setBody("...");                // finally usable — compiler never enforced this
```

**Builder with Records for simpler data carriers:**

```java
// Good: Record + Builder for pure data objects with optional fields
public record SearchRequest(String query, int page, int pageSize,
                            String sortField, boolean ascending) {
    public SearchRequest {
        Objects.requireNonNull(query, "query");
        if (page < 0)     throw new IllegalArgumentException("page must be >= 0");
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be >= 1");
        pageSize = Math.min(pageSize, 200);
    }

    public static Builder builder(String query) { return new Builder(query); }

    public static final class Builder {
        private final String query;
        private int     page      = 0;
        private int     pageSize  = 20;
        private String  sortField = "id";
        private boolean ascending = true;

        private Builder(String query) {
            this.query = Objects.requireNonNull(query, "query");
        }

        public Builder page(int page)        { this.page      = page;   return this; }
        public Builder pageSize(int size)    { this.pageSize  = size;   return this; }
        public Builder sortBy(String field)  { this.sortField = field;  return this; }
        public Builder descending()          { this.ascending = false;  return this; }

        public SearchRequest build() { return new SearchRequest(query, page, pageSize, sortField, ascending); }
    }
}

var request = SearchRequest.builder("java 21")
    .page(2).pageSize(50).sortBy("relevance").descending()
    .build();
```

---

## 3. Prefer Dependency Injection to Hardwired Resources (Item 5)

Never hardwire collaborators — repositories, gateways, clocks, HTTP clients — inside a
class using `new` or static access. Inject them through the constructor. Dependencies
become explicit, the class stays immutable, and tests can substitute any dependency
without mocks of framework internals.

```java
// Good: All dependencies injected; fields are final; class is thread-safe by construction
public final class OrderService {
    private final InventoryRepository inventory;
    private final PaymentGateway      payments;
    private final Clock               clock;

    public OrderService(InventoryRepository inventory,
                        PaymentGateway payments,
                        Clock clock) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.payments  = Objects.requireNonNull(payments,  "payments");
        this.clock     = Objects.requireNonNull(clock,     "clock");
    }

    public OrderConfirmation place(Cart cart) {
        var placedAt = Instant.now(clock); // deterministic in tests — Clock.fixed(...)
        // ...
    }
}

// Avoid: Hardwired — cannot test without a real database and a real payment gateway
public final class OrderService {
    private final InventoryRepository inventory = new JdbcInventoryRepository(); // hardwired
    private final PaymentGateway      payments  = StripeGateway.INSTANCE;        // hardwired
}

// Avoid: Static clock — non-deterministic, non-substitutable in tests
var now = System.currentTimeMillis();
```

**Injecting a factory (`Supplier<T>`) when a fresh instance is needed per call:**

```java
// Good: Supplier injected — a new parser is created only when needed, and closed after use
public final class ReportParser {
    private final Supplier<XmlParser> parserFactory;

    public ReportParser(Supplier<XmlParser> parserFactory) {
        this.parserFactory = Objects.requireNonNull(parserFactory, "parserFactory");
    }

    public Report parse(InputStream input) {
        try (var parser = parserFactory.get()) {
            return parser.parse(input);
        }
    }
}

// Wiring at the composition root
var parser = new ReportParser(XmlParser::new);
```

---

## 4. Lazy Initialization for Expensive Objects (Item 6)

Avoid creating an object until it is actually needed. Do not use `null` as a sentinel —
it scatters `if (field == null)` guards across the class and is not thread-safe.

**For instance-level lazy fields:** use a memoizing `Supplier<T>`.
**For class-level shared instances:** use the initialization-on-demand holder idiom.

```java
// Good: Memoizing Supplier — computed once on first get(), cached for all subsequent calls
public final class ReportService {
    private final Supplier<HeavyReportEngine> engine;

    public ReportService(Supplier<HeavyReportEngine> engineFactory) {
        this.engine = memoize(engineFactory);
    }

    public Report generate(ReportRequest request) {
        return engine.get().run(request); // HeavyReportEngine created here, on first call only
    }
}

// Good: Thread-safe memoizing Supplier — no external library required
static <T> Supplier<T> memoize(Supplier<T> delegate) {
    return new Supplier<>() {
        private volatile T value;

        @Override
        public T get() {
            if (value == null) {
                synchronized (this) {
                    if (value == null) value = delegate.get(); // double-checked locking
                }
            }
            return value;
        }
    };
}

// Good: Initialization-on-demand holder — lazy, thread-safe, zero synchronization overhead
public final class SchemaValidator {
    private SchemaValidator() {}

    private static final class Holder {
        // Loaded by the JVM only when Holder is first referenced — on the first getInstance() call
        static final SchemaValidator INSTANCE = new SchemaValidator();
    }

    public static SchemaValidator getInstance() { return Holder.INSTANCE; }
}

// Avoid: Eager initialization — pays the cost even if the object is never used
public final class ReportService {
    private final HeavyReportEngine engine = new HeavyReportEngine(); // always created at startup
}

// Avoid: Null sentinel — race condition in multi-threaded context; null checks scattered
public final class ReportService {
    private HeavyReportEngine engine; // null until first use — invisible invariant

    public Report generate(ReportRequest request) {
        if (engine == null) engine = new HeavyReportEngine(); // not thread-safe
        return engine.run(request);
    }
}
```

---

## 5. No Setters — Wither Methods for Immutable Modification

Do not provide setters on domain objects. When a modified version of an immutable object
is needed, use **wither methods** — they return a new instance with the changed field,
leaving the original untouched.

```java
// Good: No setters; wither methods return new instances
public record UserProfile(String name, String email, Locale locale) {
    public UserProfile {
        Objects.requireNonNull(name,   "name");
        Objects.requireNonNull(email,  "email");
        Objects.requireNonNull(locale, "locale");
        if (!email.contains("@"))
            throw new IllegalArgumentException("Invalid email: " + email);
    }

    public UserProfile withName(String name)     { return new UserProfile(name, email, locale); }
    public UserProfile withEmail(String email)   { return new UserProfile(name, email, locale); }
    public UserProfile withLocale(Locale locale) { return new UserProfile(name, email, locale); }
}

// Usage: each step produces a new object; the original is never modified
var original = new UserProfile("Alice", "alice@example.com", Locale.US);
var updated  = original
    .withEmail("alice.smith@example.com")
    .withLocale(Locale.UK);
// original is unchanged — safe to share across threads or keep as a snapshot

// Avoid: Setter allows mutation after construction — any reference can corrupt state
public class UserProfile {
    private String email;
    public void setEmail(String email) { this.email = email; } // anyone, anytime
}
```

---

## Decision Guide

| Situation | Approach |
| :--- | :--- |
| Simple value object, ≤ 4 fields, all required | `record` with compact constructor |
| Value object needing semantic factory names or caching | `record` + static factory methods |
| Value object with subtype variants | `sealed interface` + static factories |
| Complex object with many optional parameters | `Builder` with required fields in `builder()` |
| Shared expensive resource — created at most once | Initialization-on-demand holder |
| Expensive resource — deferred, may never be needed | Injected or internal `Supplier<T>` + `memoize()` |
| Resource created fresh on every call | Injected `Supplier<T>` (not memoized) |
| Service with collaborators | Constructor injection, all fields `final` |

---

## Build and Verification

| Build Tool | Command |
| :--- | :--- |
| Maven | `mvn clean verify` |
| Gradle (macOS/Linux) | `./gradlew build` |
| SonarScanner | `sonar-scanner -Dsonar.projectKey=<key>` |

- Maven: `<java.version>21</java.version>`
- Gradle: `sourceCompatibility = JavaVersion.VERSION_21`

---

## Additional Resources

- [JEP 395 — Records](https://openjdk.org/jeps/395)
- [JEP 409 — Sealed Classes](https://openjdk.org/jeps/409)
- *Effective Java*, 3rd edition — Joshua Bloch, Chapter 2 (Items 1, 2, 5, 6)
