# Rastreamento Distribuído

> **Documentos relacionados:**
> - [Padrões de Codificação](CODING_STANDARDS.md) — padrões proibidos, incluindo geração manual de `traceId`

---

## Parte I — Conceitos

---

### 1. O Problema: Requisições sem Identidade

Em uma arquitetura de microsserviços, uma única operação do usuário — como fechar um pedido — pode percorrer dezenas de serviços distintos. Cada serviço emite seus próprios logs, mas esses registros ocorrem simultaneamente a milhares de outras requisições. Sem um identificador global compartilhado, reconstruir a jornada de um problema torna-se virtualmente impossível.

O cenário concreto sem rastreamento:

```
14:32:01.001  [pedidos-service]       ERROR  "Falha no pagamento da ordem ORD-9912"
14:32:01.003  [pagamentos-service]    ERROR  "Cartão recusado: USR-445"
14:32:01.240  [notificacoes-service]  INFO   "E-mail enviado para usuário desconhecido"
```

Essas três linhas pertencem à mesma requisição do mesmo usuário. Sem um identificador comum, não há como determinar isso automaticamente — a investigação depende de correlação manual por timestamps aproximados, o que em sistemas de alto volume é impraticável.

Com rastreamento distribuído:

```
14:32:01.001  [pedidos-service]       ERROR  traceId=7d2c  "Falha no pagamento da ordem ORD-9912"
14:32:01.003  [pagamentos-service]    ERROR  traceId=7d2c  "Cartão recusado: USR-445"
14:32:01.240  [notificacoes-service]  INFO   traceId=7d2c  "E-mail enviado para USR-445"
```

Uma única query por `traceId=7d2c` em qualquer agregador de logs reconstrói a história completa da requisição em todos os serviços.

> **Definição do padrão (Richardson — microservices.io):** cada instância de serviço registra informações sobre as requisições que processa usando um formato padrão. Cada requisição recebe um identificador único que é propagado por todos os serviços participantes, permitindo correlação completa de ponta a ponta.

---

### 2. O Rastreamento Distribuído nos Três Pilares da Observabilidade

O Rastreamento Distribuído é um dos três pilares da **Observabilidade** — a capacidade de entender o estado interno de um sistema a partir das saídas externas que ele gera, sem necessidade de instrumentação adicional para cada novo tipo de pergunta. Os três pilares são complementares e cada um responde a uma pergunta distinta:

| Pilar | Pergunta Central | Natureza do Dado |
|---|---|---|
| **Logging** | *"O que aconteceu em um momento específico?"* | Registros discretos de eventos com contexto e severidade |
| **Métricas** | *"Como o sistema está se comportando agora e quais são as tendências?"* | Valores numéricos agregados: CPU, memória, requisições por minuto, percentis de latência |
| **Tracing** | *"Por onde a requisição passou e por que demorou ou falhou?"* | Grafo temporal de operações encadeadas entre serviços |

O mnemônico que resume a interação entre os três: **Métricas dizem *se* há um problema; Traces dizem *onde* ele está; Logs dizem *qual* foi o erro exato.** O OpenTelemetry é a infraestrutura que une os três sinais em um único pipeline de telemetria, com correlação nativa via `traceId`.

> Operar sistemas distribuídos sem observabilidade é equivalente a conduzir um veículo com o painel apagado e o capô selado — o problema só se revela quando o sistema para completamente. *(Cindy Sridharan — Monitoring and Observability)*

---

### 3. Conceitos Fundamentais

#### 3.1. Trace

Um **Trace** representa o ciclo de vida completo de uma requisição externa — desde a ação do usuário até a resposta final, atravessando todos os serviços envolvidos. É identificado por um **Trace ID** globalmente único, gerado no ponto de entrada do sistema e propagado por todos os saltos de rede subsequentes.

Um trace é estruturalmente uma **árvore de Spans**. O Trace ID é a raiz que unifica essa árvore — constante ao longo de toda a operação distribuída.

#### 3.2. Span

O **Span** é a unidade básica de trabalho dentro de um trace. Cada operação significativa é representada por um Span independente: a execução de um endpoint HTTP, uma consulta ao banco de dados, uma chamada a um serviço externo, a execução de um método de negócio crítico.

Um Span registra obrigatoriamente:

| Campo | Descrição |
|---|---|
| `trace_id` | Identificador do trace raiz — mesmo valor em todos os spans da operação |
| `span_id` | Identificador único deste span dentro do trace |
| `parent_span_id` | Span pai que originou este (ausente apenas no Root Span) |
| `operation_name` | O que este span representa (ex: `POST /pedidos`, `SELECT orders`) |
| `start_time` | Timestamp UTC com precisão de nanosegundos |
| `end_time` | Timestamp UTC com precisão de nanosegundos |
| `duration_ms` | Derivado de `end_time - start_time` |
| `status` | `OK`, `ERROR` ou `UNSET` |
| `attributes` | Pares chave-valor com metadados da operação |

Além dos campos obrigatórios, Spans carregam:

- **Atributos (*Tags*):** pares chave-valor para busca e filtragem — URL, método HTTP, código de status, ID de entidade de negócio.
- **Eventos de Span:** mensagens associadas a momentos específicos dentro do Span, úteis para registrar exceções ou marcos relevantes no contexto exato onde ocorreram.

#### 3.3. Estrutura Hierárquica: Root Span e Child Spans

Os Spans formam uma **árvore**. O Span criado no ponto de entrada da requisição — geralmente o endpoint HTTP — é o **Root Span**. Cada operação subsequente cria um **Child Span** vinculado ao Span pai que o originou.

Essa hierarquia permite responder duas perguntas fundamentais:

- **Ordem de execução:** qual serviço chamou qual, em que sequência e com quais parâmetros.
- **Distribuição de latência:** a comparação entre a duração do Root Span e a soma dos Child Spans revela imediatamente onde o tempo está sendo consumido — banco de dados, serviço externo, processamento interno.

```
Root Span: POST /pedidos                          [0ms ──────────────────── 250ms]
  └─ Child Span: PedidoService.criar              [5ms ──────────────── 245ms]
       ├─ Child Span: EstoqueService.reservar     [10ms ──── 80ms]
       ├─ Child Span: PagamentoService.processar  [90ms ──────────── 200ms]
       │    └─ Child Span: GatewayClient.cobrar   [95ms ─────────── 195ms]
       └─ Child Span: notificacoes.enviar         [205ms ── 240ms]
```

Nesse exemplo, o gargalo é `GatewayClient.cobrar` — 100ms de 250ms totais, visível imediatamente sem necessidade de instrumentação adicional.

#### 3.4. Propagação de Contexto

Para que o rastreamento funcione entre serviços distintos, o Trace ID e o Span ID do pai precisam viajar junto com cada requisição. Esse mecanismo é a **Propagação de Contexto**.

O padrão de mercado é o **W3C TraceContext** (recomendação W3C, 2021), que define o formato do cabeçalho HTTP `traceparent`:

```
traceparent: 00-[traceId-128bits]-[parentSpanId-64bits]-01
              │   │                 │                    │
              │   └─ 32 hex chars   └─ 16 hex chars      └─ flags (01 = sampled)
              └─ versão do protocolo
```

O padrão W3C garante interoperabilidade entre implementações de diferentes fornecedores. Um serviço instrumentado com Quarkus/OTel pode propagar contexto para um serviço Node.js, Python ou .NET sem nenhuma configuração adicional — desde que todos respeitem o W3C TraceContext.

---

### 4. OpenTelemetry — O Padrão CNCF

O **OpenTelemetry** (OTel) é um projeto de código aberto mantido pela **CNCF** (*Cloud Native Computing Foundation*) que fornece APIs, SDKs e protocolos padronizados para instrumentar, gerar, coletar e exportar dados de telemetria — traces, métricas e logs.

Antes do OpenTelemetry, cada plataforma de observabilidade exigia sua própria biblioteca proprietária de instrumentação, criando *vendor lock-in* estrutural: migrar do Datadog para o New Relic implicava reescrever toda a instrumentação. O OTel eliminou esse problema — o código é instrumentado **uma única vez** usando o padrão aberto, e os dados podem ser roteados para qualquer plataforma compatível via configuração, sem alteração de código.

#### 4.1. Instrumentação

A instrumentação pode ocorrer em dois níveis:

**Automática:** em frameworks como o Quarkus, a extensão `quarkus-opentelemetry` instrumenta endpoints REST, clientes HTTP e operações de banco de dados automaticamente — sem nenhuma alteração no código de negócio. O Root Span da requisição e os Child Spans das chamadas downstream são criados e encerrados pelo framework.

**Manual (customizada):** quando a instrumentação automática não cobre uma operação relevante — um método de negócio crítico, uma etapa de processamento de lote, uma operação com semântica de negócio importante — o desenvolvedor pode usar a anotação `@WithSpan` ou a API `Tracer` diretamente para criar Spans adicionais com atributos de domínio.

#### 4.2. Protocolo OTLP e Pipeline de Coleta

O OpenTelemetry usa o protocolo **OTLP** (*OpenTelemetry Protocol*) para transmitir dados de telemetria da aplicação para o backend de análise. O pipeline típico:

```
Aplicação (OTel SDK)
        │
        │  OTLP (gRPC ou HTTP/Protobuf)
        ▼
OTel Collector  ──(filtragem, aggregation, tail sampling)──▶  Backend de Análise
                                                               (Jaeger / Grafana Tempo / Datadog)
```

Em ambientes de desenvolvimento, a aplicação pode exportar diretamente para o backend, dispensando o Collector intermediário. Em produção, o **OTel Collector é recomendado** pois centraliza políticas de filtragem, amostragem e roteamento — sem alterar o código da aplicação para mudar de backend ou ajustar política de coleta.

#### 4.3. Backends de Visualização

| Backend | Característica |
|---|---|
| **Jaeger** | Open-source, amplamente adotado em cloud-native; cronogramas detalhados e análise de causa raiz integrada |
| **Grafana Tempo** | Integração nativa com Loki (logs) e Prometheus (métricas) — unifica os três pilares em um único painel operacional |
| **Zipkin** | Alternativa madura, especialmente em ecossistemas Spring |
| **Elastic APM** | Integração natural com o stack ELK |
| **Datadog** | Plataforma de observabilidade gerenciada com correlação automática entre os três pilares |

---

### 5. `traceId` vs. `spanId` — Granularidades Complementares

Os dois identificadores operam em granularidades diferentes dentro da mesma árvore de execução:

| Identificador | Granularidade | Propósito |
|---|---|---|
| `traceId` | **Toda a transação** — atravessa bordas de múltiplos serviços | Correlacionar todos os spans de uma requisição de ponta a ponta; é o identificador de busca no Jaeger/Grafana Tempo |
| `spanId` | **Uma operação individual** — um método, uma query, uma chamada downstream | Identificar o nó exato da árvore onde ocorreu a falha ou o gargalo de latência |

O `traceId` é **constante** ao longo de toda a requisição distribuída. O `spanId` **muda a cada nova unidade de trabalho**, sempre referenciando o `spanId` do Span pai que o originou. Juntos, formam o par mínimo necessário para diagnóstico completo em ambiente distribuído — sem nenhum identificador adicional.

Ambos adotam o formato `camelCase` em conformidade com a saída nativa do OTel SDK e do JBoss Logging do Quarkus (`traceId`, `spanId`).

**Anti-padrão: `traceId` gerado manualmente**

Gerar `UUID.randomUUID()` e usá-lo como `traceId` cria um identificador que não existe em nenhuma árvore de rastreamento. Ele não correlaciona com nenhum span no Jaeger, não aparece em nenhum trace no Grafana Tempo e torna o campo completamente inútil para diagnóstico distribuído. O `traceId` deve sempre ser extraído do contexto OTel ativo.

---

### 6. Correlação com Logs — O Elo entre os Pilares

O rastreamento distribuído atinge seu potencial máximo quando os `traceId` e `spanId` estão presentes em **cada linha de log** emitida durante a execução. Essa correlação é o que transforma logs isolados em evidências de uma narrativa coerente.

```json
{
  "timestamp":   "2026-03-11T21:55:00.123Z",
  "level":       "ERROR",
  "message":     "Falha ao processar pagamento",
  "traceId":     "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":      "a3ce929d0e0e4736",
  "userId":      "joao.silva@empresa.com",
    "applicationName": "pagamentos-service",
  "log_motivo":  "Gateway recusou a transação",
  "detalhe_ordemId": "9912"
}
```

Com esses campos presentes, o fluxo de investigação de um incidente é:

1. Alerta dispara com base em métricas (taxa de erro acima de limiar).
2. O engenheiro abre o Jaeger e busca por traces com `status=ERROR` na janela de tempo.
3. Localiza o trace problemático pelo `traceId` e visualiza o grafo de spans — identifica que `GatewayClient.cobrar` retornou erro.
4. Clica no `spanId` específico e navega diretamente para os logs correlacionados em Kibana/Loki.
5. Os logs exibem o contexto completo do erro: usuário, ordem, código de gateway, stack trace.

O `traceId` é a chave que une o alerta de métrica, o grafo de trace e o detalhe do log — em uma única operação de investigação, sem varreduras manuais.

**Registro correto do Span ID nos logs:** quando um Child Span é criado para um método de negócio, o `spanId` nos logs subsequentes deve ser o do Child Span — não o do Root Span. Isso permite localizar exatamente qual operação dentro do grafo gerou cada linha de log.

---

### 7. Quando Utilizar

O padrão de rastreamento distribuído é necessário quando:

- Múltiplos serviços formam um único caminho de requisição e o diagnóstico de falhas exige visibilidade além das fronteiras de um serviço individual.
- A latência ponta-a-ponta de uma operação é crítica para o negócio e é necessário identificar qual componente contribui com qual parcela do tempo total.
- A correlação entre logs de serviços independentes é necessária para confirmar a saúde geral do sistema perante os usuários.
- Investigações de incidente precisam reconstruir a sequência causal de eventos em sistemas com alta taxa de requisições concorrentes.

---

### 8. Trade-offs

O rastreamento distribuído agrega maturidade diagnóstica significativa, mas exige atenção a ressalvas operacionais:

**Overhead transacional:** a coleta de traces adiciona esforço computacional — injeção de cabeçalhos em cada chamada HTTP, criação e encerramento de objetos Span, exportação via rede para o backend. Em implementações bem projetadas como o Dapper do Google, o overhead é mantido abaixo de 0,01% do throughput total — mas isso depende de amostragem adequada. Coletar 100% dos spans em sistemas de alto volume é impraticável.

> O paper Dapper (Google, 2010) documenta o requisito fundamental: o overhead deve ser suficientemente baixo para que a instrumentação possa ser habilitada em todos os serviços em produção, sem que os times precisem escolher entre observabilidade e performance.

**Complexidade de infraestrutura:** requer configuração e operação de tecnologias dedicadas — OTel Collector, backend de traces (Jaeger, Grafana Tempo), armazenamento de spans com retenção adequada. Em ecossistemas pequenos, o custo operacional pode superar o benefício diagnóstico.

**Gerenciamento de volume:** em sistemas de grande escala, gravar 100% dos spans satura o armazenamento. A solução é **Tail-Based Sampling** — a decisão de reter ou descartar um trace é tomada *após* o trace completar, garantindo que traces com erros sejam sempre retidos independente da taxa de amostragem de traces normais. O OTel Collector é o componente responsável por essa política; a aplicação emite 100% dos spans e o Collector aplica a filtragem sem alteração de código.

---

### 9. Padrões Relacionados

O rastreamento distribuído opera em conjunto com outros padrões de arquitetura de microsserviços:

- **Log Aggregation** (Iluwatar, Richardson): o rastreamento isolado tem valor limitado. Ele atinge máxima utilidade quando associado à agregação centralizada de logs — ao pesquisar por um `traceId` no Kibana ou Loki, o resultado é a história completa da requisição em todos os serviços.
- **API Gateway**: ponto de entrada único do sistema e responsável natural por criar o Root Span de cada requisição que chega do lado externo. O `traceId` gerado no Gateway propaga-se por todos os serviços internos.
- **Circuit Breaker**: usado em conjunto com rastreamento para mapear falhas em cascata — o grafo de spans expõe graficamente qual nó ativou o disjuntor e com que latência.
- **Saga**: o `traceId` persistido ao longo de uma transação distribuída mantém a rastreabilidade de cada etapa de compensação ou confirmação — essencial para auditoria e diagnóstico de transações de longa duração.

---

## Parte II — Implementação na Biblioteca Quarkus

> Padrão de referência: Chris Richardson — [Distributed Tracing (microservices.io)](https://microservices.io/patterns/observability/distributed-tracing.html)

Esta seção documenta a camada de rastreamento distribuído da `lib-logging-quarkus`. A implementação satisfaz os cinco requisitos do padrão microservices.io e integra-se ao `GerenciadorContextoLog` e ao `LogContextoFiltro` existentes — sem duplicar responsabilidades.

| Requisito microservices.io | Mecanismo | Componente |
|---|---|---|
| Geração automática de `traceId` e `spanId` | `quarkus-opentelemetry` auto-instrumenta endpoints JAX-RS | Nativo — sem código adicional |
| Propagação W3C TraceContext entre serviços | Cabeçalho `traceparent` via `quarkus-smallrye-context-propagation` | Nativo — sem código adicional |
| Registro de início, fim e metadados por span | CDI `@AroundInvoke` com `Tracer` OTel | `@Rastreado` + `RastreamentoInterceptor` |
| Exportação para backend configurável | `application.properties` + OTLP | Configuração — sem código adicional |
| `traceId` em cada linha de log | MDC populado pelo `GerenciadorContextoLog` | `LogContextoFiltro` (já existente) |

---

### 10. Estrutura do Projeto

Os componentes de tracing ficam em `tracing/` — separado do pacote `context/` de logging para preservar a separação de responsabilidades:

```
lib-logging-quarkus/
└── src/main/java/br/com/seudominio/log/
    ├── annotations/
    │   ├── Logged.java                    ← @InterceptorBinding CDI (logging)
    │   └── Rastreado.java                 ← @InterceptorBinding CDI (tracing)   ✦ novo
    ├── context/
    │   ├── LogContexto.java
    │   ├── GerenciadorContextoLog.java
    │   └── SanitizadorDados.java
    ├── core/
    │   └── LogEvento.java
    ├── dsl/
    │   ├── LogEtapas.java
    │   └── LogSistematico.java
    ├── filtro/
    │   └── LogContextoFiltro.java
    ├── interceptor/
    │   ├── LogInterceptor.java
    │   └── RastreamentoInterceptor.java   ← CDI @AroundInvoke + OTel Tracer     ✦ novo
    └── tracing/
        ├── EnriquecedorSpan.java          ← Interface do pipeline de enriquecimento  ✦ novo
        ├── EnriquecedorMetadados.java     ← Metadados técnicos (prioridade 10)       ✦ novo
        ├── EnriquecedorIdentidade.java    ← Identidade do usuário (prioridade 20)    ✦ novo
        └── GerenciadorRastreamento.java   ← Ciclo de vida do Span + MDC sync         ✦ novo
```

---

### 11. Dependências Maven

A API do OpenTelemetry já está disponível transitivamente via `quarkus-opentelemetry`. Nenhuma dependência adicional é necessária.

```xml
<!--
    Já presente no pom.xml — provê auto-instrumentação HTTP
    e expõe io.opentelemetry.api.trace.Tracer injetável via CDI.
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>

<!--
    Já presente no pom.xml — garante propagação do span OTel
    e do MDC em pipelines reativos Mutiny / Vert.x.
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-context-propagation</artifactId>
</dependency>
```

---

### 12. Configuração de Exportação (`application.properties`)

```properties
# ─── OpenTelemetry — Exportação de Traces ─────────────────────────────────────

# Endpoint do backend de análise (Jaeger via OTLP gRPC).
# Substitua pela URL do OTel Collector em produção para desacoplar o backend.
quarkus.otel.exporter.otlp.endpoint=http://jaeger:4317

# Protocolo de exportação: grpc (padrão) ou http/protobuf
# quarkus.otel.exporter.otlp.protocol=grpc

# Amostragem: always_on envia 100% dos spans para o Collector.
# Políticas de Tail-Based Sampling residem no OTel Collector — não na aplicação.
quarkus.otel.traces.sampler=always_on

# Nome do serviço: aparece como rótulo em todos os spans no Jaeger/Grafana Tempo.
# Deve ser único por microsserviço no ecossistema.
quarkus.application.name=pedidos-service

# ─── Backends alternativos ─────────────────────────────────────────────────────
# Zipkin       → quarkus.otel.exporter.otlp.endpoint=http://zipkin:9411/api/v2/spans
# OTel Collector → quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317
```

---

### 13. `@Rastreado` — Anotação CDI de Tracing

```java
package br.com.seudominio.log.annotations;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.*;

/**
 * Ativa rastreamento distribuído para um bean ou método CDI.
 *
 * <p>Quando aplicada, o {@link br.com.seudominio.log.interceptor.RastreamentoInterceptor}
 * cria um {@code Child Span} no span OTel ativo, registra metadados da operação
 * (classe, método, hora de início/fim) e propaga o {@code spanId} atualizado
 * para o MDC — mantendo a correlação com as linhas de log emitidas dentro
 * do método.</p>
 *
 * <p>Pode ser combinada com {@link Logged} no mesmo bean sem conflito:
 * {@code @Logged} gerencia o MDC de logging; {@code @Rastreado} gerencia
 * o span OTel. Quando usadas juntas, a ordem de execução é controlada
 * por {@code @Priority}: {@code RastreamentoInterceptor} executa primeiro
 * (cria o span), depois {@code LogInterceptor} (registra localização no MDC).</p>
 *
 * <pre>{@code
 * // Apenas tracing (sem métricas de duração Micrometer)
 * @ApplicationScoped
 * @Rastreado
 * public class IntegracaoFiscalClient { ... }
 *
 * // Tracing + Logging — interceptors acumulados
 * @ApplicationScoped
 * @Logged
 * @Rastreado
 * public class PagamentoService { ... }
 * }</pre>
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Rastreado {
}
```

---

### 14. `GerenciadorRastreamento` — Ciclo de Vida do Span

Centraliza a criação, enriquecimento e encerramento de spans. Separa a lógica OTel do interceptor, tornando cada responsabilidade testável de forma isolada. Implementa o padrão **Pipeline** via `Instance<EnriquecedorSpan>`: novos enriquecedores são descobertos automaticamente pelo CDI sem alteração nesta classe.

```java
package br.com.seudominio.log.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

import java.util.Comparator;

@ApplicationScoped
public class GerenciadorRastreamento {

    private static final String CAMPO_SPAN_ID = "spanId";

    Tracer tracer;
    Instance<EnriquecedorSpan> enriquecedores;

    public GerenciadorRastreamento(Tracer tracer, Instance<EnriquecedorSpan> enriquecedores) {
        this.tracer = tracer;
        this.enriquecedores = enriquecedores;
    }

    /**
     * Cria um Child Span e executa o pipeline de enriquecimento.
     *
     * Fluxo:
     *   1. Cria span filho a partir do contexto OTel ativo
     *   2. Torna o span filho corrente (scope)
     *   3. Atualiza o MDC com o spanId do filho
     *   4. Executa o pipeline de enriquecimento em ordem crescente de prioridade
     */
    public ContextoSpan iniciar(String nomeSpan, InvocationContext contexto) {
        var span = tracer.spanBuilder(nomeSpan)
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        var scope = span.makeCurrent();
        MDC.put(CAMPO_SPAN_ID, span.getSpanContext().getSpanId());

        enriquecedores.stream()
                .sorted(Comparator.comparingInt(EnriquecedorSpan::prioridade))
                .forEach(e -> e.enriquecer(span, contexto));

        return new ContextoSpan(span, scope);
    }

    /** Encerra o span e restaura o spanId do pai no MDC. */
    public void encerrar(ContextoSpan ctx, String spanIdPai) {
        try {
            ctx.scope().close();
            ctx.span().end();
        } catch (Exception e) {
            // Falha de infraestrutura OTel não deve interromper o fluxo de negócio
        } finally {
            if (spanIdPai != null) {
                MDC.put(CAMPO_SPAN_ID, spanIdPai);
            } else {
                MDC.remove(CAMPO_SPAN_ID);
            }
        }
    }

    /** Marca o span como ERROR e registra a exceção como evento do span. */
    public void marcarErro(ContextoSpan ctx, Throwable causa) {
        try {
            ctx.span().setStatus(StatusCode.ERROR, causa.getMessage());
            ctx.span().recordException(causa);
        } catch (Exception e) {
            // Falha de infraestrutura OTel não deve interromper o fluxo de negócio
        }
    }

    public record ContextoSpan(Span span, Scope scope) {}
}
```

---

### 15. `RastreamentoInterceptor` — CDI Interceptor de Tracing

Intercepta métodos anotados com `@Rastreado` e delega o ciclo de vida do span ao `GerenciadorRastreamento`. Executa antes do `LogInterceptor` via `@Priority` menor, garantindo que o `spanId` do Child Span já esteja no MDC quando o `LogInterceptor` registrar a localização do método.

```java
package br.com.seudominio.log.interceptor;

import br.com.seudominio.log.annotations.Rastreado;
import br.com.seudominio.log.tracing.GerenciadorRastreamento;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

/**
 * CDI Interceptor ativado por {@link Rastreado}.
 *
 * Ordem na cadeia de interceptors:
 *   RastreamentoInterceptor  [APPLICATION - 10] → cria span, atualiza spanId no MDC
 *   LogInterceptor           [APPLICATION]      → registra classe/metodo no MDC
 *   Método de negócio
 */
@Rastreado
@Interceptor
@Priority(Interceptor.Priority.APPLICATION - 10)
public class RastreamentoInterceptor {

    GerenciadorRastreamento gerenciador;

    public RastreamentoInterceptor(GerenciadorRastreamento gerenciador) {
        this.gerenciador = gerenciador;
    }

    @AroundInvoke
    public Object rastrear(InvocationContext contexto) throws Exception {
        var metodo     = contexto.getMethod();
        var classe     = metodo.getDeclaringClass().getSimpleName();
        var nomeMetodo = metodo.getName();
        var nomeSpan   = classe + "." + nomeMetodo;

        // Salva o spanId do pai antes de criar o Child Span para restaurar no finally
        var spanIdPai = (String) MDC.get("spanId");

        var contextoSpan = gerenciador.iniciar(nomeSpan, contexto);
        try {
            return contexto.proceed();
        } catch (Exception e) {
            gerenciador.marcarErro(contextoSpan, e);
            throw e;
        } finally {
            gerenciador.encerrar(contextoSpan, spanIdPai);
        }
    }
}
```

---

### 16. `EnriquecedorSpan` — Interface do Pipeline de Enriquecimento

Define o contrato para enriquecer spans com atributos OTel. O `GerenciadorRastreamento` descobre implementações via CDI (`Instance<EnriquecedorSpan>`) e as executa em ordem crescente de `prioridade()` — sem acoplamento a nenhuma implementação concreta.

```java
package br.com.seudominio.log.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.interceptor.InvocationContext;

/**
 * Contrato do pipeline de enriquecimento de spans.
 *
 * <p>Implementações são descobertas automaticamente pelo CDI via
 * {@code Instance<EnriquecedorSpan>} no {@link GerenciadorRastreamento}.
 * Novos atributos obrigatórios ou de negócio entram como novos beans
 * {@code @ApplicationScoped} sem alterar o núcleo da biblioteca.</p>
 */
public interface EnriquecedorSpan {

    /**
     * Enriquece o span com atributos OTel.
     *
     * @param span     span ativo no momento da interceptação
     * @param contexto contexto CDI; use {@code getParameters()} para acessar
     *                 os argumentos reais do método interceptado
     */
    void enriquecer(Span span, InvocationContext contexto);

    /**
     * Ordem de execução no pipeline — valor menor executa primeiro.
     * Padrão: {@link Integer#MAX_VALUE}.
     */
    default int prioridade() {
        return Integer.MAX_VALUE;
    }
}
```

**Bandas de prioridade convencionadas:**

| Faixa | Tipo | Implementações embutidas |
|---|---|---|
| 1–50 | Atributos técnicos obrigatórios | `EnriquecedorMetadados` (10), `EnriquecedorIdentidade` (20) |
| 100+ | Atributos de negócio — domínio da aplicação | Enriquecedores customizados do time |

---

### 17. Enriquecedores Embutidos

**`EnriquecedorMetadados` (prioridade 10)** — adiciona atributos técnicos via [OTel Code Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/code/):

| Atributo OTel | Valor |
|---|---|
| `service.name` | `${quarkus.application.name}` |
| `code.namespace` | Nome qualificado da classe interceptada |
| `code.function` | Nome do método interceptado |

**`EnriquecedorIdentidade` (prioridade 20)** — adiciona `enduser.id` quando a requisição não é anônima. Usa `SecurityIdentity` do Quarkus via `Instance<SecurityIdentity>` com guarda `isResolvable()` — nunca lança `ContextNotActiveException` fora de contextos HTTP.

---

### 18. Enriquecedor de Negócio Customizado

Enriquecedores de negócio implementam `EnriquecedorSpan` e são descobertos automaticamente pelo CDI. O `contexto.getParameters()` expõe os argumentos reais da invocação — útil para extrair identificadores visíveis no Jaeger/Grafana Tempo sem alterar o código de negócio.

```java
/**
 * Enriquecedor de negócio — captura operandos da operação.
 * Prioridade 100: executa após os enriquecedores de infraestrutura (10 e 20).
 */
@ApplicationScoped
public class EnriquecedorOperacao implements EnriquecedorSpan {

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        if (!"divide".equals(contexto.getMethod().getName())) return;

        var p = contexto.getParameters();
        if (p == null || p.length < 2) return;

        // Java 21: pattern matching com instanceof — sem cast explícito
        if (p[0] instanceof Double dividendo && p[1] instanceof Double divisor) {
            span.setAttribute("operacao.dividendo", dividendo.toString());
            span.setAttribute("operacao.divisor",   divisor.toString());
            span.setAttribute("operacao.risco",     divisor == 0.0);
        }
    }

    @Override
    public int prioridade() { return 100; }
}
```

**Atributos resultantes no span (visíveis no Jaeger/Grafana Tempo):**

| Atributo | Origem |
|---|---|
| `service.name` | `EnriquecedorMetadados` |
| `code.namespace` | `EnriquecedorMetadados` |
| `code.function` | `EnriquecedorMetadados` |
| `enduser.id` | `EnriquecedorIdentidade` |
| `operacao.dividendo` | `EnriquecedorOperacao` |
| `operacao.divisor` | `EnriquecedorOperacao` |
| `operacao.risco` | `EnriquecedorOperacao` |

---

### 19. Exemplos de Uso

**Caso 1 — Apenas `@Rastreado`: serviço de integração externa**

```java
@ApplicationScoped
@Rastreado  // Cria Child Span para cada método — sem métricas Micrometer
public class IntegracaoFiscalClient {

    public NotaFiscal emitir(Pedido pedido) {
        // RastreamentoInterceptor criou um span "IntegracaoFiscalClient.emitir"
        // vinculado ao traceId da requisição HTTP original.
        // Qualquer exceção é automaticamente marcada no span como ERROR.
        return chamarApiExterna(pedido);
    }
}
```

**Caso 2 — `@Logged` + `@Rastreado`: serviço de negócio crítico**

```java
@ApplicationScoped
@Logged     // LogInterceptor:          MDC com classe/método + métricas Micrometer
@Rastreado  // RastreamentoInterceptor: Child Span OTel + spanId atualizado no MDC
public class PagamentoService {

    public Pagamento processar(OrdemPagamento ordem) {
        // Span "PagamentoService.processar" criado e visível no Jaeger.
        // traceId e spanId (do filho) estão no MDC para todos os logs abaixo.

        LogSistematico
            .registrando("Pagamento iniciado")
            .em(PagamentoService.class, "processar")
            .comDetalhe("ordemId", ordem.getId())
            .comDetalhe("valor",   ordem.getValor())
            .info();
        // JSON inclui: traceId, spanId (Child Span), userId, applicationName

        return gateway.processar(ordem);
    }
}
```

**Caso 3 — Enriquecedor de negócio para atributos de domínio**

```java
@ApplicationScoped
public class EnriquecedorEstoque implements EnriquecedorSpan {

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        if (!"reservar".equals(contexto.getMethod().getName())) return;

        var p = contexto.getParameters();
        if (p != null && p.length >= 2
                && p[0] instanceof String sku
                && p[1] instanceof Integer quantidade) {
            span.setAttribute("estoque.sku",        sku);
            span.setAttribute("estoque.quantidade", String.valueOf(quantidade));
        }
    }

    @Override
    public int prioridade() { return 100; }
}
```

---

### 20. Diagrama de Fluxo — Requisição com `@Logged` + `@Rastreado`

```
Requisição HTTP recebida
        │
        ▼
LogContextoFiltro.filter(request)
  └─ GerenciadorContextoLog.inicializar(userId)
    └─ MDC: { traceId, spanId(root), userId, applicationName }
        │
        ▼
RastreamentoInterceptor.rastrear()          [Priority = APPLICATION - 10]
  └─ GerenciadorRastreamento.iniciar()
       ├─ Cria Child Span vinculado ao Root Span
       └─ MDC: { spanId atualizado para o filho }
        │
        ▼
LogInterceptor.interceptar()                [Priority = APPLICATION]
  └─ GerenciadorContextoLog.registrarLocalizacao(classe, metodo)
       └─ MDC: { classe, metodo }
        │
        ▼
Método de negócio executa
  └─ LogSistematico.registrando(...).info()
    └─ JSON: { traceId, spanId(filho), userId, applicationName, classe, metodo, ... }
        │
        ▼
LogInterceptor.finally
  └─ MDC.remove(classe, metodo)
        │
        ▼
RastreamentoInterceptor.finally
  └─ GerenciadorRastreamento.encerrar()
       ├─ Scope.close()  → restaura Root Span como corrente
       └─ Span.end()     → registra hora de término, exporta via OTLP
        │
        ▼
LogContextoFiltro.filter(response)
  └─ GerenciadorContextoLog.limpar()
       └─ MDC: {} (limpo)
```

**JSON de um log emitido dentro de um método `@Rastreado`:**

```json
{
  "timestamp":        "2026-03-24T14:32:00.847Z",
  "level":            "INFO",
  "message":          "Pagamento iniciado",
  "traceId":          "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":           "f9d3a1b2c4e56789",
  "userId":           "joao.silva@empresa.com",
    "applicationName":  "pagamentos-service",
  "log_classe":       "PagamentoService",
  "log_metodo":       "processar",
  "detalhe_ordemId":  "8821",
  "detalhe_valor":    "1249.90"
}
```

O `spanId` acima é o do Child Span criado pelo `RastreamentoInterceptor` — não o do Root Span da requisição HTTP. Isso permite localizar exatamente qual operação de negócio gerou cada linha de log no grafo do Jaeger.

---

## Referências

**Padrões de microsserviços:**
- Chris Richardson — [Distributed Tracing (microservices.io)](https://microservices.io/patterns/observability/distributed-tracing.html)
- Chris Richardson — [Application Logging (microservices.io)](https://microservices.io/patterns/observability/application-logging.html)
- Chris Richardson — [Exception Tracking (microservices.io)](https://microservices.io/patterns/observability/exception-tracking.html)
- Chris Richardson — [Audit Logging (microservices.io)](https://microservices.io/patterns/observability/audit-logging.html)
- Iluwatar — [java-design-patterns: microservices-log-aggregation](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-log-aggregation)
- Iluwatar — [java-design-patterns: microservices-distributed-tracing](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-distributed-tracing)

**Pesquisa e engenharia de sistemas:**
- Benjamin Sigelman et al. — [Dapper, a Large-Scale Distributed Systems Tracing Infrastructure](https://research.google/pubs/pub36356/) (Google, 2010) — paper seminal sobre rastreamento distribuído em larga escala
- Microsoft Research (2010) — *Characterizing Logging Practices in Open-Source Software*
- Anton Chuvakin — *Security Information and Event Management*

**Observabilidade e SRE:**
- Charity Majors, Liz Fong-Jones, George Miranda — *Observability Engineering* (O'Reilly, 2022)
- Betsy Beyer, Chris Jones et al. — *Site Reliability Engineering* (Google, 2016)
- Cindy Sridharan — *Distributed Systems Observability* (O'Reilly, 2018)
- Cindy Sridharan — [Monitoring and Observability](https://copyconstruct.medium.com/monitoring-and-observability-8417d1952e1c)
- James Turnbull — *The Art of Monitoring* (O'Reilly, 2018)

**Padrões e especificações:**
- [OpenTelemetry Specification](https://opentelemetry.io/docs/specs/)
- [W3C TraceContext Recommendation](https://www.w3.org/TR/trace-context/)
- [OTel Code Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/code/)
- [Quarkus 3.x — OpenTelemetry Guide](https://quarkus.io/guides/opentelemetry)
- [Elasticsearch Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/)

**Ferramentas:**
- [Jaeger](https://www.jaegertracing.io/) — backend de tracing distribuído open-source
- [Grafana Tempo](https://grafana.com/oss/tempo/) — armazenamento de traces escalável
- [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) — pipeline de telemetria agnóstico de vendor
- [Grafana Loki](https://grafana.com/oss/loki/) — armazenamento e consulta de logs
- [Prometheus](https://prometheus.io/) — coleta de métricas e alertas
- [Grafana](https://grafana.com/) — dashboards e visualização
- [Elasticsearch + Kibana (ELK)](https://www.elastic.co/) — indexação e busca de logs estruturados
- [Datadog](https://www.datadoghq.com/) — plataforma de observabilidade gerenciada