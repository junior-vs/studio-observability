# Métricas

> **Documentos relacionados:**
> - [5W1H](5W1H.md) — fundamentos de contexto semântico para logs estruturados
> - [Rastreamento Distribuído](DISTRIBUTED_TRACING.md) — o terceiro pilar da observabilidade
> - [Campos Canônicos](FIELD_NAMES.md) — padronização de nomes de campos e tags
> - [Padrões de Codificação](CODING_STANDARDS.md) — falhas de `MeterRegistry` não propagam como exceção de negócio

---

## Parte I — Conceitos

---

### 1. O Problema: o que Logs e Traces não Respondem

Logs e traces são indispensáveis para diagnóstico — eles respondem *o que aconteceu* e *por onde a requisição passou*. Mas eles não foram projetados para responder perguntas de volume e tendência sobre o sistema como um todo:

- A taxa de erros no último minuto subiu ou desceu?
- O tempo de resposta do endpoint `POST /pedidos` nos últimos 30 dias segue tendência de degradação?
- Quantas requisições simultâneas o sistema está atendendo agora?
- A fila de processamento está crescendo ou estabilizando?

Responder essas perguntas a partir de logs exigiria consultar e agregar potencialmente milhões de registros em tempo real — operação custosa, com latência incompatível com alertas de produção. Métricas existem precisamente para tornar essas perguntas respondíveis em milissegundos, com custo computacional desprezível.

> **Métricas dizem *se* há um problema; Traces dizem *onde* ele está; Logs dizem *qual* foi o erro exato.**

---

### 2. Métricas nos Três Pilares da Observabilidade

Métricas são o segundo pilar da observabilidade. Os três pilares são complementares — cada um captura uma dimensão diferente do comportamento do sistema:

| Pilar | Pergunta Central | Natureza do Dado | Custo de Consulta |
|---|---|---|---|
| **Logging** | *"O que aconteceu em um momento específico?"* | Registros discretos de eventos com contexto e severidade | Alto — varredura de volume |
| **Métricas** | *"Com que frequência, volume e tendência?"* | Valores numéricos agregados em séries temporais | Baixo — pré-agregado |
| **Tracing** | *"Por onde a requisição passou e onde demorou?"* | Grafo temporal de operações encadeadas | Médio — por `traceId` |

O OpenTelemetry é a infraestrutura que une os três sinais em um único pipeline de telemetria, com correlação nativa pelo `traceId`.

---

### 3. O que é uma Métrica

Uma métrica é uma **medição numérica capturada em um ponto no tempo**, associada a um nome e a um conjunto de dimensões (*tags*). Métricas são armazenadas em **bancos de dados de séries temporais** (*time series databases*), onde cada nova medição é adicionada ao final da série — sem sobrescrever valores anteriores.

Essa natureza de série temporal é o que torna métricas adequadas para análise de tendência, detecção de anomalias e configuração de alertas. Quando o Prometheus armazena `http_server_requests_seconds_count`, ele mantém cada valor com seu timestamp — permitindo calcular a taxa de crescimento, identificar picos e configurar alertas por limiar.

**Características fundamentais:**

- **Pré-agregação:** ao contrário de logs, métricas são calculadas na origem e transmitidas já em forma agregada. O contador de requisições não envia um evento por requisição — acumula e envia a contagem periodicamente.
- **Baixa cardinalidade por design:** cada combinação única de nome de métrica e conjunto de tags produz uma série temporal independente. O número total de séries deve ser controlado.
- **Resolução temporal configurável:** métricas são amostradas em intervalos regulares (ex: a cada 15 segundos pelo Prometheus). Eventos que ocorrem entre dois *scrapes* são agregados, não individualmente registrados.

---

### 4. Tipos Fundamentais de Medidores

O Micrometer define quatro tipos primitivos de medidores (*meters*), suficientes para modelar qualquer fenômeno observável em sistemas de software:

#### 4.1. Counter (Contador)

Registra um valor que **somente aumenta**. Nunca decresce — apenas avança. Quando um serviço reinicia, o contador retorna a zero.

**Quando usar:** eventos que ocorrem e são contados — requisições processadas, erros lançados, mensagens consumidas, pagamentos aprovados.

**Pergunta que responde:** *"Quantas vezes X aconteceu?"* e, por derivação, *"Com que taxa X está acontecendo?"* (calculada como `delta(counter) / delta(tempo)`).

```
Exemplos:
  metodo.falha{classe="PagamentoService", metodo="processar", excecao="GatewayException"}
  http.requests.total{uri="/pedidos", method="POST", status="200"}
  ordem.pagamento.recusado{gateway="Cielo", motivo="saldo_insuficiente"}
```

**Anti-padrão:** usar um counter para medir algo que pode diminuir (ex: número de itens em uma fila). Para isso, use um Gauge.

#### 4.2. Gauge (Medidor)

Registra um valor que **pode aumentar ou diminuir** — como o velocímetro de um carro. O Gauge não acumula: ele captura o valor instantâneo no momento da leitura.

**Quando usar:** estados correntes de recursos — tamanho de fila, número de conexões ativas em um pool, uso de memória, número de threads em execução, itens em cache.

**Pergunta que responde:** *"Qual é o valor atual de X?"*

```
Exemplos:
  jvm.memory.used{area="heap"}
  db.connections.active{pool="pedidos-ds"}
  fila.processamento.tamanho{topico="pagamentos"}
```

**Nota importante:** o Micrometer não mantém referência forte ao objeto observado pelo Gauge. Se o objeto for coletado pelo GC, o Gauge passa a retornar `NaN`. Sempre mantenha uma referência forte ao objeto instrumentado.

#### 4.3. Timer (Cronômetro)

Mede **latência e frequência** de operações de curta duração. Internamente armazena três valores: a soma de todas as durações medidas, a contagem de ocorrências e o valor máximo observado em uma janela de tempo decrescente (*decaying time window*).

**Quando usar:** qualquer operação com duração mensurável — execução de endpoints HTTP, consultas ao banco de dados, chamadas a APIs externas, processamento de métodos de negócio críticos.

**Pergunta que responde:** *"Quanto tempo X leva? Com que frequência X ocorre? Qual foi a latência máxima recente?"*

```
Exemplos:
  metodo.execucao{classe="PedidoService", metodo="criar"}
  http.server.requests{uri="/pedidos", method="POST"}
  db.query.duration{operation="SELECT", table="orders"}
```

Timers suportam **histogramas** e **percentis** (p50, p95, p99) — fundamentais para SLOs. Um valor médio de latência pode esconder que 5% das requisições levam 10x mais tempo.

#### 4.4. Distribution Summary (Sumário de Distribuição)

Semelhante ao Timer, mas para **valores não temporais arbitrários**. Registra distribuição de magnitudes — tamanho de payload, número de itens em uma resposta, valor de transações financeiras.

**Quando usar:** quando o fenômeno a medir é uma quantidade, não uma duração.

```
Exemplos:
  http.response.size{uri="/pedidos", method="GET"}       — tamanho da resposta em bytes
  pedido.itens.quantidade{canal="checkout"}              — número de itens por pedido
  pagamento.valor{moeda="BRL", gateway="Cielo"}          — valor das transações
```

---

### 5. Dimensões (Tags) e o Risco de Explosão de Cardinalidade

Tags são pares chave-valor associados a uma métrica no momento do registro. Elas são o mecanismo que torna métricas **multidimensionais** — permitem fatiar e agrupar dados de formas diferentes sem criar métricas separadas para cada combinação.

```
metodo.execucao{classe="PedidoService", metodo="criar"}     → latência por método
metodo.execucao{classe="PagamentoService", metodo="processar"} → latência por método
```

Com a tag `classe`, uma única métrica `metodo.execucao` cobre todos os serviços. Sem ela, seria necessária uma métrica separada para cada classe — inviável em escala.

**Risco de explosão de cardinalidade:** cada combinação única de nome de métrica e conjunto de tag-values produz uma série temporal independente no banco de tempo. Tags com alta cardinalidade — valores que variam por requisição, como `userId`, `requestId`, `traceId` ou IDs de entidades — multiplicam exponencialmente o número de séries, saturando armazenamento e memória do sistema de coleta.

| Uso de tag | Cardinalidade | Adequado para métrica? |
|---|---|---|
| `status` (`200`, `404`, `500`) | Baixa — poucos valores fixos | ✅ Correto |
| `metodo` (`criar`, `buscar`, `deletar`) | Baixa — conjunto fechado | ✅ Correto |
| `userId` (milhões de usuários distintos) | Alta — cresce com usuários | ❌ Explosão de cardinalidade |
| `traceId` (único por requisição) | Extremamente alta | ❌ Nunca usar como tag |
| `pedidoId` (único por pedido) | Alta — cresce com volume | ❌ Use logs/traces para isso |

**Regra:** use tags apenas para valores de um conjunto **fechado e pequeno**. Para correlacionar uma métrica com uma entidade ou usuário específico, use o `traceId` — ele existe para isso.

---

### 6. Correlação com Logs e Traces

Métricas, por sua natureza agregada, não identificam *qual* requisição causou um problema — elas identificam *que* existe um problema e *onde* no sistema ele está concentrado. O fluxo de investigação típico atravessa os três pilares:

```
1. ALERTA DISPARA (Prometheus/Grafana)
   └─ métricas mostram: taxa de erro de PagamentoService.processar acima de 5%

2. ANÁLISE DE TENDÊNCIA (Grafana)
   └─ histograma revela: p99 de latência subiu de 200ms para 2s nas últimas 2h
   └─ counter de falhas: erro específico é GatewayException

3. IDENTIFICAÇÃO DO SPAN PROBLEMÁTICO (Jaeger/Grafana Tempo)
   └─ traces com status=ERROR filtrados por serviço e janela de tempo
   └─ grafo de spans revela: GatewayClient.cobrar leva 1.8s dos 2s totais

4. DIAGNÓSTICO DETALHADO (Kibana/Loki)
   └─ filtrar por traceId do trace problemático
   └─ logs exibem: contexto completo, userId, pedidoId, código de erro do gateway
```

O `traceId` presente em cada linha de log e em cada span é a chave que torna essa navegação possível. Métricas apontam o problema; traces localizam o componente; logs explicam o contexto exato.

---

### 7. Quando Utilizar cada Tipo de Medidor

| Fenômeno a observar | Tipo recomendado | Justificativa |
|---|---|---|
| Número de requisições processadas | Counter | Sempre cresce; taxa calculada por derivação |
| Número de erros por tipo de exceção | Counter | Sempre cresce; permite alertas por tipo |
| Latência de endpoints HTTP | Timer | Duração + frequência; suporta percentis |
| Duração de métodos de negócio | Timer | Duração + frequência; suporta histograma |
| Conexões ativas em um pool | Gauge | Valor atual — pode subir e descer |
| Tamanho de uma fila | Gauge | Valor atual — não acumula |
| Uso de memória JVM | Gauge | Valor amostrado — não acumula |
| Tamanho de payloads HTTP | Distribution Summary | Valor arbitrário, não temporal |
| Valor de transações financeiras | Distribution Summary | Distribuição de magnitudes |

**Quando usar um Counter em vez de um Gauge:** se o que você quer medir *pode* ser contado porque sempre incrementa (número de pedidos criados, número de erros lançados), use Counter. O rate de um Counter calculado pelo Prometheus é mais confiável do que um Gauge que pode perder incrementos entre amostras.

---

### 8. Trade-offs

**Perda de granularidade individual:** métricas agregam. Um Timer com p99 de 2 segundos não diz *qual* requisição demorou 2 segundos — apenas que 1% das requisições estão nessa faixa. Para identificar a requisição específica, o `traceId` no log é o caminho.

**Custo de amostragem periódica:** o Prometheus coleta métricas por *scrape* a cada N segundos. Eventos que ocorrem e se resolvem entre dois scrapes podem não ser capturados. Para fenômenos de curta duração ou de ocorrência rara, logs e traces são mais confiáveis.

**Explosão de cardinalidade:** o risco mais grave na prática. Uma tag com alta cardinalidade pode multiplicar o número de séries temporais por ordens de magnitude, saturando memória e armazenamento. Monitorar a cardinalidade do sistema de métricas é parte da operação de observabilidade.

**Overhead de coleta:** timers e contadores em caminhos de alta frequência têm custo — criação de objetos, operações de incremento com sincronização, transmissão periódica. Em caminhos críticos de performance, o tipo de medidor e a estratégia de registro devem ser escolhidos com cuidado.

---

### 9. Padrões Relacionados

- **Log Aggregation** (Iluwatar, Richardson): métricas indicam *se* há um problema e *qual componente* está sofrendo; logs fornecem o contexto detalhado. Os dois são complementares e se referenciam pelo `traceId`.
- **Distributed Tracing** (Richardson): traces fornecem a visão de latência por span individual; métricas fornecem a visão agregada de latência por operação em janelas de tempo. Um SLO de latência é monitorado via métrica; uma violação específica é investigada via trace.
- **Health Check API** (MicroProfile): health checks respondem *se* o serviço está operacional (binário); métricas respondem *quão bem* o serviço está operando (contínuo). Ambos são necessários em produção.
- **Circuit Breaker**: o estado do disjuntor (fechado/aberto/meio-aberto) é uma métrica Gauge natural; o número de ativações é um Counter. Métricas tornam o comportamento do Circuit Breaker visível em dashboards operacionais.

---

## Parte II — Implementação no Quarkus

> Referência: [Quarkus — Micrometer Metrics Guide](https://quarkus.io/guides/telemetry-micrometer) | [Quarkus — Observability Guide](https://quarkus.io/guides/observability)

O Quarkus usa **Micrometer** como abstração de métricas — a abordagem recomendada pela plataforma. O Micrometer define uma API unificada para os quatro tipos de medidores e um `MeterRegistry` que abstrai o backend de destino: o mesmo código de instrumentação funciona com Prometheus, Datadog, InfluxDB ou qualquer outro backend suportado, alterando apenas a dependência Maven e a configuração.

---

### 10. Extensões Maven

```xml
<!--
    Micrometer core + integração Quarkus + exportação Prometheus.
    Expõe /q/metrics automaticamente no HTTP server principal.
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>

<!--
    Alternativa: exportação unificada via OTLP.
    Permite usar Micrometer para métricas e OTel para traces/logs
    com um único pipeline de saída — recomendado pela documentação Quarkus.
    Substitui quarkus-micrometer-registry-prometheus quando o OTel Collector
    já está no pipeline.
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-opentelemetry</artifactId>
</dependency>
```

**Prometheus vs. OTLP:** use `quarkus-micrometer-registry-prometheus` quando o stack de observabilidade já tem Prometheus como coletor de métricas e Grafana como visualização. Use `quarkus-micrometer-opentelemetry` quando o OTel Collector já está no pipeline — ele unifica métricas, traces e logs em um único protocolo OTLP, eliminando um coletor separado.

---

### 11. Configuração (`application.properties`)

```properties
# ─── Micrometer / Prometheus ──────────────────────────────────────────────────

# Endpoint de métricas exposto em /q/metrics (porta HTTP principal por padrão).
# Use management.port para expor em porta separada em produção.
quarkus.micrometer.export.prometheus.enabled=true

# Prefixo para todas as métricas da aplicação — distingue de métricas de infra
# quarkus.micrometer.export.prometheus.prefix=app

# Ignorar endpoints de health/metrics do próprio Quarkus para não poluir métricas HTTP
quarkus.micrometer.binder.http-server.ignore-patterns=/q/health.*,/q/metrics

# ─── Tags globais ─────────────────────────────────────────────────────────────
# Adicionadas automaticamente a todas as métricas — baixa cardinalidade garantida
# quarkus.micrometer.tags.application=${quarkus.application.name}
# quarkus.micrometer.tags.environment=${quarkus.profile}

# ─── OTLP (alternativa ao Prometheus) ─────────────────────────────────────────
# Quando usar quarkus-micrometer-opentelemetry, as métricas são enviadas
# pelo mesmo endpoint OTLP configurado para traces.
# quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317
```

---

### 12. `MeterRegistry` via CDI

O `MeterRegistry` é o ponto central de registro de todos os medidores. No Quarkus, é injetável diretamente via CDI — sem fábrica manual, sem configuração adicional:

```java
@ApplicationScoped
public class PedidoService {

    private final MeterRegistry meterRegistry;

    // Injeção via construtor — preferida para testabilidade
    public PedidoService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }
}
```

O Quarkus configura e gerencia o ciclo de vida do `MeterRegistry`. Quando múltiplos backends estão configurados (ex: Prometheus + OTLP), o Quarkus cria um `CompositeMeterRegistry` que replica cada medição para todos os backends registrados — sem alteração no código de instrumentação.

---

### 13. Métricas Automáticas do Quarkus

Com `quarkus-micrometer-registry-prometheus` instalado, o Quarkus instrumenta automaticamente, sem nenhuma linha de código adicional:

**Requisições HTTP (RESTEasy Reactive):**
```
http_server_requests_seconds_count{method, uri, status, outcome}
http_server_requests_seconds_sum{method, uri, status, outcome}
http_server_requests_seconds_max{method, uri, status, outcome}
```

**JVM:**
```
jvm_memory_used_bytes{area}               — memória heap e non-heap
jvm_gc_pause_seconds{action, cause}       — pausas de GC
jvm_threads_live_threads                  — threads ativas
jvm_classes_loaded_classes                — classes carregadas
```

**Pool de conexões (JDBC/Agroal):**
```
agroal_connection_pool_active_count{data_source}
agroal_connection_pool_available_count{data_source}
agroal_connection_pool_waiting_count{data_source}
```

**Netty (camada de rede do Vert.x):**
```
allocator_memory_used_bytes{allocator_type, memory_type}
allocator_pooled_allocations_total{allocator_type}
```

Essas métricas automáticas cobrem a operação da plataforma. As métricas de negócio e de código de aplicação são responsabilidade do desenvolvedor — e é onde o `LogInterceptor` da biblioteca entra.

---

### 14. Integração com a Biblioteca — `LogInterceptor`

> **Nota de ativação:** a instrumentação já está implementada no `LogInterceptor`, porém a emissão de métricas permanece desabilitada por padrão via configuração (`quarkus.micrometer.enabled=false`).

O `LogInterceptor` (anotação `@Logged`) emite automaticamente duas métricas para cada método interceptado, sem nenhum código adicional no desenvolvedor:

**`metodo.execucao` (Timer com histograma):**
```
metodo_execucao_seconds_count{classe, metodo}  — número de invocações
metodo_execucao_seconds_sum{classe, metodo}    — soma das durações
metodo_execucao_seconds_max{classe, metodo}    — máximo na janela decrescente
# com publishPercentileHistogram=true:
metodo_execucao_seconds_bucket{classe, metodo, le}  — histograma para cálculo de percentis
```

**`metodo.falha` (Counter por tipo de exceção):**
```
metodo_falha_total{classe, metodo, excecao}  — falhas por tipo de exceção
```

O par `metodo.execucao` + `metodo.falha` permite calcular taxa de erro por método diretamente no Prometheus:

```promql
# Taxa de erro do PedidoService.criar nos últimos 5 minutos
rate(metodo_falha_total{classe="PedidoService", metodo="criar"}[5m])
/
rate(metodo_execucao_seconds_count{classe="PedidoService", metodo="criar"}[5m])
```

O histograma habilitado via `publishPercentileHistogram()` permite calcular percentis no Prometheus — o que não é possível com percentis pré-calculados na aplicação, pois eles não são reagregáveis entre instâncias:

```promql
# p99 de latência do PedidoService.criar — agregado de todas as instâncias
histogram_quantile(0.99,
    sum by (le) (
        rate(metodo_execucao_seconds_bucket{classe="PedidoService", metodo="criar"}[5m])
    )
)
```

**Proteção contra falha de infraestrutura:** falhas do `MeterRegistry` (backend indisponível, timeout de exportação) são capturadas localmente e não propagam como exceção de negócio — conforme o princípio estabelecido no `CODING_STANDARDS.md`:

```java
} catch (Exception e) {
    try {
        meterRegistry.counter("metodo.falha",
            "classe",  classe,
            "metodo",  nomeMetodo,
            "excecao", e.getClass().getSimpleName()
        ).increment();
    } catch (Exception metricaFalhou) {
        // Falha de infraestrutura de observabilidade não interrompe o negócio
        log.warn("Falha ao registrar métrica: {}", metricaFalhou.getMessage());
    }
    throw e;
}
```

---

### 15. Criando Métricas de Negócio Customizadas

**Gauge — estado corrente**

O Gauge é o único medidor que *observa* um valor externo em vez de acumulá-lo. Ao contrário do Counter e do Timer, o código de instrumentação não "empurra" um valor para o registro — o Micrometer *puxa* o valor do objeto observado a cada scrape do Prometheus ou ciclo de exportação OTLP.

Isso tem uma consequência direta: **não existe conceito de "incrementar" ou "decrementar" um Gauge diretamente**. O valor do Gauge é sempre o que o objeto observado retorna no momento da leitura.

> **⚠️ Referência fraca — armadilha crítica em produção:** o Micrometer mantém apenas referência **fraca** ao objeto observado. Se nenhuma outra parte do código mantiver uma referência forte ao objeto, o GC pode coletá-lo e o Gauge passará a retornar `NaN` silenciosamente — sem exceção, sem log, sem alerta. **Sempre garanta que o bean CDI ou campo da classe mantenha a referência forte ao objeto instrumentado.**

Há três padrões de uso, cada um adequado a uma categoria diferente de estado a observar:

---

**Padrão 1 — Gauge observador de coleção (`gaugeCollectionSize` / `gaugeMapSize`)**

*Use quando: o estado a observar já é uma coleção ou mapa Java gerenciado pelo próprio bean.*

O `MeterRegistry` oferece atalhos de conveniência — `gaugeCollectionSize` e `gaugeMapSize` — que registram o Gauge e retornam a própria coleção instrumentada. Isso elimina a necessidade de declarar o Gauge separadamente e é o padrão mais idiomático quando a coleção é o estado canônico do bean.

```java
@ApplicationScoped
public class FilaProcessamento {

    // A referência deve ser mantida pelo bean — o Micrometer usa referência fraca ao objeto
    private final List<Pedido> pendentes;
    private final Map<String, Pedido> emProcessamento;

    public FilaProcessamento(MeterRegistry meterRegistry) {

        // gaugeCollectionSize: registra Gauge que lê Collection::size a cada scrape
        this.pendentes = meterRegistry.gaugeCollectionSize(
            "fila.pedidos.pendentes",
            Tags.of("etapa", "entrada"),
            new ArrayList<>()
        );

        // gaugeMapSize: equivalente para Map — lê Map::size a cada scrape
        this.emProcessamento = meterRegistry.gaugeMapSize(
            "fila.pedidos.processando",
            Tags.of("etapa", "execucao"),
            new ConcurrentHashMap<>()
        );
    }

    public void enfileirar(Pedido pedido) {
        pendentes.add(pedido);            // Gauge reflete o novo tamanho no próximo scrape
    }

    public void iniciarProcessamento(Pedido pedido) {
        pendentes.remove(pedido);
        emProcessamento.put(pedido.getId(), pedido);
    }

    public void concluir(Pedido pedido) {
        emProcessamento.remove(pedido.getId()); // Gauge reflete a remoção automaticamente
    }
}
```

> **Por que não usar `Gauge.builder` aqui:** `gaugeCollectionSize` é equivalente a `Gauge.builder("nome", colecao, Collection::size).register(registry)` mas retorna a própria coleção, permitindo atribuição direta ao campo. O padrão é mais conciso e deixa a intenção explícita no código.

---

**Padrão 2 — Gauge observador de objeto arbitrário (`Gauge.builder` com `ToDoubleFunction`)**

*Use quando: o estado a observar é um objeto de domínio ou de infraestrutura que expõe getters numéricos — pool de conexões, cache, objeto de configuração, cliente de API externa.*

O `Gauge.builder` aceita qualquer objeto e uma `ToDoubleFunction` que extrai o valor numérico a cada leitura. Um mesmo objeto pode ser observado por múltiplos Gauges, cada um extraindo uma propriedade diferente:

```java
@ApplicationScoped
public class CacheMetricas {

    // Referência forte ao cache — necessária porque o Gauge usa referência fraca
    private final Cache<String, Produto> cache;

    public CacheMetricas(Cache<String, Produto> cache, MeterRegistry meterRegistry) {
        this.cache = cache;

        // Tamanho atual do cache
        Gauge.builder("cache.produtos.tamanho", cache, Cache::estimatedSize)
            .description("Número estimado de entradas no cache de produtos")
            .register(meterRegistry);

        // Taxa de acerto — valor entre 0.0 e 1.0
        // stats() retorna CacheStats; hitRate() é um double — ToDoubleFunction<Cache>
        Gauge.builder("cache.produtos.hit.rate", cache, c -> c.stats().hitRate())
            .description("Taxa de acerto do cache de produtos (proporção de hits sobre total de acessos)")
            .register(meterRegistry);

        // Número de evicções acumuladas — observado como Gauge, não Counter,
        // porque o objeto cache já acumula internamente e não queremos dupla contagem
        Gauge.builder("cache.produtos.evicoes", cache, c -> c.stats().evictionCount())
            .description("Total de entradas removidas por evicção desde a inicialização")
            .register(meterRegistry);
    }
}
```

O mesmo padrão se aplica a objetos de infraestrutura gerenciados externamente, como um `DataSource` ou `ThreadPoolExecutor`:

```java
@ApplicationScoped
public class InfraMetricas {

    private final ThreadPoolExecutor executor;

    public InfraMetricas(ThreadPoolExecutor executor, MeterRegistry meterRegistry) {
        this.executor = executor;

        Gauge.builder("executor.tarefas.ativas", executor, ThreadPoolExecutor::getActiveCount)
            .description("Threads do pool ativamente executando tarefas")
            .register(meterRegistry);

        Gauge.builder("executor.fila.tamanho", executor, e -> e.getQueue().size())
            .description("Tarefas aguardando execução na fila do pool")
            .register(meterRegistry);

        Gauge.builder("executor.pool.tamanho", executor, ThreadPoolExecutor::getPoolSize)
            .description("Número atual de threads no pool, incluindo ociosas")
            .register(meterRegistry);
    }
}
```

> **Múltiplos Gauges sobre o mesmo objeto:** é correto e idiomático. Cada `Gauge.builder` registra uma série temporal independente com nome e tags próprios. O objeto (`executor`, `cache`) é compartilhado como referência — leve e sem custo extra.

---

**Padrão 3 — Gauge imperativo com `AtomicLong`**

*Use quando: o valor do Gauge é calculado por código de negócio em momentos específicos — não é derivado diretamente de uma estrutura em memória, mas de uma query ao banco, uma chamada a uma API externa, ou um cálculo periódico.*

O `AtomicLong` atua como suporte (*backing store*) do Gauge. O código de negócio chama `.set(valor)` para atualizar o estado; o Gauge lê o `AtomicLong` a cada scrape. O bean CDI mantém a referência forte ao `AtomicLong`, evitando a coleta pelo GC:

```java
@ApplicationScoped
public class PedidoEstadoMetricas {

    // AtomicLong como suporte — referência forte garantida pelo bean @ApplicationScoped
    private final AtomicLong pedidosPendentes    = new AtomicLong(0);
    private final AtomicLong pedidosEmAnalise    = new AtomicLong(0);
    private final AtomicLong pedidosBloqueados   = new AtomicLong(0);

    public PedidoEstadoMetricas(MeterRegistry meterRegistry) {

        Gauge.builder("pedidos.por.estado", pedidosPendentes, AtomicLong::get)
            .tag("estado", "PENDENTE")
            .description("Pedidos atualmente no estado PENDENTE")
            .register(meterRegistry);

        Gauge.builder("pedidos.por.estado", pedidosEmAnalise, AtomicLong::get)
            .tag("estado", "EM_ANALISE")
            .description("Pedidos atualmente em análise de fraude")
            .register(meterRegistry);

        Gauge.builder("pedidos.por.estado", pedidosBloqueados, AtomicLong::get)
            .tag("estado", "BLOQUEADO")
            .description("Pedidos bloqueados aguardando revisão manual")
            .register(meterRegistry);
    }

    // Chamado periodicamente por um job (@Scheduled) ou após eventos de mudança de estado
    public void sincronizarContadores(Map<String, Long> contagensPorEstado) {
        pedidosPendentes.set(contagensPorEstado.getOrDefault("PENDENTE",  0L));
        pedidosEmAnalise.set(contagensPorEstado.getOrDefault("EM_ANALISE", 0L));
        pedidosBloqueados.set(contagensPorEstado.getOrDefault("BLOQUEADO", 0L));
    }
}
```

O bean que popula o Gauge fica separado da fonte de dados — a atualização é disparada por um job agendado ou por um evento de domínio:

```java
@ApplicationScoped
public class PedidoEstadoSincronizador {

    private final PedidoRepository repository;
    private final PedidoEstadoMetricas metricas;

    @Scheduled(every = "30s")   // Quarkus Scheduler — atualiza os Gauges a cada 30 segundos
    public void sincronizar() {
        try {
            var contagens = repository.contarPorEstado(); // SELECT estado, COUNT(*) GROUP BY estado
            metricas.sincronizarContadores(contagens);
        } catch (Exception e) {
            // Falha de sincronização não interrompe o negócio — Gauge mantém último valor conhecido
            log.warn("Falha ao sincronizar métricas de estado de pedidos: {}", e.getMessage());
        }
    }
}
```

> **Por que não usar Counter aqui:** contagens de entidades em um estado específico *podem diminuir* — pedidos saem de `PENDENTE` quando são processados. Counter é monotônico por definição. O Gauge com `AtomicLong` é o instrumento correto para estados que oscilam.

> **Frequência de atualização:** o intervalo do job de sincronização deve ser compatível com o intervalo de scrape do Prometheus (padrão: 15s). Um job a cada 30s é razoável — evita pressão no banco e garante que o valor esteja razoavelmente atual a cada coleta.

---

**Padrão 4 — `MultiGauge` para múltiplas dimensões (observer)**

*Use quando: o mesmo fenômeno precisa ser reportado para vários conjuntos de tags ao mesmo tempo — como contagem de entidades por estado, por tipo ou por canal — e esses conjuntos são dinâmicos (surgem ou desaparecem com o tempo).*

O `MultiGauge` gerencia internamente um conjunto de séries temporais com a mesma métrica base e tags variáveis. Diferente de múltiplos `Gauge.builder` independentes com `AtomicLong`, o `MultiGauge` recebe os novos valores de uma só vez registrando novas `Row`s — cada `Row` é um par `(Tags, valor)`. Com `overwrite=true`, séries cujos estados desapareceram do resultado são removidas automaticamente, evitando "séries zumbi" no Prometheus.

```java
@ApplicationScoped
public class PedidoEstadoMetricas {

    private final MultiGauge pedidosPorEstado;

    public PedidoEstadoMetricas(MeterRegistry meterRegistry) {
        // Registra o MultiGauge — sem dimensões iniciais, apenas define o nome e descrição
        this.pedidosPorEstado = MultiGauge.builder("pedidos.por.estado")
            .description("Pedidos agrupados por estado atual")
            .register(meterRegistry);
    }

    // Chamado por @Scheduled ou após eventos de domínio com o resultado de
    //   SELECT estado, COUNT(*) FROM pedidos GROUP BY estado
    public void atualizar(Map<String, Long> contagensPorEstado) {
        var rows = contagensPorEstado.entrySet().stream()
            .map(e -> MultiGauge.Row.of(Tags.of("estado", e.getKey()), e.getValue()))
            .toList();

        // overwrite=true: remove séries de estados que sumiram do resultado
        pedidosPorEstado.register(rows, true);
    }
}
```

```java
@ApplicationScoped
public class PedidoEstadoSincronizador {

    private final PedidoRepository repository;
    private final PedidoEstadoMetricas metricas;

    @Scheduled(every = "30s")
    public void sincronizar() {
        try {
            metricas.atualizar(repository.contarPorEstado());
        } catch (Exception e) {
            log.warn("Falha ao sincronizar métricas de estado: {}", e.getMessage());
        }
    }
}
```

```promql
# Pedidos em estado PENDENTE agora
pedidos_por_estado{estado="PENDENTE"}

# Total por todos os estados — visão de distribuição de backlog
sum by (estado) (pedidos_por_estado)
```

> **`MultiGauge` vs. múltiplos `Gauge.builder` com `AtomicLong`:** prefira `MultiGauge` quando as dimensões são dinâmicas (novos estados podem surgir, estados vazios devem ser ocultados). Para dimensões estáticas e conhecidas em tempo de compilação (ex: dois ambientes fixos), múltiplos `Gauge.builder` são suficientes.

---

**Padrão 5 — `TimeGauge` para tempo decorrido desde um evento**

*Use quando: o valor a observar é uma duração — tempo desde o último heartbeat, idade da mensagem mais antiga em fila, tempo desde o último backup bem-sucedido.*

O `TimeGauge` é um Gauge semântico para valores temporais. Recebe uma `ToDoubleFunction` que retorna um valor numérico na `TimeUnit` especificada; o Micrometer converte automaticamente para a unidade esperada pelo backend (Prometheus usa segundos). Ao contrário de um Gauge genérico com valor em milissegundos exposto diretamente, o `TimeGauge` garante a conversão correta independente do backend.

```java
@ApplicationScoped
public class HeartbeatMetricas {

    // Referência forte — necessária porque TimeGauge usa referência fraca ao objeto
    private final AtomicLong ultimoHeartbeatEpoch;

    public HeartbeatMetricas(MeterRegistry meterRegistry) {
        this.ultimoHeartbeatEpoch = new AtomicLong(System.currentTimeMillis());

        // A função é avaliada a cada scrape do Prometheus — modelo pull/observer
        TimeGauge.builder(
                "heartbeat.ultima.ha",             // nome da métrica
                ultimoHeartbeatEpoch,              // objeto observado (referência forte)
                TimeUnit.MILLISECONDS,             // unidade do valor retornado pela função
                epoch -> System.currentTimeMillis() - epoch.get()  // função avaliada no scrape
            )
            .description("Tempo decorrido desde o último heartbeat bem-sucedido")
            .register(meterRegistry);
    }

    public void registrarHeartbeat() {
        ultimoHeartbeatEpoch.set(System.currentTimeMillis());
    }
}
```

```promql
# Alerta se o heartbeat não ocorre há mais de 60 segundos
heartbeat_ultima_ha_seconds > 60
```

O mesmo padrão serve para monitorar a "idade" da mensagem mais antiga em fila — útil para detectar filas presas sem consumidor ativo:

```java
@ApplicationScoped
public class FilaMensagensMetricas {

    private final BlockingQueue<Mensagem> fila;

    public FilaMensagensMetricas(BlockingQueue<Mensagem> fila, MeterRegistry meterRegistry) {
        this.fila = fila;

        // Tamanho da fila — Gauge observer puro: nenhum código de "set" necessário
        meterRegistry.gaugeCollectionSize("fila.mensagens.tamanho", Tags.empty(), fila);

        // Idade da mensagem mais antiga — avaliada a cada scrape
        TimeGauge.builder(
                "fila.mensagem.mais.antiga",
                fila,
                TimeUnit.MILLISECONDS,
                q -> {
                    var oldest = q.peek();
                    return oldest != null ? System.currentTimeMillis() - oldest.getTimestamp() : 0L;
                }
            )
            .description("Idade da mensagem mais antiga aguardando processamento")
            .register(meterRegistry);
    }
}
```

```promql
# Alerta se há mensagens esperando há mais de 5 minutos (300 segundos)
fila_mensagem_mais_antiga_seconds > 300
```

> **`TimeGauge` vs. Gauge com milissegundos hard-coded:** use sempre `TimeGauge` para durações. Prometheus espera segundos; um Gauge que exponha milissegundos diretamente produz alertas e dashboards com escala errada. O `TimeGauge` realiza a conversão automaticamente, tornando o código agnóstico ao backend de métricas.

---

**Resumo dos cinco padrões:**

| Padrão | Mecanismo | Quando usar |
|---|---|---|
| **Observador de coleção** | `gaugeCollectionSize` / `gaugeMapSize` | A coleção/mapa é o estado canônico do bean — nenhum código de "set" necessário |
| **Observador de objeto** | `Gauge.builder` + `ToDoubleFunction` | Objeto de domínio ou infra expõe getter numérico — cache, pool, executor |
| **Imperativo** | `Gauge.builder` + `AtomicLong` | Último recurso: valor calculado por código sem objeto observável direto |
| **Múltiplas dimensões dinâmicas** | `MultiGauge` + `Row.of(Tags, valor)` | Dimensões surgem e somem com o tempo — estados de entidade, categorias, tenants |
| **Duração desde evento** | `TimeGauge` + `ToDoubleFunction` | Tempo decorrido — heartbeat, idade de mensagem, tempo desde último backup |


**Counter — eventos de negócio:**

```java
@ApplicationScoped
public class PedidoService {

    private final MeterRegistry meterRegistry;

    public PedidoService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Pedido criar(NovoPedidoRequest request) {
        var pedido = repository.salvar(new Pedido(request));

        // Conta pedidos criados por canal — tags de baixa cardinalidade
        meterRegistry.counter("pedido.criado",
            "canal",  request.canal(),     // "checkout", "app", "api"
            "regiao", request.regiao()     // "sul", "sudeste", "norte"
        ).increment();

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
```

**Timer manual — operações sem `@Logged`:**

```java
public NotaFiscal emitirNotaFiscal(Pedido pedido) {
    // Timer com Sample para operações com código de resultado variável
    var sample = Timer.start(meterRegistry);

    try {
        var nota = apiSefaz.emitir(pedido);

        sample.stop(Timer.builder("nota.fiscal.emissao")
            .tag("resultado", "sucesso")
            .publishPercentileHistogram()
            .register(meterRegistry));

        return nota;

    } catch (SefazException e) {
        sample.stop(Timer.builder("nota.fiscal.emissao")
            .tag("resultado", "falha")
            .tag("codigo",    e.getCodigo())    // ex: "rejeicao_534", "timeout"
            .publishPercentileHistogram()
            .register(meterRegistry));
        throw e;
    }
}
```

**Gauge — estado corrente:**

```java
@ApplicationScoped
public class FilaProcessamento {

    private final Queue<Pedido> fila = new ConcurrentLinkedQueue<>();

    public FilaProcessamento(MeterRegistry meterRegistry) {
        // O Gauge observa o tamanho da fila — atualizado automaticamente a cada scrape
        Gauge.builder("fila.processamento.tamanho", fila, Queue::size)
            .description("Número de pedidos aguardando processamento")
            .register(meterRegistry);
    }

    public void enfileirar(Pedido pedido) {
        fila.offer(pedido);
    }
}
```

**Anotações declarativas (`@Timed`, `@Counted`):**

```java
@ApplicationScoped
public class RelatorioService {

    // Timer automático via interceptor CDI do Micrometer
    @Timed(value = "relatorio.geracao",
           extraTags = {"tipo", "vendas"},
           histogram = true,
           description = "Tempo de geração de relatório de vendas")
    public Relatorio gerarVendas(Periodo periodo) {
        // ...
    }

    // Counter automático via interceptor CDI do Micrometer
    @Counted(value = "relatorio.solicitacoes",
             extraTags = {"tipo", "estoque"})
    public Relatorio gerarEstoque(Periodo periodo) {
        // ...
    }
}
```

---

### 15.1. Padrão Monitor Externo

O Monitor Externo é um padrão de organização de código que responde a uma tensão real em sistemas instrumentados: **objetos de domínio de negócio não deveriam carregar referências ao sistema de observabilidade**.

Um `PedidoService` que injeta `MeterRegistry` apenas para emitir Gauges de estado está misturando duas responsabilidades que têm razões de mudança distintas — a lógica de processamento de pedidos e a estratégia de observabilidade do sistema. Se a equipe de plataforma decide mudar as tags de uma métrica, ou adicionar um novo Gauge, o objeto de domínio precisa ser alterado.

O padrão resolve isso com um bean CDI dedicado exclusivamente à observabilidade — um **monitor** que observa o objeto de domínio de fora, sem que o objeto saiba que está sendo monitorado.

---

**Fundamento conceitual — white-box vs. black-box monitoring**

A distinção vem do *SRE Book* (Beyer et al., cap. 10):

| Estratégia | O objeto instrumentado... | Acoplamento | Adequado para |
|---|---|---|---|
| **White-box** | emite suas próprias métricas — chama `meterRegistry` diretamente | Alto — objeto conhece o sistema de métricas | Infraestrutura técnica: pool, cache, executor, fila técnica |
| **Black-box (Monitor Externo)** | expõe apenas estado natural via getters — não sabe que está sendo observado | Zero — objeto ignora a existência de métricas | Domínio de negócio: `PedidoService`, `PagamentoService`, `EstoqueService` |

A regra prática: **se o objeto pertence ao domínio de negócio, use Monitor Externo**. Se pertence à camada de infraestrutura ou utilitários técnicos, white-box é aceitável — o objeto já é intrinsecamente técnico.

---

**Estrutura do padrão**

```
ObjetoDeDomínio          — lógica de negócio, estado, zero referência a métricas
MonitorExterno           — observabilidade, zero lógica de negócio
MeterRegistry            — infraestrutura, zero conhecimento dos dois acima
```

O `MonitorExterno` é um bean CDI `@ApplicationScoped` que recebe o objeto de domínio via injeção de construtor e registra os Gauges no `MeterRegistry` — usando o Padrão 2 (`Gauge.builder` com `ToDoubleFunction`) como mecanismo de implementação.

---

**Exemplo — `PedidoService` observado por `PedidoServiceMonitor`**

O objeto de domínio expõe apenas o estado que naturalmente já exporia — getters que fazem sentido para a lógica de negócio, independentemente de qualquer observabilidade:

```java
// Objeto de domínio — zero conhecimento de métricas
@ApplicationScoped
public class PedidoService {

    // Estado interno com acesso de leitura — exposto para o negócio, não para métricas
    private final ConcurrentLinkedDeque<Pedido> aguardandoPagamento = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Pedido> aguardandoEstoque   = new ConcurrentLinkedDeque<>();
    private final AtomicLong                    totalRecusados      = new AtomicLong(0);

    private final PedidoRepository repository;

    public PedidoService(PedidoRepository repository) {
        this.repository = repository;
    }

    public Pedido criar(NovoPedidoRequest request) {
        var pedido = repository.salvar(new Pedido(request));
        aguardandoPagamento.addLast(pedido);
        return pedido;
    }

    public void confirmarPagamento(Pedido pedido) {
        aguardandoPagamento.remove(pedido);
        aguardandoEstoque.addLast(pedido);
    }

    public void recusar(Pedido pedido, String motivo) {
        aguardandoPagamento.remove(pedido);
        totalRecusados.incrementAndGet();
    }

    public void despachar(Pedido pedido) {
        aguardandoEstoque.remove(pedido);
    }

    // Getters de estado — existem para o negócio, não para métricas
    public int  qtdAguardandoPagamento() { return aguardandoPagamento.size(); }
    public int  qtdAguardandoEstoque()   { return aguardandoEstoque.size(); }
    public long totalPedidosRecusados()  { return totalRecusados.get(); }
}
```

O monitor é um bean separado, sem nenhuma lógica de negócio. Sua única responsabilidade é conectar o estado do `PedidoService` ao `MeterRegistry`:

```java
// Monitor Externo — observabilidade pura, zero lógica de negócio
@ApplicationScoped
public class PedidoServiceMonitor {

    // Referência forte ao objeto monitorado — necessária porque Gauge usa referência fraca
    private final PedidoService pedidoService;

    public PedidoServiceMonitor(PedidoService pedidoService, MeterRegistry meterRegistry) {
        this.pedidoService = pedidoService;

        // Cada Gauge observa o PedidoService de fora, via ToDoubleFunction
        Gauge.builder("pedido.estado.aguardando_pagamento", pedidoService,
                      s -> s.qtdAguardandoPagamento())
            .description("Pedidos confirmados aguardando aprovação de pagamento")
            .register(meterRegistry);

        Gauge.builder("pedido.estado.aguardando_estoque", pedidoService,
                      s -> s.qtdAguardandoEstoque())
            .description("Pedidos com pagamento aprovado aguardando reserva de estoque")
            .register(meterRegistry);

        Gauge.builder("pedido.recusados.total", pedidoService,
                      s -> s.totalPedidosRecusados())
            .description("Total acumulado de pedidos recusados desde a inicialização")
            .register(meterRegistry);
    }
}
```

O `PedidoService` permanece idêntico com ou sem observabilidade. Para adicionar um novo Gauge, alterar tags ou remover uma métrica, somente o `PedidoServiceMonitor` é tocado.

---

**Extensão — Monitor com estado calculado periodicamente**

Quando o estado a observar não está em memória mas em uma fonte externa — banco de dados, API, cache distribuído — o Monitor Externo combina com um job agendado. O monitor mantém `AtomicLong` como suporte (Padrão 3) e os atualiza a partir da fonte:

```java
@ApplicationScoped
public class PedidoEstadoBancoDadosMonitor {

    private final AtomicLong pedidosPendentes  = new AtomicLong(0);
    private final AtomicLong pedidosBloqueados = new AtomicLong(0);

    private final PedidoRepository repository;

    public PedidoEstadoBancoDadosMonitor(PedidoRepository repository,
                                         MeterRegistry meterRegistry) {
        this.repository = repository;

        // Gauges observam os AtomicLong — referência forte mantida pelo bean @ApplicationScoped
        Gauge.builder("pedido.estado.pendente", pedidosPendentes, AtomicLong::get)
            .description("Pedidos no estado PENDENTE — atualizado a cada 30s")
            .register(meterRegistry);

        Gauge.builder("pedido.estado.bloqueado", pedidosBloqueados, AtomicLong::get)
            .description("Pedidos BLOQUEADOS aguardando revisão manual")
            .register(meterRegistry);
    }

    @Scheduled(every = "30s")
    void sincronizar() {
        try {
            // SELECT estado, COUNT(*) FROM pedidos GROUP BY estado
            var contagens = repository.contarPorEstado();
            pedidosPendentes.set(contagens.getOrDefault("PENDENTE",   0L));
            pedidosBloqueados.set(contagens.getOrDefault("BLOQUEADO", 0L));
        } catch (Exception e) {
            // Falha do monitor não afeta o negócio — Gauge mantém último valor conhecido
            log.warn("Falha ao sincronizar métricas de estado de pedidos do banco: {}",
                     e.getMessage());
        }
    }
}
```

> **Separação de responsabilidades no `@Scheduled`:** o job de sincronização pertence ao monitor, não ao repositório nem ao service. O repositório executa a query; o monitor decide quando executá-la e como mapear o resultado para métricas.

---

**Convenção de nomenclatura para monitores**

Para tornar a intenção explícita no código e facilitar a navegação:

| Objeto monitorado | Monitor externo |
|---|---|
| `PedidoService` | `PedidoServiceMonitor` |
| `FilaProcessamento` | `FilaProcessamentoMonitor` |
| `EstoqueService` | `EstoqueServiceMonitor` |

Todos os monitores são `@ApplicationScoped`, não carregam lógica de negócio, e têm um único ponto de entrada para alterações de observabilidade — o construtor onde os Gauges são registrados.

---

**Relação com os três padrões de Gauge**

O Monitor Externo não é um mecanismo do Micrometer — é um padrão de organização que se implementa sobre os mecanismos existentes:

| Mecanismo interno | Padrão de organização | Resultado |
|---|---|---|
| `gaugeCollectionSize` | White-box (objeto técnico) | Fila técnica auto-instrumentada |
| `Gauge.builder` + `ToDoubleFunction` | **Monitor Externo** | Domínio observado de fora |
| `Gauge.builder` + `AtomicLong` | **Monitor Externo** + `@Scheduled` | Estado de banco observado de fora |

---


### 16. Exportação Unificada via OTLP (`quarkus-micrometer-opentelemetry`)

Quando o OTel Collector já está no pipeline de telemetria, a extensão `quarkus-micrometer-opentelemetry` unifica métricas (Micrometer), traces e logs em um único protocolo OTLP — eliminando o endpoint Prometheus separado:

```xml
<!-- Substitui quarkus-micrometer-registry-prometheus -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-opentelemetry</artifactId>
</dependency>
```

```properties
# Todas as métricas, traces e logs saem pelo mesmo endpoint OTLP
quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317

# Métricas via OTLP — período de exportação (padrão: 60s)
# quarkus.otel.metric.export.interval=60s
```

Com essa configuração, o OTel Collector recebe os três sinais via OTLP e os roteia para os backends apropriados — Grafana Tempo para traces, Prometheus/Thanos para métricas, Loki para logs — sem nenhuma alteração no código da aplicação.

---

### 17. Diagrama de Fluxo — Ciclo de Vida de uma Métrica

```
Método de negócio (@Logged)
        │
        ▼
LogInterceptor.interceptar()
  ├─ Timer.start(meterRegistry)       ← cronômetro iniciado
  ├─ contexto.proceed()               ← execução do método
  │
  ├─ [se exceção]
  │    └─ meterRegistry.counter("metodo.falha", ...).increment()
  │
  └─ [finally]
       └─ Timer.stop(...)              ← duração registrada no MeterRegistry
            └─ publishPercentileHistogram() → histograma local acumulado
                │
                ▼
       MeterRegistry (em memória)
                │
                │  Pull (Prometheus scrape a cada ~15s)
                │  ou
                │  Push (OTLP export a cada ~60s)
                ▼
       Backend de coleta
         ├── Prometheus → Grafana (métricas isoladas)
         └── OTel Collector → Grafana Tempo + Grafana (métricas + traces + logs)

Correlação:
  Log emitido no mesmo método:
  └─ JSON inclui traceId + spanId → navegável para o trace no Jaeger/Tempo
  Métrica do mesmo método:
  └─ tags{classe, metodo} → filtrável no Grafana com o mesmo contexto
```

---

## Referências

**Documentação Quarkus:**
- [Quarkus — Micrometer Metrics Guide](https://quarkus.io/guides/telemetry-micrometer)
- [Quarkus — Observability in Quarkus](https://quarkus.io/guides/observability)
- [Quarkus — Micrometer and OpenTelemetry](https://quarkus.io/guides/telemetry-micrometer-to-opentelemetry)
- [Quarkus — Using OpenTelemetry](https://quarkus.io/guides/opentelemetry)
- [Quarkus — Observability Dev Services (LGTM)](https://quarkus.io/guides/observability-devservices-lgtm)

**Micrometer:**
- [Micrometer Concepts — Naming](https://docs.micrometer.io/micrometer/reference/concepts/naming)
- [Micrometer Concepts — Timers](https://docs.micrometer.io/micrometer/reference/concepts/timers)
- [Micrometer Concepts — Counters](https://docs.micrometer.io/micrometer/reference/concepts/counters)
- [Micrometer Concepts — Gauges](https://docs.micrometer.io/micrometer/reference/concepts/gauges)
- [Micrometer Concepts — Distribution Summaries](https://docs.micrometer.io/micrometer/reference/concepts/distribution-summaries)

**Padrões de microsserviços:**
- Chris Richardson — [Application Logging (microservices.io)](https://microservices.io/patterns/observability/application-logging.html)
- Chris Richardson — [Distributed Tracing (microservices.io)](https://microservices.io/patterns/observability/distributed-tracing.html)
- Iluwatar — [java-design-patterns: microservices-log-aggregation](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-log-aggregation)

**Observabilidade e SRE:**
- Charity Majors, Liz Fong-Jones, George Miranda — *Observability Engineering* (O'Reilly, 2022)
- Betsy Beyer, Chris Jones et al. — *Site Reliability Engineering* (Google, 2016) — capítulos de monitoramento e alertas
- Cindy Sridharan — *Distributed Systems Observability* (O'Reilly, 2018)
- [Google SRE Book — Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/)

**Ferramentas:**
- [Prometheus](https://prometheus.io/) — coleta de métricas e alertas
- [Grafana](https://grafana.com/) — dashboards e visualização
- [Grafana Loki](https://grafana.com/oss/loki/) — armazenamento e consulta de logs
- [Grafana Tempo](https://grafana.com/oss/tempo/) — armazenamento de traces
- [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) — pipeline de telemetria agnóstico de vendor