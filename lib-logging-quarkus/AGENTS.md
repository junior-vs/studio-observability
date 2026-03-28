# AGENTS.md

## Scope and intent
- This module is a Quarkus library/app that standardizes structured logging + tracing + metrics for HTTP flows.
- Primary domain lives in `src/main/java/br/com/vsjr/labs/observability`; `src/main/java/br/com/vsjr/labs/example` is usage/demo code.
- Language and identifiers are mostly Portuguese (`Rastreado`, `GerenciadorContextoLog`, `motivo`, `canal`); keep naming consistent when extending.

## Architecture map (read these first)
- `annotations/` (`@Logged`, `@Rastreado`) define CDI interceptor bindings.
- `interceptor/LogInterceptor` adds technical location to MDC. Metrics are intentionally out of scope for now.
- `interceptor/RastreamentoInterceptor` creates child spans and runs before `LogInterceptor` (`APPLICATION - 10`).
- `filtro/LogContextoFiltro` initializes request MDC (`traceId`, `spanId`, `userId`, `applicationName`) and clears MDC on response.
- `dsl/LogSistematico` + `dsl/LogEtapas` implement the fluent 5W1H logging API with compile-time ordering via sealed interfaces.
- `tracing/GerenciadorRastreamento` manages span lifecycle and executes `EnriquecedorSpan` chain by priority.

## Runtime flow
- HTTP request enters `LogContextoFiltro.filter(request)` -> MDC context is initialized from active OTel span and security identity.
- Business methods annotated with `@Rastreado` create child span; same methods with `@Logged` enrich MDC with technical location.
- `LogSistematico` emits JSON-compatible logs through JBoss Logging and temporary MDC fields (`log_*`, `detalhe_*`).
- Response filter calls `GerenciadorContextoLog.limpar()` to prevent MDC leakage across Vert.x threads.

## Project-specific coding rules
- Use `LogSistematico` instead of ad-hoc loggers for business events. Minimum valid sequence: `registrando(...).em(...).info()/erro(...)`.
- `comDetalhe(chave, valor)` is automatically sanitized by `SanitizadorDados`; do not bypass this for sensitive fields.
- MDC ownership is split by design:
  - request correlation keys are owned by `LogContextoFiltro`/`GerenciadorContextoLog`;
  - per-event keys are owned/cleaned by `LogSistematico` and `LogInterceptor`.
- When adding tracing attributes, implement `EnriquecedorSpan` (`@ApplicationScoped`) and choose priority:
  - infra enrichers: `10-50` (see `EnriquecedorMetadados`, `EnriquecedorIdentidade`)
  - business enrichers: `100+` (see `exemple/tracing/EnriquecedorOperacao`).
- Keep span names in `Classe.metodo` format (created in `RastreamentoInterceptor`).

## Build, test, and run
- Dev mode (Windows): `mvnw.cmd quarkus:dev`
- Package JAR: `mvnw.cmd package`
- Run tests: `mvnw.cmd test`
- Native profile toggles in `pom.xml` (`-Dnative` activates `skipITs=false`, native build settings).
- Observability config is in `src/main/resources/application.properties` (OTLP endpoint `http://localhost:4317`).
- JSON logging is enabled for `dev` and `prod` profiles (console and file according to profile setup).
- Metrics are planned for a future module/release and remain disabled in current configuration.

## Testing patterns in this repo
- Unit tests are JUnit 5 with direct JBoss LogManager capture (`src/test/java/br/com/vsjr/labs/observability/dsl/LogSistematicoTest.java`).
- For logging assertions, attach temporary handlers and always restore logger state in `finally`.

## Safe extension points
- New logging behaviors: extend DSL or interceptors without changing mandatory call order contract in `LogEtapas`.
- New HTTP context fields: add in `GerenciadorContextoLog` and ensure cleanup path still runs in response filter.
- New trace metadata: add an `EnriquecedorSpan` rather than modifying `GerenciadorRastreamento` directly.

