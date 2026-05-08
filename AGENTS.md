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
