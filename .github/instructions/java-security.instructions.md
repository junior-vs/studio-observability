---
description: 'Java security guidelines for JDK 21 LTS: input validation, secrets management, authentication, cryptography, OWASP Top 10 prevention, and static analysis compliance'
applyTo: '**/*.java'
---

# Java Security Guidelines (JDK 21 LTS)

## Non-Negotiable Rules

These rules apply to **every generated file**, no exceptions:

- Never include real secrets, API keys, tokens, passwords, or connection strings in code. Use placeholders: `{{API_KEY}}`, `{{DB_PASSWORD}}`, `{{JWT_SECRET}}`.
- Never log sensitive data: passwords, tokens, PII (CPF, email, phone), card numbers, or stack traces with business context.
- Never disable or suppress security checks with comments like `// safe here` or `@SuppressWarnings("security")` without an explicit justification.
- Never trust user input. Validate and sanitize at every public API boundary.

---

## Secrets Management

- Load secrets from **environment variables** or a secrets manager (Vault, AWS Secrets Manager, Azure Key Vault). Never from `application.properties` committed to source control.
- Use `char[]` instead of `String` for passwords in memory — `String` is immutable and stays in the heap until GC; `char[]` can be zeroed out after use.

```java
// Good: Load from environment
String dbUrl = System.getenv("DB_URL");
if (dbUrl == null) throw new IllegalStateException("DB_URL environment variable not set");

// Good: Zero out password after use
char[] password = getPasswordFromVault();
try {
    authenticate(password);
} finally {
    Arrays.fill(password, '\0'); // Clear sensitive data
}

// Avoid: Hardcoded credentials
String password = "mySecret123"; // NEVER do this
```

---

## Input Validation

Validate all inputs at **public API boundaries** (controllers, service entry points, record constructors). Use compact constructors in Records to enforce invariants.

```java
// Good: Centralized validation in Record compact constructor
public record Email(String value) {
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[\\w.+-]+@[\\w-]+\\.[\\w.-]+$");

    public Email {
        Objects.requireNonNull(value, "email is required");
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid email format: " + value);
        }
    }
}

// Good: Guard clauses at service boundary
public Order placeOrder(PlaceOrderCommand cmd) {
    Objects.requireNonNull(cmd, "command is required");
    Objects.requireNonNull(cmd.customerId(), "customerId is required");
    if (cmd.items().isEmpty()) throw new IllegalArgumentException("Order must have at least one item");
    // ...
}
```

**Rules:**
- Reject on invalid input — never silently fix or guess intent.
- Validate length, format, range, and character set for all string inputs.
- Use allowlists (accepted values) over denylists (rejected patterns) whenever possible.

---

## SQL & Injection Prevention

- Always use **parameterized queries** or an ORM (JPA, jOOQ). Never concatenate user input into SQL strings.
- If using raw JDBC, use `PreparedStatement` exclusively.

```java
// Good: Parameterized query
try (var stmt = connection.prepareStatement(
        "SELECT * FROM users WHERE email = ? AND active = ?")) {
    stmt.setString(1, email);
    stmt.setBoolean(2, true);
    return stmt.executeQuery();
}

// Avoid: SQL injection vulnerability
String query = "SELECT * FROM users WHERE email = '" + email + "'"; // NEVER
```

---

## Authentication & Authorization

- Never implement custom authentication algorithms. Use established libraries: **Spring Security**, **Quarkus OIDC**, **Auth0 SDK**, or **Keycloak**.
- Always validate JWT signatures. Never trust the payload without verifying the signature and expiration.
- Apply the **principle of least privilege**: grant only the minimum permissions required.
- Enforce authorization checks at the **service layer**, not only at the controller layer.

```java
// Good: Authorization at service layer (Spring Security example)
@PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")
public UserProfile getProfile(String userId) { ... }

// Avoid: Authorization only at controller — easy to bypass
@GetMapping("/admin/users")
public List<User> listUsers() {
    // If this method is called internally, the check is bypassed
    return userService.findAll();
}
```

---

## Cryptography

- Use **JCA (Java Cryptography Architecture)** standard APIs. Never implement custom encryption.
- Prefer **AES-GCM** (authenticated encryption) over AES-CBC.
- Use **BCrypt**, **Argon2**, or **PBKDF2** for password hashing. Never use MD5 or SHA-1 for passwords.
- Always use a **cryptographically secure random** generator: `SecureRandom`, never `java.util.Random`.
- Minimum key sizes: AES 256-bit, RSA 2048-bit, EC 256-bit.

```java
// Good: Secure random token generation
byte[] token = new byte[32];
new SecureRandom().nextBytes(token);
String secureToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token);

// Good: Password hashing (Spring Security example)
PasswordEncoder encoder = new BCryptPasswordEncoder(12); // Cost factor 12+
String hash = encoder.encode(rawPassword);

// Avoid: Weak or deterministic random
String token = UUID.randomUUID().toString(); // Not cryptographically secure for tokens
Random random = new Random();               // Predictable — never for security
```

---

## Sensitive Data Handling

- Override `toString()` in classes that hold sensitive data to prevent accidental logging:

```java
// Good: Safe toString on sensitive record
public record Credentials(String username, String password) {
    @Override
    public String toString() {
        return "Credentials{username='" + username + "', password='[REDACTED]'}";
    }
}
```

- Annotate sensitive fields with `@JsonIgnore` (Jackson) or `@ToString.Exclude` (Lombok) to prevent serialization leaks.
- Never log request/response bodies that may contain PII without masking.
- Apply data minimization: only collect and store fields that are strictly necessary.

---

## HTTP & API Security

- Enforce **HTTPS** for all endpoints. Never accept plain HTTP in production.
- Set secure HTTP headers: `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, `Strict-Transport-Security`.
- Implement **rate limiting** on authentication endpoints and public APIs.
- Use **CORS** with explicit allowed origins. Never use `*` in production.
- Validate `Content-Type` on all POST/PUT endpoints to prevent content sniffing attacks.

```java
// Good: Explicit CORS configuration (Spring Security example)
@Bean
CorsConfigurationSource corsConfigurationSource() {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://app.example.com"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

---

## Dependency Security

- Keep dependencies up to date. Run **OWASP Dependency-Check** or **Snyk** as part of CI/CD.
- Prefer well-maintained libraries with active security disclosures over obscure ones.
- Pin transitive dependency versions to avoid silent upgrades introducing vulnerabilities.

```xml
<!-- Maven: OWASP Dependency Check plugin -->
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>{{OWASP_VERSION}}</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS> <!-- Fail on HIGH+ severity -->
    </configuration>
</plugin>
```

---

## Static Analysis Compliance

All generated code must pass these security-related Sonar rules:

| Rule | Description |
| :--- | :--- |
| `S2077` | SQL queries must not be built from dynamic strings |
| `S2076` | OS commands must not be built from user input |
| `S5144` | Server-side request forgery (SSRF) prevention |
| `S5145` | Log injection prevention |
| `S2068` | No hardcoded credentials |
| `S5659` | JWT must always be signed and verified |
| `S4790` | No weak hashing algorithms (MD5, SHA-1) for security |

---

## OWASP Top 10 Quick Reference

| Risk | Prevention in Java |
| :--- | :--- |
| **A01 - Broken Access Control** | Enforce `@PreAuthorize` at service layer; least privilege |
| **A02 - Cryptographic Failures** | AES-256-GCM, BCrypt/Argon2, no MD5/SHA-1, HTTPS only |
| **A03 - Injection** | Parameterized queries, validated inputs, no string concatenation |
| **A04 - Insecure Design** | Sealed domain models, Records for immutability, Result types |
| **A05 - Security Misconfiguration** | Explicit CORS, secure headers, no default credentials |
| **A06 - Vulnerable Components** | OWASP Dependency-Check in CI, pinned versions |
| **A07 - Auth Failures** | Standard libraries (Spring Security), JWT signature validation |
| **A09 - Logging Failures** | No PII in logs, structured logging, no stack traces to clients |
