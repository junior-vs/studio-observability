---
title: Metrics Integration — lib-logging-quarkus
version: 1.0
date_created: 2026-03-28
last_updated: 2026-03-28
owner: br.com.vsjr.labs
tags: [design, metrics, quarkus, micrometer, observability, logging]
---

# Introduction

This specification defines the requirements, constraints, and interfaces for implementing
Micrometer-based metrics within the `lib-logging-quarkus` library. The metrics module extends
the existing `@Logged` CDI interceptor and `LogSistematico` DSL to automatically emit execution
timing and failure counters, complementing the structured logging and OpenTelemetry tracing
already in production.

Metrics are the second pillar of observability. While logs answer *what happened* and traces
answer *where latency was introduced*, metrics answer *how often, at what rate, and with what
trend*. This specification ensures the metrics module is implemented in a way that is consistent
with the project's existing architecture, naming conventions, and fault-isolation principles.

## 1. Purpose & Scope

**Purpose:** Define the complete design for introducing Micrometer metrics into `lib-logging-quarkus`
without breaking existing logging, tracing, or CDI contracts.

**Scope:**

- Automatic metrics emitted by `LogInterceptor` for every `@Logged` method invocation.
- Injection contract for `MeterRegistry` in CDI beans within the library.
- Naming and tagging conventions for all metrics emitted by the library.
- Configuration properties for enabling or disabling each metric category.
- Guidelines for application developers adding custom business metrics.
- Fault isolation: metric infrastructure failures must never propagate to business logic.

**Out of scope:**

- Dashboard or alert-rule definitions (owned by the operations team).
- Backend-specific configuration beyond Prometheus and OTLP (e.g., Datadog, InfluxDB).
- Metrics for OpenTelemetry spans or traces (managed by `quarkus-opentelemetry`).
- A/B testing or feature-flag instrumentation.

**Intended audience:** Library maintainers implementing the metrics module, and application
developers consuming the library who need to add business-level metrics.

**Assumptions:**

- The library runs on Quarkus 3.32.3 with Java 21 (compiled to target 25).
- `quarkus-micrometer-registry-prometheus` is already declared in `pom.xml` but disabled in
  `application.properties` pending this implementation.
- `LogInterceptor` currently enriches MDC only; this specification extends it to also record metrics.
- The OpenTelemetry Collector may or may not be present; Prometheus scrape is the default export strategy.

---

## 2. Definitions

| Term | Definition |
|---|---|
| **MDC** | Mapped Diagnostic Context — thread-local key/value store used by JBoss Logging to enrich structured log records. |
| **MeterRegistry** | Micrometer central registry that manages all meters and routes measurements to one or more backends. |
| **Timer** | Micrometer meter that records both the count and cumulative duration of events. Supports histograms for percentile calculation. |
| **Counter** | Micrometer meter that records a monotonically increasing value. Suitable for counting occurrences. |
| **Tag** | A key/value pair attached to a meter that adds a dimension for filtering and grouping in dashboards. Synonymous with Prometheus *label*. |
| **Cardinality** | The number of unique tag-value combinations for a given metric name. High cardinality (e.g., per-userId) saturates time-series databases. |
| **Histogram** | Statistical data structure that pre-aggregates observed values into configurable buckets, enabling percentile calculation in Prometheus (`histogram_quantile`). |
| **OTLP** | OpenTelemetry Protocol — the wire format used by the OpenTelemetry Collector to receive and export all telemetry signals (logs, traces, metrics). |
| **`@Logged`** | CDI interceptor binding annotation in this library that activates `LogInterceptor` for a class or method. |
| **`LogInterceptor`** | CDI interceptor (`@Priority(APPLICATION)`) that currently enriches MDC; will be extended to record metrics. |
| **`LogSistematico`** | Fluent DSL entry point for structured log emission following the 5W1H framework. |
| **`LogEvento`** | Immutable Java record representing one structured log event, carrying What/Where/Why/How dimensions. |
| **`GerenciadorContextoLog`** | `@ApplicationScoped` bean managing the MDC lifecycle for request-scoped fields (`userId`, `applicationName`). |
| **5W1H** | Observability framework: What, Who, When, Where, Why, How — maps to `LogEvento` fields. |
| **`SanitizadorDados`** | Utility class that masks credential and personal-data fields before logging. |
| **Percentile Histogram** | A Timer configured with `publishPercentileHistogram()` that emits bucket counters for server-side percentile computation (p50, p95, p99). |
| **Scrape** | Prometheus pull model: the Prometheus server periodically requests `/q/metrics` from each application instance. |
| **`CompositeMeterRegistry`** | Micrometer registry that replicates each measurement to all registered sub-registries (e.g., Prometheus + OTLP simultaneously). |

---

## 3. Requirements, Constraints & Guidelines

### Functional Requirements

- **REQ-001**: `LogInterceptor` MUST record a `Timer` named `metodo.execucao` for every method
  invocation intercepted by `@Logged`, regardless of whether the method succeeds or throws.
- **REQ-002**: `LogInterceptor` MUST increment a `Counter` named `metodo.falha` when an intercepted
  method throws any `Throwable`.
- **REQ-003**: The `metodo.execucao` Timer MUST include a `publishPercentileHistogram()` configuration
  so that Prometheus can compute percentiles (p50, p95, p99) server-side across multiple instances.
- **REQ-004**: Both `metodo.execucao` and `metodo.falha` MUST carry the tags `classe` (simple class
  name) and `metodo` (method name). `metodo.falha` MUST additionally carry the tag `excecao`
  (simple exception class name).
- **REQ-005**: The `MeterRegistry` MUST be injected into `LogInterceptor` via constructor injection.
- **REQ-006**: Application developers MUST be able to inject `MeterRegistry` into their own `@ApplicationScoped`
  beans to register custom business metrics without additional configuration.

### Security Requirements

- **SEC-001**: Tag values MUST NOT include user identifiers (`userId`), request identifiers
  (`requestId`, `traceId`), or any entity identifier (e.g., `pedidoId`, `orderId`) that causes
  high-cardinality tag sets. These values belong in logs and traces, not metric tags.
- **SEC-002**: Exception class names used in the `excecao` tag MUST be the simple class name only
  (e.g., `GatewayException`), never the full message or stack-trace fragment, to prevent
  accidental leakage of sensitive runtime data into metric labels.

### Constraints

- **CON-001**: Metrics infrastructure MUST NOT interrupt business logic. Any `Exception` thrown by
  `MeterRegistry` operations (counter increment, timer stop, registration) MUST be caught locally
  within `LogInterceptor` and logged at `WARN` level. The original method result or exception
  MUST propagate normally.
- **CON-002**: `metodo.execucao` timer MUST be started before `InvocationContext.proceed()` and
  stopped in the `finally` block, ensuring duration is measured even when the method throws.
- **CON-003**: Metrics MUST remain disabled by default via `quarkus.micrometer.enabled=false`
  until this module is fully implemented and tested. Enabling metrics MUST be a configuration-only
  change, not a code change.
- **CON-004**: Tag values MUST be drawn only from fixed, closed sets of values (e.g., class names,
  method names, exception type names). Dynamic runtime values with open-ended cardinality are
  prohibited as tags.
- **CON-005**: `LogInterceptor` MUST remain a single-responsibility interceptor focused on
  observability context. Business logic MUST NOT be added to it.
- **CON-006**: The metrics implementation MUST NOT introduce a compile-time or runtime dependency
  on any specific Prometheus client beyond what `quarkus-micrometer-registry-prometheus` already
  provides.

### Guidelines

- **GUD-001**: Prefer constructor injection over field injection for `MeterRegistry` in all beans
  to support testability.
- **GUD-002**: Register `Timer` and `Counter` instances once at bean initialization
  (`@PostConstruct` or constructor) when the tags are known at construction time, rather than
  calling `meterRegistry.timer(...)` on every invocation. For `LogInterceptor`, dynamic tags
  (class/method name) require per-invocation registration but Micrometer caches meter instances
  by name + tags.
- **GUD-003**: Use `Timer.Sample` (via `Timer.start(meterRegistry)`) for measuring durations
  that span multiple code blocks or that need to capture different tags based on the outcome.
- **GUD-004**: When adding custom business metrics, always include a `description()` on the
  meter builder for Prometheus `HELP` comment generation.
- **GUD-005**: Prefer `publishPercentileHistogram()` over `publishPercentiles(double...)` for
  Timers exposed to Prometheus. Pre-computed percentiles cannot be reaggregated across instances;
  histogram buckets can.
- **GUD-006**: Use dot-separated lowercase metric names following Micrometer convention (e.g.,
  `metodo.execucao`, `pedido.criado`). Micrometer normalizes these to underscore-separated names
  for Prometheus (`metodo_execucao_seconds_count`).

### Patterns

- **PAT-001**: Wrap metric recording in a try/catch that logs at WARN on failure, as shown in the
  reference implementation in Section 9. This is the project's established fault-isolation pattern.
- **PAT-002**: Log events and metrics for the same method invocation MUST use the same `classe` and
  `metodo` values, enabling cross-referencing between Grafana panels and log search results.
- **PAT-003**: Use `@Timed` and `@Counted` CDI interceptor annotations from `quarkus-micrometer`
  only for application-layer beans that are not already covered by `@Logged`. Do not double-instrument
  a method with both `@Logged` (which emits `metodo.execucao`) and `@Timed`.

---

## 4. Interfaces & Data Contracts

### 4.1 Metrics Emitted by `LogInterceptor`

| Metric name | Micrometer type | Tags | Description |
|---|---|---|---|
| `metodo.execucao` | `Timer` (with histogram) | `classe`, `metodo` | Duration and invocation count per intercepted method. |
| `metodo.falha` | `Counter` | `classe`, `metodo`, `excecao` | Failure count per intercepted method per exception type. |

**Prometheus wire names** (after Micrometer normalization):

```
metodo_execucao_seconds_count{classe, metodo}
metodo_execucao_seconds_sum{classe, metodo}
metodo_execucao_seconds_max{classe, metodo}
metodo_execucao_seconds_bucket{classe, metodo, le}   # with publishPercentileHistogram
metodo_falha_total{classe, metodo, excecao}
```

### 4.2 Tag Value Contracts

| Tag key | Source | Example values | Cardinality |
|---|---|---|---|
| `classe` | `InvocationContext.getTarget().getClass().getSimpleName()` | `"PedidoService"`, `"PagamentoService"` | Low — one per service class |
| `metodo` | `InvocationContext.getMethod().getName()` | `"criar"`, `"processar"` | Low — one per method name |
| `excecao` | `e.getClass().getSimpleName()` | `"GatewayException"`, `"ConstraintViolationException"` | Low — one per exception type |

### 4.3 Updated `LogInterceptor` Signature

```java
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LogInterceptor {

    private final GerenciadorContextoLog gerenciador;
    private final MeterRegistry meterRegistry;

    public LogInterceptor(GerenciadorContextoLog gerenciador, Instance<MeterRegistry> meterRegistryInstance) {
        this.gerenciador = gerenciador;
      this.meterRegistry = meterRegistryInstance.isResolvable() ? meterRegistryInstance.get() : null;
    }

    @AroundInvoke
    public Object interceptar(InvocationContext contexto) throws Exception {
        gerenciador.enriquecer(contexto);
      var sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        try {
            return contexto.proceed();
      } catch (Throwable e) {
            registrarFalha(contexto, e);
        if (e instanceof Exception ex) throw ex;
        if (e instanceof Error err) throw err;
        throw new RuntimeException(e);
        } finally {
            pararTimer(contexto, sample);
            gerenciador.limparEnriquecimento();
        }
    }

    // registrarFalha and pararTimer wrap meterRegistry calls
    // in try/catch per CON-001 and PAT-001
}
```

  When Micrometer is disabled at build/runtime profile level, `LogInterceptor` MUST keep operating with MDC enrichment and skip metric emission without failing dependency injection.

### 4.4 Configuration Properties

```properties
# Enable Micrometer (set to true to activate metrics)
quarkus.micrometer.enabled=false

# Prometheus endpoint
quarkus.micrometer.export.prometheus.enabled=false
quarkus.micrometer.export.prometheus.path=/q/metrics

# JVM and HTTP binders — disable until performance impact is assessed
quarkus.micrometer.binder.jvm=false
quarkus.micrometer.binder.system=false
quarkus.micrometer.binder.http-server.enabled=false

# OTLP alternative (mutually exclusive approach with Prometheus)
# quarkus.otel.metrics.enabled=true
# quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317
```

When metrics are enabled, all four properties MUST be set consistently:
`quarkus.micrometer.enabled=true` AND `quarkus.micrometer.export.prometheus.enabled=true`.

### 4.5 PromQL Reference Queries

```promql
# Method error rate (PedidoService.criar, last 5 minutes)
rate(metodo_falha_total{classe="PedidoService", metodo="criar"}[5m])
/
rate(metodo_execucao_seconds_count{classe="PedidoService", metodo="criar"}[5m])

# p99 latency — aggregated across all instances
histogram_quantile(0.99,
    sum by (le) (
        rate(metodo_execucao_seconds_bucket{classe="PedidoService", metodo="criar"}[5m])
    )
)

# Top 5 slowest methods by p95 latency
topk(5,
    histogram_quantile(0.95,
        sum by (classe, metodo, le) (
            rate(metodo_execucao_seconds_bucket[5m])
        )
    )
)
```

---

## 5. Acceptance Criteria

- **AC-001**: Given a `@Logged` bean method is invoked successfully, when the method completes,
  then `metodo_execucao_seconds_count` for the corresponding `{classe, metodo}` tag set MUST
  be incremented by 1.
- **AC-002**: Given a `@Logged` bean method throws any `Throwable`, when the exception propagates,
  then `metodo_falha_total` for the corresponding `{classe, metodo, excecao}` tag set MUST be
  incremented by 1 AND `metodo_execucao_seconds_count` MUST also be incremented by 1 (failure
  counts toward total invocations).
- **AC-003**: Given `quarkus.micrometer.enabled=false`, when the application starts, then no
  Prometheus endpoint is exposed and `LogInterceptor` MUST continue to function correctly for
  MDC enrichment without throwing a `NullPointerException` or `UnsatisfiedResolutionException`.
- **AC-004**: Given the `MeterRegistry` backend throws a runtime exception during `counter.increment()`,
  when the interceptor catches it, then the original method exception MUST still propagate to the
  caller and a `WARN` log MUST be emitted containing the metric failure message.
- **AC-005**: Given a custom business metric is registered via injected `MeterRegistry` in an
  `@ApplicationScoped` bean, when `GET /q/metrics` is scraped by Prometheus, then the custom
  metric MUST appear in the response with the registered name and tags.
- **AC-006**: Given `publishPercentileHistogram()` is enabled on `metodo.execucao`, when
  `histogram_quantile(0.99, ...)` is evaluated in Prometheus, then a numeric latency value in
  seconds MUST be returned (not `NaN`).
- **AC-007**: The tag `excecao` MUST contain only the simple class name (e.g., `GatewayException`).
  It MUST NOT contain the exception message, full qualified name, or any substring of the stack trace.
- **AC-008**: Given `@Logged` is applied to a class, when any of its public methods are invoked,
  then each method invocation MUST produce independent metric entries with the correct `metodo` tag.

---

## 6. Test Automation Strategy

- **Test Levels**:
  - **Unit**: Test `LogInterceptor` in isolation using a `SimpleMeterRegistry` (in-memory backend
    provided by Micrometer). Verify counter increments and timer samples without starting Quarkus.
  - **Integration**: Use `@QuarkusTest` with `@TestHTTPEndpoint` to invoke a test resource
    annotated with `@Logged` and assert `/q/metrics` contents via `RestAssured`.
  - **Contract**: Verify that disabling Micrometer (`quarkus.micrometer.enabled=false`) does not
    break `@Logged` — use a separate test profile with metrics disabled.

- **Frameworks**:
  - JUnit 5 (`@QuarkusTest`, `@Test`)
  - `io.micrometer.core.instrument.simple.SimpleMeterRegistry` for unit tests
  - RestAssured for integration assertions on `/q/metrics`
  - AssertJ for fluent assertions

- **Test Data Management**: Use fixed, deterministic service class/method names in tests to
  avoid tag-set pollution. Create isolated `SimpleMeterRegistry` instances per test to prevent
  counter bleed-across.

- **CI/CD Integration**: All tests run via `mvn -B clean install` in `.github/workflows/build.yml`.
  Metrics tests MUST pass with both `quarkus.micrometer.enabled=true` and `false` test profiles.

- **Coverage Requirements**: All branches of the `try/catch` fault-isolation logic in
  `LogInterceptor` (success path, failure path, metric-failure path) MUST be covered by unit tests.

- **Performance Testing**: Measure overhead of `Timer.start()` + `Timer.stop()` on the intercepted
  path in a microbenchmark (JMH optional). Acceptable overhead: less than 5% throughput reduction
  on a hot method compared to a non-intercepted equivalent.

---

## 7. Rationale & Context

### Why Micrometer (not raw OpenTelemetry Metrics API)

Quarkus's recommended metrics abstraction is Micrometer. It provides a stable API backed by
`quarkus-micrometer-registry-prometheus` for Prometheus and `quarkus-micrometer-opentelemetry`
for OTLP, switchable by dependency change alone. The OTel Metrics API is lower-level and does
not integrate as cleanly with Quarkus CDI lifecycle and configuration.

### Why Timer + Counter (not single Timer)

A `Timer` tracks invocations and durations for all outcomes. A separate `Counter` for failures
enables independent alerting on failure rates using `rate(metodo_falha_total[5m])` without
post-processing. This pattern makes error-rate dashboards and SLO monitoring straightforward.

### Why `publishPercentileHistogram()` not `publishPercentiles()`

Pre-computed percentiles are not reaggregable across multiple application instances. A cluster
of three pods each reporting independently-computed p99 values cannot be merged into a single
p99. Histogram buckets can: `histogram_quantile(0.99, sum by (le) (...))` computes the true
aggregate p99 across all instances. This is the recommended pattern for microservice deployments.

### Why Metrics Are Deferred

The current `LogInterceptor` is intentionally minimal to isolate concerns and reduce risk during
the library's initial rollout. Metrics require careful tag design to avoid cardinality explosions
and a stable performance baseline before introducing per-invocation timing overhead in production.

### Why Fault Isolation (CON-001) Is Mandatory

The `MeterRegistry` export pipeline involves network I/O (OTLP) or in-memory accumulation with
Prometheus. Either can fail transiently. A monitoring infrastructure failure MUST NOT degrade
or fail business transactions — this is a fundamental observability principle: observability
tooling is a side channel, never in the critical path.

---

## 8. Dependencies & External Integrations

### External Systems

- **EXT-001**: Prometheus — pull-based metric collector. Scrapes `/q/metrics` every 15-60 seconds.
  Requires the application port to be reachable from the Prometheus server. No push required.
- **EXT-002**: OpenTelemetry Collector (optional) — receives metrics via OTLP push when
  `quarkus-micrometer-opentelemetry` is substituted for `quarkus-micrometer-registry-prometheus`.

### Third-Party Services

- **SVC-001**: Grafana — visualization layer. Connects to Prometheus as data source. No direct
  application dependency; operates entirely post-scrape.

### Infrastructure Dependencies

- **INF-001**: HTTP port accessibility — the application's HTTP port (`quarkus.http.port`, default 8080)
  MUST be reachable from the Prometheus server for scrape to succeed. In Kubernetes, a `ServiceMonitor`
  or static scrape configuration is required.
- **INF-002**: `containers-dev/docker-compose.yml` — the local observability stack includes
  Prometheus and Grafana. Metrics endpoint must be whitelisted in `prometheus.yml` scrape config.

### Technology Platform Dependencies

- **PLT-001**: Java 21 (compiled to target 25) — required for `sealed interface`, pattern matching
  switch, and records used throughout the library.
- **PLT-002**: Quarkus 3.32.3 — provides CDI, JBoss Logging, `quarkus-micrometer-registry-prometheus`,
  and `quarkus-arc` (interceptor runtime).
- **PLT-003**: Micrometer core (transitive via `quarkus-micrometer-registry-prometheus`) — provides
  `MeterRegistry`, `Timer`, `Counter`, `Timer.Sample`.

### Data Dependencies

- **DAT-001**: `quarkus.application.name` — application name property injected into `GerenciadorContextoLog`
  and used for log attribution. Should also be used as a global tag on all metrics for multi-service
  dashboards (via `quarkus.micrometer.tags.application=${quarkus.application.name}`).

### Compliance Dependencies

- **COM-001**: LGPD / GDPR — tag values MUST NOT contain personally identifiable information (PII).
  This aligns with SEC-001 and the existing `SanitizadorDados` masking strategy for logs.

---

## 9. Examples & Edge Cases

### Successful method invocation — metrics emitted

```java
// Application service annotated with @Logged
@ApplicationScoped
@Logged
public class PedidoService {
    public Pedido criar(NovoPedidoRequest request) {
        // business logic
        LogSistematico
            .registrando("Pedido criado")
            .em(PedidoService.class, "criar")
            .porque("Solicitação do cliente via checkout")
            .comDetalhe("eventType", "ORDER_CREATED")
            .comDetalhe("pedidoId",  pedido.getId())
            .info();
        return pedido;
    }
}

// After invocation, Prometheus exposes:
// metodo_execucao_seconds_count{classe="PedidoService", metodo="criar"} 1.0
// metodo_execucao_seconds_sum{classe="PedidoService", metodo="criar"} 0.042
```

### Method throws — both timer and counter increment

```java
// GatewayException propagates from PagamentoService.processar
// After invocation:
// metodo_execucao_seconds_count{classe="PagamentoService", metodo="processar"} 1.0
// metodo_falha_total{classe="PagamentoService", metodo="processar", excecao="GatewayException"} 1.0
```

### Fault-isolation pattern — metric failure does not break the method

```java
@AroundInvoke
public Object interceptar(InvocationContext contexto) throws Exception {
    gerenciador.enriquecer(contexto);
    var sample = Timer.start(meterRegistry);
    try {
        return contexto.proceed();
    } catch (Exception e) {
        try {
            meterRegistry.counter(
                "metodo.falha",
                "classe",  contexto.getTarget().getClass().getSimpleName(),
                "metodo",  contexto.getMethod().getName(),
                "excecao", e.getClass().getSimpleName()
            ).increment();
        } catch (Exception metricaFalhou) {
            // CON-001: metric failure is observable but never propagated
            log.warnf("Falha ao registrar metrica de falha: %s", metricaFalhou.getMessage());
        }
        throw e;  // original exception propagates normally
    } finally {
        try {
            sample.stop(Timer.builder("metodo.execucao")
                .tag("classe", contexto.getTarget().getClass().getSimpleName())
                .tag("metodo", contexto.getMethod().getName())
                .publishPercentileHistogram()
                .register(meterRegistry));
        } catch (Exception metricaFalhou) {
            log.warnf("Falha ao registrar metrica de execucao: %s", metricaFalhou.getMessage());
        }
        gerenciador.limparEnriquecimento();
    }
}
```

### Custom business metric in an application service

```java
@ApplicationScoped
public class PagamentoService {

    private final MeterRegistry meterRegistry;

    public PagamentoService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void processar(Pagamento pagamento) {
        // business logic ...
        meterRegistry.counter("pagamento.processado",
            "gateway", pagamento.gateway(),   // "cielo", "stone", "pagseguro"
            "resultado", "aprovado"            // "aprovado", "recusado"
        ).increment();
    }
}
```

### Edge case — `quarkus.micrometer.enabled=false` (metrics disabled)

When `MeterRegistry` is disabled, Quarkus injects a `NoopMeterRegistry`. All `Timer.start()`,
`counter().increment()`, and `sample.stop()` calls are no-ops with zero overhead. `LogInterceptor`
MUST function correctly in this mode; tests MUST verify that MDC enrichment still occurs and
no `NullPointerException` is thrown.

### Edge case — anonymous class or lambda proxy

When `contexto.getTarget().getClass()` returns a CDI proxy class (e.g.,
`PedidoService$Subclass$...`), the simple name may include CDI suffixes. The implementation
MUST call `getClass().getSuperclass().getSimpleName()` or use the declaring class from the
`Method` object to obtain the clean class name.

---

## 10. Validation Criteria

The implementation is compliant with this specification when ALL of the following are true:

1. Running `mvn -B clean install` with a test profile that enables Micrometer produces zero
   test failures and zero compilation errors.
2. `GET /q/metrics` on a running instance with one intercepted method invoked returns a response
   body containing both `metodo_execucao_seconds_count` and `metodo_falha_total` for the invoked
   method.
3. `GET /q/metrics` on a running instance with `quarkus.micrometer.enabled=false` returns HTTP 404
   or an empty response, and all `@Logged`-annotated methods continue to execute and emit MDC-enriched
   log records normally.
4. A unit test using `SimpleMeterRegistry` verifies that after one failing method invocation,
   `registry.counter("metodo.falha", ...).count()` equals `1.0` and the Timer counter also equals `1.0`.
5. A unit test verifies that when `MeterRegistry.counter(...)` throws `IllegalArgumentException`,
   the exception does not propagate out of `LogInterceptor.interceptar()` and the original invocation
   result is returned or the original exception re-thrown correctly.
6. Static analysis (`mvn spotbugs:check` or equivalent) reports zero high-severity findings in
   the modified `LogInterceptor`.
7. All tag values in emitted metrics contain only alphanumeric, dot, hyphen, and underscore
   characters — no spaces, no JSON fragments, no user data.

---

## 11. Related Specifications / Further Reading

- [concepts/METRICS.md](../concepts/METRICS.md) — full conceptual guide: metric types, cardinality rules, Micrometer integration patterns, and PromQL examples.
- [concepts/5W1H.md](../concepts/5W1H.md) — 5W1H framework defining `LogEvento` field semantics.
- [concepts/FIELD_NAMES.md](../concepts/FIELD_NAMES.md) — canonical field naming conventions (camelCase mandate).
- [concepts/CODING_STANDARDS.md](../concepts/CODING_STANDARDS.md) — coding standards including the fault-isolation rule for metric failures.
- [lib-logging-quarkus/AGENTS.md](../lib-logging-quarkus/AGENTS.md) — module agent checklist and cross-file contracts.
- [Quarkus — Micrometer Metrics Guide](https://quarkus.io/guides/telemetry-micrometer)
- [Quarkus — Micrometer and OpenTelemetry](https://quarkus.io/guides/telemetry-micrometer-to-opentelemetry)
- [Micrometer — Timer Concepts](https://docs.micrometer.io/micrometer/reference/concepts/timers)
- [Micrometer — Counters](https://docs.micrometer.io/micrometer/reference/concepts/counters)
- [Micrometer — Histograms and Percentiles](https://docs.micrometer.io/micrometer/reference/concepts/histogram-quantiles)
