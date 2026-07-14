# AGENTS.md

## Project map (read this first)
- Root `pom.xml` is an aggregator (`packaging=pom`) with one module: `lib-logging-quarkus`.
- Main code lives in `lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability`.
- Local telemetry stack lives in `infra/` (`docker-compose.yml`, collector/prometheus/grafana provisioning).
- Concept contracts are in `concepts/` (`5W1H.md`, `FIELD_NAMES.md`, `METRICS.md`, `DISTRIBUTED_TRACING.md`).

## Big-picture runtime flow
- HTTP request enters `filtro/LogContextoFiltro` (sets `userId`, `applicationName`, syncs `traceId`/`spanId`; clears MDC on response).
- `@Rastreado` (`interceptor/TracingInterceptor`) runs at `APPLICATION - 10`, creates child span first.
- `@Logged` (`interceptor/LogInterceptor`) enriches MDC, runs method, records optional Micrometer metrics, then cleans enrichment keys.
- Business logs should use DSL `dsl/LOG.java` (`registrando(...).em(...).info()/erro(...)`) not ad-hoc string logs.
- Enrichment extension points are CDI-discovered chains: `context/enriquecedor/EnriquecedorContexto` and `tracing/enriquecedor/EnriquecedorTracing` sorted by `prioridade()`.

## Developer workflows (tested paths in docs)
- Dev app loop (Windows):
  - `Set-Location D:\develop\repos\java_repos\studio-observability\lib-logging-quarkus`
  - `.\mvnw.cmd quarkus:dev`
- Run tests:
  - `.\mvnw.cmd test`
- Build JVM package:
  - `.\mvnw.cmd package`
- Native build (containerized):
  - `.\mvnw.cmd package -Dnative -Dquarkus.native.container-build=true`
- Observability stack (from `infra/`):
  - `docker-compose up -d` / `docker-compose down`
  - or `infra\start-observability.ps1` and `infra\stop-observability.ps1`
- Debug mode: `.\mvnw.cmd quarkus:dev -Ddebug=5005` (documented in `infra/QUICKSTART.md`).

## Project-specific conventions (do not ignore)
- DSL is Portuguese-first and compile-time guided via sealed interfaces (`dsl/LogEtapas.java`, `dsl/LOG.java`).
- Canonical MDC/tag keys come from `CamposMdc` enum; do not hardcode MDC key strings.
- Extra fields must go through `.comDetalhe("key", value)`; runtime prefixes keys as `detalhe_`.
- Sensitive values are masked by key name in `security/SanitizadorDados` (credentials -> `****`, personal data -> `[PROTEGIDO]`).
- Metrics in `LogInterceptor` are app-prefixed (`<application>.metodo.execucao`, `<application>.metodo.falha`) using `quarkus.application.name`.
- Micrometer is off by default in `application.properties`; tests enable it with `MetricsEnabledTestProfile`.

## Integration boundaries
- Quarkus extensions in `lib-logging-quarkus/pom.xml`: OTel, JSON logging, JWT, context propagation, Micrometer/Prometheus.
- App exports OTLP to collector (`%dev.quarkus.otel.exporter.otlp.endpoint=http://localhost:4317`; prod points to `otel-collector:4317`).
- Prometheus scrapes app `/q/metrics` via `infra/observability/prometheus/prometheus.yml`.
- Collector pipelines (`infra/observability/otel/otel-collector-config.yaml`) route telemetry to Elasticsearch, Prometheus exporter, and Graylog GELF.

## Known repo/doc drift (use real paths)
- Some docs/scripts still say `logging-quarkus` or `containers-dev`; actual module is `lib-logging-quarkus` and infra dir is `infra`.
- Prefer commands/paths from this file when they conflict with older docs.


# GitHub Copilot Instructions

## Priority Guidelines

When generating code for this repository:

1. Version compatibility first: use only language and framework features compatible with the versions declared in Maven build files.
2. Existing patterns over external preferences: mirror patterns already present in this codebase.
3. Architectural consistency: preserve module boundaries and interceptor/filter responsibilities.
4. Security and observability by default: keep MDC, tracing, and sanitization behavior intact.
5. Testability: keep code easy to test using the existing JUnit 5 style and conventions.

## Technology Version Detection

Before generating code, confirm versions from project files:

- Root aggregator: pom.xml
- Library module: lib-logging-quarkus/pom.xml
- Example module: examples/logging-quarkus-example/pom.xml

Detected baseline (must be respected):

- Java: 21 (maven.compiler.release=21)
- Quarkus BOM: 3.32.3
- Maven compiler plugin: 3.15.0
- Surefire/Failsafe plugin: 3.5.4
- Main logging/runtime stack:
  - io.quarkus:quarkus-opentelemetry
  - io.quarkus:quarkus-logging-json
  - io.quarkus:quarkus-smallrye-jwt
  - io.quarkus:quarkus-smallrye-context-propagation
  - io.quarkus:quarkus-arc
  - io.quarkus:quarkus-micrometer-registry-prometheus
  - io.quarkus:quarkus-rest

Important:

- Do not use Java features beyond 21, even if prose docs mention newer versions.
- Treat pom.xml values as source of truth for code generation.

## Repository Structure and Boundaries

This repository is a Maven multi-module project:

- lib-logging-quarkus: core library
- examples/logging-quarkus-example: consumer/sample app
- infra: local observability stack and scripts
- concepts and docs: architecture and operational guidance

Boundary rules:

- Keep generic observability logic in lib-logging-quarkus.
- Do not move example-specific dependencies into the library module.
- Do not introduce coupling from core library to example package namespaces.

## Codebase Pattern Rules

### Java style and language features

Use Java 21 patterns already present:

- Records for immutable data carriers (for example in GerenciadorContextoLog and GerenciadorTracing).
- Sealed interfaces for compile-time workflow enforcement (DSL stages in LogEtapas).
- Pattern matching switch where applicable and already used.
- var for local inference when it keeps code readable.

Do not introduce language constructs not visible in the current codebase style.

### Dependency injection and Quarkus patterns

Follow CDI and Quarkus patterns used in main code:

- Constructor injection for beans and interceptors.
- @ApplicationScoped for manager/enricher beans.
- @Interceptor with explicit @Priority for interceptor ordering.
- @Provider for JAX-RS filters.
- Keep business flow in try/catch/finally with explicit cleanup semantics.

### Logging DSL usage

Prefer DSL entry point over ad-hoc logs:

- Use Log.registrando(...).em(...)/aqui() as the standard path.
- Preserve required What + Where sequence through existing sealed-stage API.
- Use comDetalhe for extra fields, not MDC string literals scattered in feature code.

### MDC and tracing discipline

Respect MDC ownership and lifecycle:

- Use CamposMdc constants for canonical keys.
- Avoid hardcoded MDC key strings.
- Keep traceId/spanId synchronization behavior intact.
- Preserve strict cleanup in finally blocks.

Only components explicitly designed for direct MDC management should write/read MDC directly.

### Security and data protection

Preserve sanitization strategy:

- Route sensitive detail values through SanitizadorDados.
- Keep key-based masking behavior for credentials and personal data.
- Do not bypass or weaken masking rules when adding new detail fields.

### Metrics

When touching interceptor metrics:

- Keep metric names prefixed with quarkus.application.name.
- Maintain low-cardinality tags (class/method/exception style).
- Keep metrics failures isolated from business execution (warn/log, never break flow).

## Naming and Organization Conventions

Follow existing conventions:

- Package root: br.com.vsjr.labs.observability for library code.
- Portuguese-first domain naming in DSL and internal terminology is intentional.
- Test classes use descriptive method names with underscores and behavioral intent.
- Keep helper and utility classes close to their concern domains (context, tracing, security, dsl, interceptor, filtro).

## Error Handling Conventions

Mirror current approach:

- Preserve original Exception/Error semantics when rethrowing.
- Avoid swallowing exceptions silently.
- In cleanup/finalization paths, isolate secondary infrastructure failures from primary business failures.

## Testing Conventions

Current testing style is JUnit 5 with focused unit-style tests.

Guidelines:

- Use org.junit.jupiter.api.Test.
- Prefer org.junit.jupiter.api.Assertions static methods.
- Keep tests deterministic and isolated.
- Use fake/proxy test doubles where already established instead of introducing unnecessary frameworks.
- Preserve architecture-governance tests that enforce repository constraints.

When adding tests:

- Follow naming style of existing tests in lib-logging-quarkus/src/test/java.
- Keep assertions explicit and behavior-oriented.

## Configuration Conventions

Follow module intent:

- Library module should stay policy-light in main resources (no hard operational defaults).
- Application-level operational settings belong to consuming apps (example module or downstream services).
- For tests, maintain current defaults that avoid external collector dependency unless explicitly required.

## Documentation and Drift Handling

When code and prose documentation diverge:

- Prioritize executable configuration and tests (pom.xml, source code, test resources).
- Keep generated code aligned with build truth, not narrative text.

## Do and Do Not

Do:

- Reuse existing enums/contracts (Event, Entrypoint, CamposMdc).
- Keep interceptor ordering semantics intact.
- Keep filter/interceptor responsibilities separated.
- Preserve compatibility with Quarkus 3.32.3 + Java 21.

Do not:

- Introduce new frameworks or styles not already used.
- Hardcode MDC keys as string literals.
- Add high-cardinality metric tags.
- Place example-only dependencies into the core library module.
- Use language features beyond Java 21.

## Quick File Anchors for Pattern Discovery

Use these as first references before generating code:

- Core build/version truth:
  - pom.xml
  - lib-logging-quarkus/pom.xml
  - examples/logging-quarkus-example/pom.xml
- Logging DSL and stage contracts:
  - lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability/dsl/LOG.java
  - lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability/dsl/LogEtapas.java
- Interceptors and filter lifecycle:
  - lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability/interceptor/LogInterceptor.java
  - lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability/interceptor/TracingInterceptor.java
  - lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability/filtro/LogContextoFiltro.java
- MDC keys and sanitization:
  - lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability/CamposMdc.java
  - lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability/security/SanitizadorDados.java
- Testing patterns:
  - lib-logging-quarkus/src/test/java/br/com/vsjr/labs/observability/LogDslTest.java
  - lib-logging-quarkus/src/test/java/br/com/vsjr/labs/observability/ApiPublicaContratoTest.java
  - lib-logging-quarkus/src/test/java/br/com/vsjr/labs/observability/ArquiteturaGovernancaTest.java
  - lib-logging-quarkus/src/test/java/br/com/vsjr/labs/observability/interceptor/LogInterceptorMetricsTest.java
