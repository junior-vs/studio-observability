# lib-logging-quarkus

[![Build](https://img.shields.io/github/actions/workflow/status/vsjrlabs/studio-observability/build.yml?style=flat-square)](../../actions)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk)](https://openjdk.org)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.32-4695EB?style=flat-square&logo=quarkus)](https://quarkus.io)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](LICENSE)

> Structured observability for Quarkus: a compile-time-safe logging DSL, CDI interceptors for distributed tracing, automatic PII masking, and extensible MDC enrichment pipelines.

The library enforces a structured **5W1H** logging contract at compile time using Java 21 sealed interfaces — incomplete log statements are compile errors, not silent runtime gaps. It integrates transparently with OpenTelemetry, Micrometer, and JSON logging via standard Quarkus extensions.

## Features

- **Compile-time-safe DSL** — `Log.registrando(Event).em()|aqui().[porque().[como(Entrypoint).[comDetalhe()]*]].info()|debug()|warn()|erro()` guided by sealed interfaces
- **CDI interceptors** — `@Logged` for MDC enrichment and optional Micrometer metrics; `@Traced` for OTel child span creation
- **Automatic PII and credential masking** — `token`, `senha`, `cpf`, `email`, and similar keys are redacted before logging, with no extra configuration
- **Extensible enrichment pipelines** — add `EnriquecedorContexto` and `EnriquecedorTracing` beans to enrich MDC and span attributes without modifying library code
- **HTTP request lifecycle** — `LogContextoFiltro` initialises correlation fields (`traceId`, `spanId`, `userId`, `applicationName`) on every inbound request and cleans the MDC on response
- **JSON-ready logging** — structured JSON output via `quarkus-logging-json`, with operational policies controlled by the consuming application
- **OTel integration** — traces and logs exported via OTLP/gRPC; W3C trace context propagation; configurable sampling per environment

## Requirements

| Tool | Version |
|---|---|
| Java | 21+ |
| Quarkus | 3.32+ |
| Maven | 3.9+ |

## Installation

Add the library to a Quarkus application:

```xml
<dependency>
    <groupId>br.com.vsjr.labs</groupId>
    <artifactId>lib-logging-quarkus</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Local development from this repository:

```bash
mvn -pl lib-logging-quarkus test
mvn -pl lib-logging-quarkus install
```

## Example App

Examples are intentionally outside the library artifact, under `examples/logging-quarkus-example`.

Run the example application:

```bash
cd examples/logging-quarkus-example
mvn quarkus:dev
```

Try the example endpoints:

```bash
curl http://localhost:8080/hello/world

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

#### `@Traced`

Activates `TracingInterceptor` to create a child OTel span per invocation. Runs at `APPLICATION - 10` priority, so the new `spanId` is already in the MDC when `@Logged` enriches the context.

```java
// Tracing only
@ApplicationScoped
@Traced
public class FiscalIntegrationClient { ... }

// Tracing + logging context
@ApplicationScoped
@Logged
@Traced
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

The library does not impose production logging, file, OTel endpoint, sampling, or application-name policies. Configure those in the consuming application.

Common application properties:

```properties
# Application identity (used in MDC and OTel resource attributes)
quarkus.application.name=my-service

# JSON logging, if desired by the application
quarkus.log.json=true
quarkus.log.level=INFO
quarkus.log.category."br.com.vsjr.labs".level=DEBUG

# OTLP exporter, if the application exports traces/logs
%dev.quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
%prod.quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317

# Automatic @Logged method metrics
quarkus.micrometer.enabled=true
quarkus.micrometer.export.prometheus.enabled=true
```

> [!NOTE]
> Micrometer metrics (`<application>.metodo.execucao` timer and `<application>.metodo.falha` counter) are emitted only when a `MeterRegistry` is available.

## Build and packaging

```bash
# Run tests
mvn -pl lib-logging-quarkus test

# Package library JAR
mvn -pl lib-logging-quarkus package

# Run example app
mvn -pl examples/logging-quarkus-example quarkus:dev
```

## Related guides

- [OpenTelemetry](https://quarkus.io/guides/opentelemetry) — tracing and metrics export
- [Logging JSON](https://quarkus.io/guides/logging#json-logging) — structured JSON console output
- [SmallRye JWT](https://quarkus.io/guides/security-jwt) — JWT-based security identity
- [SmallRye Context Propagation](https://quarkus.io/guides/context-propagation) — MDC propagation across reactive threads
