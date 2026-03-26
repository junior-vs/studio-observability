---
description: 'Java testing guidelines for JDK 21 LTS: JUnit 5, AssertJ, Mockito, AAA pattern, parameterized tests, and coverage standards'
applyTo: '**/*Test.java, **/*Tests.java, **/*Spec.java, **/*IT.java'
---

# Java Testing Guidelines (JDK 21 LTS)

## Tooling Stack

| Concern | Library | Notes |
| :--- | :--- | :--- |
| Test framework | **JUnit 5** (Jupiter) | Use `@Test`, `@Nested`, `@ParameterizedTest`, `@DisplayName` |
| Assertions | **AssertJ** | Prefer over `junit.jupiter.api.Assertions` for readability |
| Mocking | **Mockito** | Use `MockitoExtension` with `@Mock` / `@InjectMocks` |
| Integration tests | **Testcontainers** | For database, messaging, and external service tests |

---

## Test Structure: AAA Pattern

Every test must follow **Arrange → Act → Assert**. Each section should be visually separated with a blank line.

```java
@Test
@DisplayName("Should throw exception when account balance is insufficient")
void withdraw_InsufficientFunds_ThrowsException() {
    // Arrange
    var account = new BankAccount("ACC-001", new BigDecimal("50.00"));

    // Act & Assert
    assertThatThrownBy(() -> account.withdraw(new BigDecimal("100.00")))
        .isInstanceOf(InsufficientFundsException.class)
        .hasMessageContaining("insufficient balance");
}
```

---

## Naming Convention

Use the pattern: `methodName_Scenario_ExpectedBehavior`

```java
// Good
void calculateDiscount_ActiveUser_AppliesPercentage()
void findById_UserNotFound_ReturnsEmpty()
void save_NullEmail_ThrowsIllegalArgument()

// Avoid
void testDiscount()
void test1()
void shouldWork()
```

Use `@DisplayName` to describe the **business requirement** in plain English:

```java
@DisplayName("Given an expired credit card, payment processing should be rejected")
@Test
void processPayment_ExpiredCard_ThrowsPaymentException() { ... }
```

---

## Test Organization with `@Nested`

Group tests by **state or scenario** using `@Nested` classes. This creates a specification-like structure.

```java
@DisplayName("BankAccount")
class BankAccountTest {

    @Nested
    @DisplayName("When account has sufficient balance")
    class WhenSufficientBalance {

        @Test
        void withdraw_ValidAmount_DeductsBalance() { ... }

        @Test
        void withdraw_ExactBalance_LeavesZero() { ... }
    }

    @Nested
    @DisplayName("When account is locked")
    class WhenAccountIsLocked {

        @Test
        void withdraw_AnyAmount_ThrowsAccountLockedException() { ... }
    }
}
```

---

## AssertJ — Fluent Assertions

Always prefer AssertJ over JUnit's built-in assertions for better failure messages.

```java
// Collections
assertThat(result)
    .hasSize(3)
    .containsExactly("a", "b", "c")
    .allSatisfy(item -> assertThat(item).isNotBlank());

// Optionals
assertThat(result).isPresent().contains(expectedUser);
assertThat(emptyResult).isEmpty();

// Exceptions
assertThatThrownBy(() -> service.process(null))
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessageContaining("must not be null")
    .hasCauseInstanceOf(NullPointerException.class);

// Records / Objects
assertThat(order)
    .extracting(Order::id, Order::status)
    .containsExactly("ORD-001", Status.PENDING);
```

---

## Mockito — Mocking

Use `MockitoExtension` — never instantiate mocks manually.

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository repository;

    @Mock
    private NotificationService notifications;

    @InjectMocks
    private OrderService service;

    @Test
    void placeOrder_ValidOrder_SavesAndNotifies() {
        // Arrange
        var order = new Order("ORD-001", List.of());
        when(repository.save(order)).thenReturn(order);

        // Act
        service.placeOrder(order);

        // Assert
        verify(repository).save(order);
        verify(notifications).sendConfirmation(order.id());
    }
}
```

**Rules:**
- Use `verify()` only for **side effects** (writes, notifications). Don't verify reads.
- Prefer `when(...).thenReturn(...)` over `doReturn(...).when(...)` for non-void methods.
- Use `@Captor` to capture and inspect arguments passed to mocks.
- Never mock value objects or Records — instantiate them directly.

---

## Parameterized Tests

Use `@ParameterizedTest` to avoid duplicated test logic for boundary conditions and multiple scenarios.

```java
@ParameterizedTest(name = "divide({0}, {1}) should equal {2}")
@CsvSource({
    "10, 2,  5",
    "20, 4,  5",
    "99, 9, 11"
})
void divide_ValidInputs_ReturnsExpected(int a, int b, int expected) {
    assertThat(calculator.divide(a, b)).isEqualTo(expected);
}

// For complex objects, use @MethodSource
@ParameterizedTest
@MethodSource("invalidEmailProvider")
void constructor_InvalidEmail_ThrowsException(String invalidEmail) {
    assertThatThrownBy(() -> new Email(invalidEmail))
        .isInstanceOf(IllegalArgumentException.class);
}

static Stream<String> invalidEmailProvider() {
    return Stream.of("", "  ", "notanemail", "@nodomain");
}
```

---

## Testing Records & Sealed Classes

**Records:**
- Do **not** test auto-generated `equals`, `hashCode`, or `toString` unless you provided a custom implementation.
- Focus tests on **compact constructor validation** (invariants and guard clauses).

```java
@Test
void constructor_NegativeAmount_ThrowsException() {
    assertThatThrownBy(() -> new Money(new BigDecimal("-1.00"), "USD"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be negative");
}
```

**Sealed Classes:**
- Use `switch` expressions in tests to ensure **all permitted subtypes** are exercised.
- Avoid `instanceof` chains in test assertions — use pattern matching.

```java
@Test
void processPayment_CreditCard_ReturnsSuccess() {
    PaymentMethod method = new CreditCard("4111111111111111", "vault-abc");

    OrderResult result = service.process(method);

    assertThat(result).isInstanceOf(OrderResult.Success.class);
    var success = (OrderResult.Success) result;
    assertThat(success.order()).isNotNull();
}
```

---

## Test Isolation Rules

- Tests must **never share mutable state**. Use `@BeforeEach` to reset all fixtures.
- Never rely on test execution order. Each test must be independently runnable.
- Do not call `Thread.sleep()` for timing — use `Awaitility` for async assertions.
- Use `@TempDir` for tests that write to the filesystem.

```java
@BeforeEach
void setUp() {
    repository = mock(OrderRepository.class); // Reset on each test
    service = new OrderService(repository);
}
```

---

## Integration Tests

- Annotate with `@SpringBootTest` (or framework equivalent) only when the full context is required.
- Use **Testcontainers** for tests that need a real database or message broker.
- Separate unit tests from integration tests using naming (`*IT.java`) or Maven/Gradle profiles.
- Integration tests should not mock infrastructure — use the real implementation via containers.

---

## Coverage Standards

| Layer | Minimum Coverage | Focus |
| :--- | :--- | :--- |
| Domain / Use Cases | 90%+ | Business rules, invariants, edge cases |
| Services | 80%+ | Orchestration logic, error paths |
| Controllers / Adapters | 70%+ | Input validation, response mapping |
| Infrastructure | Integration tests | Repository queries, external calls |

- Coverage is a **floor**, not a goal. Prefer meaningful tests over inflating percentages.
- Every `catch` block and `else` branch must have at least one test covering it.

---

## Build Verification

```bash
# Maven — run tests only
mvn test

# Maven — with coverage report (JaCoCo)
mvn verify

# Gradle
./gradlew test
./gradlew jacocoTestReport
```

A **failing test must never be merged**. Disabled tests (`@Disabled`) must include a comment with a linked issue.

```java
@Disabled("Flaky due to race condition — see #1234")
@Test
void someTest() { ... }
```
