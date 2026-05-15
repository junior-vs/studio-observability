# lib-logging-quarkus

[![Build](https://img.shields.io/github/actions/workflow/status/vsjrlabs/studio-observability/build.yml?style=flat-square)](../../actions)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk)](https://openjdk.org)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.32-4695EB?style=flat-square&logo=quarkus)](https://quarkus.io)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](LICENSE)

> Structured observability for Quarkus: a compile-time-safe logging DSL, CDI interceptors for distributed tracing, automatic PII masking, and extensible MDC enrichment pipelines.

The library enforces a structured **5W1H** logging contract at compile time using Java 21 sealed interfaces — incomplete log statements are compile errors, not silent runtime gaps. It integrates transparently with OpenTelemetry, Micrometer, and JSON logging via standard Quarkus extensions.

## Features

- **Compile-time-safe DSL** — `Log.registrando(Event).em()|aqui().[porque().[como(Entrypoint).[comDetalhe()]*]].info()|debug()|warn()|erro()` guided by sealed interfaces
- **CDI interceptors** — `@Logged` for MDC enrichment and optional Micrometer metrics; `@Rastreado` for OTel child span creation
- **Automatic PII and credential masking** — `token`, `senha`, `cpf`, `email`, and similar keys are redacted before logging, with no extra configuration
- **Extensible enrichment pipelines** — add `EnriquecedorContexto` and `EnriquecedorTracing` beans to enrich MDC and span attributes without modifying library code
- **HTTP request lifecycle** — `LogContextoFiltro` initialises correlation fields (`traceId`, `spanId`, `userId`, `applicationName`) on every inbound request and cleans the MDC on response
- **JSON-first logging** — structured JSON output via `quarkus-logging-json` with file rotation in production
- **OTel integration** — traces and logs exported via OTLP/gRPC; W3C trace context propagation; configurable sampling per environment

## Requirements

| Tool | Version |
|---|---|
| Java | 21+ |
| Quarkus | 3.32+ |
| Maven | 3.9+ |

## Quick start

```bash
# Clone and run in development mode
git clone <repo-url>
cd lib-logging-quarkus
./mvnw quarkus:dev
```

The demo application starts at `http://localhost:8080`. The [Dev UI](http://localhost:8080/q/dev/) is available in dev mode.

Try the example endpoints:

```bash
# Basic hello — demonstrates @Logged + @Rastreado at method level
curl http://localhost:8080/hello/world

# Order lookup — demonstrates comDetalhe() with automatic PII masking
curl "http://localhost:8080/hello/pedido?pedidoId=123&token=secret&cpf=000.000.000-00"
```

Observe that `token` is logged as `****` and `cpf` as `[PROTEGIDO]` in the JSON output.

## Core concepts

### Logging DSL

Use `Log` as the single entry point for all structured log events. Every call must follow the enforced sequence:

```java
// Minimum valid usage — What + Where
Log
    .registrando(OrderEvent.ORDER_CREATED)
    .em(OrderService.class, "create")
    .info();

// Minimum valid usage with automatic Where capture
Log
    .registrando(OrderEvent.ORDER_CREATED)
    .aqui()
    .info();

// Full 5W1H usage
Log
    .registrando(PaymentEvent.PAYMENT_DECLINED)
    .em(PaymentService.class, "process")
    .porque("Insufficient balance in gateway")
    .como(EntrypointEnum.API_REST)
    .comDetalhe("orderId",  order.getId())     // → real value
    .comDetalhe("amount",   order.getAmount()) // → real value
    .comDetalhe("token",    request.token())   // → "****" (auto-masked)
    .comDetalhe("cpf",      user.getCpf())     // → "[PROTEGIDO]" (auto-masked)
    .erro(exception);
```

| Method | Role | Required |
|---|---|---|
| `registrando(evento)` | What — event contract (`Event`) | Yes |
| `em(classe, metodo)` / `aqui()` | Where — technical location | Yes |
| `porque(motivo)` | Why — business reason | No |
| `como(entrypoint)` | How — entrypoint contract (`Entrypoint`) | No |
| `comDetalhe(chave, valor)` | Extra structured fields | No, repeatable |
| `info()` / `debug()` / `warn()` / `erro(ex)` | Log level terminator | Yes (one) |

### CDI annotations

#### `@Logged`

Activates `LogInterceptor` on a CDI bean or individual method. The interceptor runs the MDC enrichment chain and optionally records Micrometer execution/failure metrics.

```java
// Applied to the entire class
@ApplicationScoped
@Logged
public class OrderService { ... }

// Applied to a single method
@ApplicationScoped
public class ReportService {

    @Logged
    public Report generate(Long id) { ... }
}
```

#### `@Rastreado`

Activates `TracingInterceptor` to create a child OTel span per invocation. Runs at `APPLICATION - 10` priority, so the new `spanId` is already in the MDC when `@Logged` enriches the context.

```java
// Tracing only
@ApplicationScoped
@Rastreado
public class FiscalIntegrationClient { ... }

// Tracing + logging context
@ApplicationScoped
@Logged
@Rastreado
public class PaymentService { ... }
```

### HTTP filter

`LogContextoFiltro` is a JAX-RS `@Provider` that runs automatically on every request. It requires no additional registration.

| Phase | Action |
|---|---|
| Request | Extracts authenticated user, sets `userId` and `applicationName` in MDC, syncs `traceId` and `spanId` from the active OTel span |
| Response | Clears the MDC — prevents context leakage across Vert.x thread-pool reuse |

### Automatic PII masking

`SanitizadorDados` is applied transparently inside `comDetalhe()`. No field-by-field configuration is required.

| Category | Keys (case-insensitive) | Output |
|---|---|---|
| Credentials | `password`, `senha`, `token`, `secret`, `apikey`, `cvv`, … | `****` |
| Personal data | `cpf`, `rg`, `email`, `celular`, `cardnumber`, … | `[PROTEGIDO]` |
| Public | anything else | real value |

### MDC fields

All canonical MDC keys are declared in `CamposMdc`. Never use raw string literals — reference the enum constants to avoid naming drift across modules.

| Field | Source | Description |
|---|---|---|
| `traceId` | `GerenciadorTracing` | W3C distributed trace ID |
| `spanId` | `GerenciadorTracing` | Current OTel span ID |
| `userId` | `LogContextoFiltro` | Authenticated principal; `anonimo` if unauthenticated |
| `applicationName` | `GerenciadorContextoLog` | Value of `quarkus.application.name` |
| `classe` | `MetadadosEnriquecedorContexto` | Simple name of the `@Logged` class |
| `metodo` | `MetadadosEnriquecedorContexto` | Name of the `@Logged` method |
| `log_classe` | `Log.em()` / `Log.aqui()` | DSL-declared or automatically captured class name |
| `log_metodo` | `Log.em()` / `Log.aqui()` | DSL-declared or automatically captured method name |
| `log_motivo` | `Log.porque()` | Business reason |
| `log_entrypoint` | `Log.como()` | Entrypoint or mechanism |
| `detalhe_*` | `Log.comDetalhe()` | Domain-specific extra fields (prefixed dynamically) |

## Extending the library

### Custom MDC enricher

Implement `EnriquecedorContexto` and declare it as an `@ApplicationScoped` bean. The `LogInterceptor` discovers and executes all enrichers in ascending `prioridade()` order.

```java
@ApplicationScoped
public class OperationEnricher implements EnriquecedorContexto {

    @Override
    public void enriquecer(InvocationContext ctx) {
        MDC.put("operation.type", extractType(ctx));
    }

    @Override
    public Set<String> chavesMdc() {
        return Set.of("operation.type"); // declared for automatic cleanup
    }

    @Override
    public int prioridade() { return 100; } // infra: 10-50, business: 100+
}
```

> [!IMPORTANT]
> Always declare every MDC key your enricher _may_ write in `chavesMdc()`, even keys inserted conditionally. The library calls `MDC.remove` on all declared keys after method execution — calling it on a missing key is safe.

### Custom span enricher

Implement `EnriquecedorTracing` and declare it as an `@ApplicationScoped` bean. Called after the child span is created and made current.

```java
@ApplicationScoped
public class PaymentSpanEnricher implements EnriquecedorTracing {

    @Override
    public void enriquecer(Span span, InvocationContext ctx) {
        var params = ctx.getParameters();
        if (params != null && params.length > 0
                && params[0] instanceof PaymentRequest req) {
            span.setAttribute("payment.amount", req.amount().toString());
        }
    }

    @Override
    public int prioridade() { return 100; }
}
```

Follow [OTel Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/) for infrastructure attributes and use domain prefixes (e.g., `payment.amount`, `order.id`) for business attributes.

## Configuration

Key properties in `application.properties`:

```properties
# Application identity (used in MDC and OTel resource attributes)
quarkus.application.name=my-service

# JSON logging (enabled by default for all profiles)
quarkus.log.json=true
quarkus.log.level=INFO
quarkus.log.category."br.com.vsjr.labs".level=DEBUG

# OTLP exporter — override per environment
%dev.quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
%prod.quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317

# Micrometer metrics (disabled by default — enable per environment)
quarkus.micrometer.enabled=false
quarkus.micrometer.export.prometheus.enabled=false
```

| Profile | Console | File | OTel sampling |
|---|---|---|---|
| `dev` | JSON + color | disabled | 100% (always_on) |
| `prod` | JSON | JSON + rotation (50 MB, 10 backups) | 10% (traceidratio) |

> [!NOTE]
> Micrometer metrics (`metodo.execucao` timer and `metodo.falha` counter) are implemented in `LogInterceptor` but disabled by default. Set `quarkus.micrometer.enabled=true` to activate them in your target environment.

## Build and packaging

```bash
# Run tests
./mvnw test

# Package as JVM JAR
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar

# Package as uber-JAR
./mvnw package -Dquarkus.package.jar.type=uber-jar
java -jar target/*-runner.jar

# Build native executable (requires GraalVM)
./mvnw package -Dnative

# Build native executable inside a container (no local GraalVM needed)
./mvnw package -Dnative -Dquarkus.native.container-build=true
./target/lib-logging-quarkus-1.0.0-SNAPSHOT-runner
```

## Related guides

- [OpenTelemetry](https://quarkus.io/guides/opentelemetry) — tracing and metrics export
- [Logging JSON](https://quarkus.io/guides/logging#json-logging) — structured JSON console output
- [SmallRye JWT](https://quarkus.io/guides/security-jwt) — JWT-based security identity
- [SmallRye Context Propagation](https://quarkus.io/guides/context-propagation) — MDC propagation across reactive threads
