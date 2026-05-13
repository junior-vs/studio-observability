## Apêndice — Stack Graylog + Grafana

> **Documentos relacionados:**
> - [Métricas](METRICS.md) — tipos de medidores, Micrometer e padrões de Gauge
> - [Rastreamento Distribuído](DISTRIBUTED_TRACING.md) — pipeline OTel e `RastreamentoInterceptor`
> - [Azure Application Insights](AZURE_APPINSIGHTS.md) — backend gerenciado Azure

Este documento descreve como integrar microsserviços Quarkus ao stack **Graylog + Grafana**. Cobre as três abordagens do documento `AZURE_APPINSIGHTS.md` — `quarkus-opentelemetry-exporter-azure`, OTel puro e Java Agent — com as alterações de configuração específicas para cada uma.

---

### Restrição Fundamental: Graylog não é um Backend de Métricas

Antes de qualquer configuração, esta distinção precisa estar documentada com precisão:

**Graylog é um sistema de gerenciamento de logs** — ele ingere, indexa e permite busca em mensagens estruturadas. Seu modelo de armazenamento subjacente (OpenSearch/Elasticsearch) é um índice de documentos, não um banco de séries temporais. Ele não suporta PromQL, não agrega medições no tempo, e o Grafana não possui datasource nativo para consultar métricas armazenadas no Graylog como séries temporais.

O que o Graylog *faz* em relação a métricas é o inverso do esperado: ele *exporta* suas próprias métricas internas de operação via um endpoint Prometheus na porta 9833, para que um Prometheus externo as consuma. Ele não ingere métricas de aplicações externas como séries temporais.

| Sinal | Graylog é adequado? | Ferramenta correta |
|---|---|---|
| **Logs estruturados** | ✅ Sim — propósito central | Graylog via GELF ou OTLP input |
| **Métricas (séries temporais)** | ❌ Não nativamente | Prometheus → Grafana |
| **Métricas como workaround GELF** | ⚠️ Tecnicamente possível, com limitações severas | Ver seção abaixo |
| **Traces** | ❌ Não suportado | Grafana Tempo, Jaeger, Azure Application Insights |

> **Traces neste documento:** traces são documentados como desabilitados. Quando um backend de traces for introduzido (Jaeger, Grafana Tempo ou Azure Application Insights), o pipeline OTel já estará preparado para recebê-los — basta adicionar o exporter correspondente ao Collector ou habilitar a extensão adequada.

---

### Arquitetura do Stack

```
Aplicação Quarkus
        │
        ├─── Abordagem 1/2: OTLP/gRPC → OTel Collector → roteia por sinal
        │
        └─── Abordagem 3: Java Agent intercepta JVM
                │
                ├─── Logs  → GELF/UDP ou TCP  → Graylog :12201
                └─── Logs  → OTLP/gRPC        → Graylog :4317 (v6.2+)

                     Métricas → /q/metrics ← scrape ← Prometheus :9090
                                                               │
                                                          Grafana :3000
                                                    (datasource: Prometheus)
```

---

## Abordagem 1 — `quarkus-opentelemetry-exporter-azure` com Graylog + Grafana

A extensão Quarkiverse envia logs e traces via OTel para o Application Insights. Para direcionar **apenas os logs** ao Graylog em vez do Application Insights, é necessário intercalar um OTel Collector como roteador — a extensão não suporta múltiplos exporters nativamente.

Se o objetivo é Graylog **exclusivamente** (sem Application Insights), a Abordagem 2 (OTel Collector puro) é mais direta. A Abordagem 1 faz mais sentido em um cenário híbrido: logs no Graylog, traces + métricas no Application Insights.

### Dependências Maven

```xml
<!-- Exportador Azure — traces e métricas vão ao Application Insights -->
<dependency>
    <groupId>io.quarkiverse.opentelemetry.exporter</groupId>
    <artifactId>quarkus-opentelemetry-exporter-azure</artifactId>
    <version>3.31.4.0</version>
</dependency>

<!-- Logs via GELF direto ao Graylog — independente do pipeline OTel -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-logging-gelf</artifactId>
</dependency>

<!-- Métricas via Prometheus scrape (endpoint /q/metrics) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

### `application.properties`

```properties
# ─── Identidade do serviço ────────────────────────────────────────────────────
quarkus.application.name=pedido-service
quarkus.otel.resource.attributes=\
    service.name=pedido-service,\
    service.namespace=plataforma-comercial,\
    service.instance.id=${HOSTNAME:localhost},\
    deployment.environment=${QUARKUS_PROFILE:dev}

# ─── OTel: traces + métricas → Application Insights ─────────────────────────
quarkus.otel.enabled=true
quarkus.otel.traces.enabled=true
quarkus.otel.metrics.enabled=true
# Logs OTel desabilitados — logs vão ao Graylog via GELF (abaixo)
quarkus.otel.logs.enabled=false

quarkus.otel.azure.applicationinsights.connection-string=${APPLICATIONINSIGHTS_CONNECTION_STRING}

%dev.quarkus.otel.traces.sampler=always_on
%prod.quarkus.otel.traces.sampler=parentbased_traceidratio
%prod.quarkus.otel.traces.sampler.arg=0.20

# ─── GELF → Graylog (logs) ────────────────────────────────────────────────────
quarkus.log.handler.gelf.enabled=true
quarkus.log.handler.gelf.host=graylog
quarkus.log.handler.gelf.port=12201
# Protocolo: udp (padrão, sem garantia de entrega) ou tcp: (prefixar com "tcp:")
# quarkus.log.handler.gelf.host=tcp:graylog

quarkus.log.handler.gelf.version=1.1
quarkus.log.handler.gelf.include-full-mdc=true   # envia traceId, spanId, servico, userId
quarkus.log.handler.gelf.extract-stack-trace=true
quarkus.log.handler.gelf.filter-stack-trace=true
quarkus.log.handler.gelf.level=INFO

# Campos canônicos do projeto enviados como campos GELF adicionais
# Os campos MDC já são enviados via include-full-mdc=true
# Campos estáticos de infraestrutura:
quarkus.log.handler.gelf.additional-field.environment.value=${QUARKUS_PROFILE:dev}
quarkus.log.handler.gelf.additional-field.environment.type=String
quarkus.log.handler.gelf.additional-field.app_version.value=${quarkus.application.version:unknown}
quarkus.log.handler.gelf.additional-field.app_version.type=String

# ─── Métricas via Micrometer → endpoint Prometheus ──────────────────────────
quarkus.micrometer.enabled=true
quarkus.micrometer.binder.http-server.enabled=true
quarkus.micrometer.binder.http-server.ignore-patterns=/q/health.*,/q/metrics
quarkus.micrometer.binder.jvm=true
quarkus.datasource.jdbc.telemetry=true

# ─── Dev/test ─────────────────────────────────────────────────────────────────
%dev.quarkus.log.handler.gelf.enabled=false     # console apenas em dev
%test.quarkus.otel.enabled=false
%test.quarkus.log.handler.gelf.enabled=false
```

---

## Abordagem 2 — OTel Collector com Graylog + Grafana

Esta é a abordagem mais natural para o stack Graylog + Grafana. O OTel Collector recebe todos os sinais e os roteia: logs para o Graylog, métricas para um endpoint Prometheus scrape. Traces são desabilitados ou descartados no Collector.

### Dependências Maven

```xml
<!-- OTel nativo do Quarkus -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>

<!-- Métricas Micrometer via OTel (logs + métricas pelo mesmo pipeline) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-opentelemetry</artifactId>
</dependency>
```

### `application.properties`

```properties
# ─── Identidade do serviço ────────────────────────────────────────────────────
quarkus.application.name=pedido-service
quarkus.otel.resource.attributes=\
    service.name=pedido-service,\
    service.namespace=plataforma-comercial,\
    service.instance.id=${HOSTNAME:localhost},\
    deployment.environment=${QUARKUS_PROFILE:dev}

# ─── Sinais: traces desabilitados — logs e métricas apenas ───────────────────
quarkus.otel.enabled=true
quarkus.otel.traces.enabled=false       # sem backend de traces por ora
quarkus.otel.metrics.enabled=true
quarkus.otel.logs.enabled=true          # requer Quarkus 3.16+

# ─── Endpoint OTLP → OTel Collector ──────────────────────────────────────────
%prod.quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317
%prod.quarkus.otel.exporter.otlp.protocol=grpc

# ─── Micrometer ───────────────────────────────────────────────────────────────
quarkus.micrometer.enabled=true
quarkus.micrometer.binder.http-server.enabled=true
quarkus.micrometer.binder.http-server.ignore-patterns=/q/health.*,/q/metrics
quarkus.micrometer.binder.jvm=true
quarkus.datasource.jdbc.telemetry=true

# ─── Dev/test ─────────────────────────────────────────────────────────────────
%dev.quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
%test.quarkus.otel.enabled=false
```

### OTel Collector (`otel-collector-config.yaml`)

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:
    timeout: 5s
    send_batch_size: 512

  memory_limiter:
    check_interval: 1s
    limit_mib: 512
    spike_limit_mib: 128

  resource:
    attributes:
      - action: insert
        key: cloud.provider
        value: on-premises

exporters:
  # ─── Logs → Graylog ────────────────────────────────────────────────────────
  # Graylog 6.2+ suporta input OpenTelemetry (gRPC) nativamente
  otlp/graylog:
    endpoint: "graylog:4317"
    tls:
      insecure: true          # apenas dev/lab — usar TLS em produção

  # ─── Métricas → endpoint Prometheus (modelo pull) ─────────────────────────
  # O Prometheus scrape este endpoint do Collector periodicamente
  prometheus:
    endpoint: "0.0.0.0:9464"
    namespace: "quarkus"
    resource_to_telemetry_conversion:
      enabled: true           # converte resource attributes em labels Prometheus

  # ─── Traces → descartados (sem backend por ora) ───────────────────────────
  # Para habilitar traces futuramente, substituir por otlp/tempo, jaeger etc.
  # otlp/tempo:
  #   endpoint: "tempo:4317"
  #   tls:
  #     insecure: true

  debug:
    verbosity: basic

service:
  pipelines:
    logs:
      receivers:  [otlp]
      processors: [memory_limiter, batch, resource]
      exporters:  [otlp/graylog]

    metrics:
      receivers:  [otlp]
      processors: [memory_limiter, batch, resource]
      exporters:  [prometheus]

    # Pipeline de traces comentado — habilitar quando houver backend
    # traces:
    #   receivers:  [otlp]
    #   processors: [memory_limiter, batch, resource]
    #   exporters:  [otlp/tempo]
```

> **Graylog < 6.2:** se a versão disponível não suportar o input OpenTelemetry/gRPC, substituir o exporter `otlp/graylog` pelo exporter `loki` apontando para um Loki intermediário, ou usar a Abordagem 3 com GELF direto.

### `prometheus.yml` — configuração de scrape

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: "otel-collector-quarkus"
    static_configs:
      - targets: ["otel-collector:9464"]
    # Labels adicionados a todas as métricas deste job
    relabel_configs:
      - target_label: job
        replacement: pedido-service
```

---

## Abordagem 3 — Java Agent com Graylog + Grafana

O Java Agent da Microsoft intercepta a JVM antes da inicialização do Quarkus. Para logs ao Graylog e métricas ao Prometheus, a configuração combina o agente (para traces e telemetria automática) com o `quarkus-logging-gelf` (para logs GELF) e `quarkus-micrometer-registry-prometheus` (para métricas via scrape).

**Restrição:** esta abordagem é válida apenas para JVM. Não é compatível com GraalVM native image.

### Dependências Maven

```xml
<!-- Java Agent Microsoft — download via Maven Dependency Plugin -->
<dependency>
    <groupId>com.microsoft.azure</groupId>
    <artifactId>applicationinsights-agent</artifactId>
    <version>3.7.8</version>
    <scope>provided</scope>
</dependency>

<!-- Logs GELF → Graylog -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-logging-gelf</artifactId>
</dependency>

<!-- Métricas → endpoint Prometheus scrape -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

### `application.properties`

```properties
# ─── GELF → Graylog ───────────────────────────────────────────────────────────
quarkus.log.handler.gelf.enabled=true
quarkus.log.handler.gelf.host=graylog
quarkus.log.handler.gelf.port=12201
quarkus.log.handler.gelf.version=1.1
quarkus.log.handler.gelf.include-full-mdc=true
quarkus.log.handler.gelf.extract-stack-trace=true
quarkus.log.handler.gelf.level=INFO

quarkus.log.handler.gelf.additional-field.environment.value=${QUARKUS_PROFILE:dev}
quarkus.log.handler.gelf.additional-field.environment.type=String

# ─── Micrometer → endpoint Prometheus ────────────────────────────────────────
# O Java Agent coleta métricas Micrometer internamente (para Application Insights).
# quarkus-micrometer-registry-prometheus adiciona um segundo coletor independente
# que expõe /q/metrics para scrape do Prometheus. Não há conflito entre os dois.
quarkus.micrometer.enabled=true
quarkus.micrometer.binder.http-server.enabled=true
quarkus.micrometer.binder.http-server.ignore-patterns=/q/health.*,/q/metrics
quarkus.micrometer.binder.jvm=true

# ─── Dev/test ─────────────────────────────────────────────────────────────────
%dev.quarkus.log.handler.gelf.enabled=false
%test.quarkus.log.handler.gelf.enabled=false
```

### `applicationinsights.json`

Com foco em Graylog + Grafana, os logs não precisam ir ao Application Insights. O agente ainda pode ser útil para telemetria automática de traces futuros ou como fallback:

```json
{
  "connectionString": "${APPLICATIONINSIGHTS_CONNECTION_STRING}",

  "role": {
    "name": "pedido-service",
    "instance": "${HOSTNAME}"
  },

  "sampling": {
    "percentage": 20
  },

  "instrumentation": {
    "logging": {
      "level": "OFF"
    },
    "micrometer": {
      "enabled": false
    },
    "jdbc": {
      "enabled": true
    },
    "vertx": {
      "enabled": true
    }
  }
}
```

> **`logging.level: OFF` e `micrometer.enabled: false`:** desabilita a coleta de logs e métricas pelo agente para o Application Insights — logs vão ao Graylog via GELF, métricas vão ao Prometheus via `/q/metrics`. O agente continua ativo para instrumentação automática de JDBC, Vert.x e traces (quando um backend for configurado).

---

## Configuração do Graylog

### Input GELF UDP (para Abordagens 1 e 3)

No portal Graylog: **System → Inputs → Launch new input → GELF UDP**

| Campo | Valor |
|---|---|
| **Title** | `Quarkus GELF Input` |
| **Bind address** | `0.0.0.0` |
| **Port** | `12201` |
| **Recv buffer size** | `262144` |
| **Decompress size limit** | `8388608` |

Para TCP (mais confiável em produção, sem perda de pacotes UDP):

**System → Inputs → Launch new input → GELF TCP**, mesma porta 12201.

> **UDP vs. TCP para GELF:** UDP é o padrão mais simples — sem handshake, sem confirmação de entrega. Em redes confiáveis (mesmo datacenter, mesma VPC), UDP é adequado e tem menor overhead. Em redes com variação de latência ou quando a perda de logs é inaceitável, use TCP. Configure com `quarkus.log.handler.gelf.host=tcp:graylog`.

### Input OpenTelemetry gRPC (para Abordagem 2, Graylog 6.2+)

**System → Inputs → Launch new input → OpenTelemetry (gRPC)**

| Campo | Valor |
|---|---|
| **Title** | `OTel Collector Input` |
| **Bind address** | `0.0.0.0` |
| **Port** | `4317` |
| **TLS** | Desabilitado em dev |

### Campos MDC → Graylog

O `quarkus-logging-gelf` com `include-full-mdc=true` envia todos os campos MDC como campos GELF adicionais. Os campos canônicos do projeto chegam com seus nomes originais:

| Campo MDC (projeto) | Campo no Graylog | Tipo |
|---|---|---|
| `traceId` | `traceId` | String |
| `spanId` | `spanId` | String |
| `servico` | `servico` | String |
| `userId` | `userId` | String |
| `environment` (adicional) | `environment` | String |

> **Nomes preservados via GELF:** ao contrário do input OTLP (que substitui pontos por underscores nos resource attributes), campos GELF chegam com seus nomes exatos do MDC. O `traceId` do projeto chega como `traceId` no Graylog — sem transformação. Buscas e alertas usam o nome diretamente.

### Streams Recomendados

```
Stream: "Produção — Erros e Avisos"
  Regra: environment = "prod"
       E level >= 4    (WARN=4, ERROR=3, no Syslog numérico invertido do GELF)
  Índice: prod-errors (retenção: 90 dias)

Stream: "Produção — Todos"
  Regra: environment = "prod"
  Índice: prod-all (retenção: 30 dias)

Stream: "Dev e Staging"
  Regra: environment != "prod"
  Índice: non-prod (retenção: 7 dias)
```

### Queries de Busca

```
# Todos os logs de uma transação específica (correlação por traceId)
traceId:"4bf92f3577b34da6a3ce929d0e0e4736"

# Erros do PedidoService em produção nas últimas 2h
app_name:"pedido-service" AND level:<=3 AND environment:"prod"

# Logs de um usuário específico (campo canônico userId)
userId:"usr_abc123" AND environment:"prod"

# Falhas de pagamento por código de exceção
servico:"pedido-service" AND _exists_:excecao AND excecao:"GatewayException"
```

---

## Configuração do Grafana

O Grafana entra exclusivamente como camada de dashboards — sem Loki nem Tempo. O único datasource de métricas é o Prometheus (ou Mimir).

### `datasources.yaml`

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    access: proxy
    isDefault: true
    jsonData:
      timeInterval: "15s"     # alinhado ao scrape_interval do Prometheus

  # Grafana Mimir como alternativa ao Prometheus (PromQL compatível):
  # - name: Mimir
  #   type: prometheus
  #   url: http://mimir:9009/prometheus
  #   access: proxy
  #   isDefault: true
```

### Dashboards — PromQL sem alteração

As queries PromQL documentadas no `METRICS.md` funcionam sem nenhuma modificação:

```promql
# Taxa de erro do PedidoService.criar
rate(metodo_falha_total{classe="PedidoService", metodo="criar"}[5m])
/
rate(metodo_execucao_seconds_count{classe="PedidoService", metodo="criar"}[5m])

# p99 de latência (histograma do LogInterceptor)
histogram_quantile(0.99,
    sum by (le) (
        rate(metodo_execucao_seconds_bucket{classe="PedidoService"}[5m])
    )
)

# Gauge: pedidos em estado PENDENTE (Monitor Externo — METRICS.md §15.1)
pedido_estado_pendente
```

### Link Grafana → Graylog por `traceId`

Como não há datasource nativo Graylog no Grafana, a correlação entre dashboards e logs é feita via link de dados (*Data link*) no painel de traces ou métricas:

```
URL do Data Link:
http://graylog:9000/search?q=traceId%3A%22${__value.raw}%22&rangetype=relative&relative=3600
```

Este link abre o Graylog com a busca `traceId:"<valor>"` já preenchida, com janela de 1h relativa ao momento atual.

---

## Workaround: Métricas como Mensagens GELF no Graylog

> **⚠️ Esta seção documenta um workaround com limitações severas. Para métricas em produção, use Prometheus + Grafana. Este padrão é documentado apenas para cenários onde Prometheus não está disponível e a equipe aceita as restrições.**

O Graylog pode receber medições de métricas como mensagens GELF — cada ponto de uma métrica vira um documento indexado. Isso permite buscas e alertas básicos no Graylog, mas **não é equivalente a um banco de séries temporais**.

### Limitações Documentadas

**Sem agregação temporal nativa:** o Graylog não calcula `rate()`, `histogram_quantile()` ou qualquer derivada temporal. Cada mensagem é um documento independente.

**Conflito de tipos no índice:** o OpenSearch/Elasticsearch subjacente determina o tipo de um campo pelo primeiro documento ingerido. Se uma métrica chegar como `long` em um evento e como `double` em outro, o segundo causará erro de parsing e será descartado silenciosamente.

**Volume:** cada scrape do Micrometer em intervalos de 15s para uma aplicação com 50 métricas = 3 mensagens/segundo apenas de telemetria. Em produção, isso degrada o desempenho do Graylog e o custo de storage rapidamente.

**Sem PromQL:** queries no Graylog usam a sintaxe Lucene/OpenSearch — incompatível com dashboards Grafana baseados em PromQL.

### Implementação (se aceita as restrições)

```java
// MicrometerGelfReporter.java — bean CDI que publica métricas como GELF
// NÃO use em produção sem avaliar o impacto de volume
@ApplicationScoped
public class MicrometerGelfReporter {

    private final MeterRegistry meterRegistry;

    public MicrometerGelfReporter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // Publicar snapshot das métricas a cada 60 segundos como logs estruturados
    // Cada métrica vira uma linha de log com campos numéricos — ingerida via GELF
    @Scheduled(every = "60s")
    public void publicarSnapshot() {
        meterRegistry.getMeters().forEach(meter -> {
            // O logger SLF4J com MDC injeta os campos no GELF via include-full-mdc=true
            MDC.put("metric_name",  meter.getId().getName());
            MDC.put("metric_type",  meter.getId().getType().name());
            meter.getId().getTags().forEach(t -> MDC.put("tag_" + t.getKey(), t.getValue()));

            // Extrair valor numérico conforme o tipo de medidor
            double valor = extrairValor(meter);
            MDC.put("metric_value", String.valueOf(valor));

            log.info("[METRICA] {} = {}", meter.getId().getName(), valor);
            MDC.remove("metric_name");
            MDC.remove("metric_type");
            MDC.remove("metric_value");
        });
    }

    private double extrairValor(Meter meter) {
        return switch (meter) {
            case Counter c          -> c.count();
            case Gauge g            -> g.value();
            case Timer t            -> t.mean(TimeUnit.MILLISECONDS);
            default                 -> meter.measure().iterator().next().getValue();
        };
    }
}
```

> **Por que não usar um `MeterRegistry` customizado para GELF:** seria possível implementar um `MeterRegistry` que envia cada incremento diretamente ao Graylog. O problema é o volume — cada chamada `counter.increment()` geraria uma mensagem GELF, saturando o Graylog em caminhos de alta frequência. O `@Scheduled` acima ao menos agrega por snapshot periódico.

---

## Resumo: Alterações por Abordagem

| Componente | Abordagem 1 | Abordagem 2 | Abordagem 3 |
|---|---|---|---|
| **`application.properties`** | + `quarkus-logging-gelf` config; `otel.logs.enabled=false` | `otel.traces.enabled=false`; endpoint → OTel Collector | + `quarkus-logging-gelf` config |
| **Dependências** | + `quarkus-logging-gelf` + `quarkus-micrometer-registry-prometheus` | `quarkus-micrometer-opentelemetry` (sem mudança) | + `quarkus-logging-gelf` + `quarkus-micrometer-registry-prometheus` |
| **OTel Collector** | Não necessário para logs/métricas | Pipeline: logs → Graylog, métricas → Prometheus exporter | Não necessário |
| **Graylog** | Input GELF UDP/TCP porta 12201 | Input OTel gRPC porta 4317 (v6.2+) | Input GELF UDP/TCP porta 12201 |
| **Prometheus** | Scrape `/q/metrics` diretamente | Scrape endpoint do Collector porta 9464 | Scrape `/q/metrics` diretamente |
| **Grafana** | Datasource: Prometheus | Datasource: Prometheus | Datasource: Prometheus |
| **Traces** | Application Insights (via extensão Azure) | Desabilitado | Desabilitado |

---

## Referências

- [Quarkus — Centralized log management (GELF)](https://quarkus.io/guides/centralized-log-management)
- [Quarkus — quarkus-logging-gelf extension](https://quarkus.io/extensions/io.quarkus/quarkus-logging-gelf/)
- [Graylog — Getting Started with Inputs](https://go2docs.graylog.org/current/getting_in_log_data/getting_in_log_data.html)
- [Graylog — OpenTelemetry (gRPC) Input (v6.2+)](https://go2docs.graylog.org/current/getting_in_log_data/opentelemetry__grpc__input.htm)
- [Graylog — Operational Metrics (Prometheus export)](https://go2docs.graylog.org/current/interacting_with_your_log_data/metrics.html)
- [Grafana — Prometheus datasource](https://grafana.com/docs/grafana/latest/datasources/prometheus/)
- [OTel Collector — Prometheus Exporter](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/exporter/prometheusexporter)
- [logstash-gelf library — configuração do handler](https://logging.paluch.biz/)