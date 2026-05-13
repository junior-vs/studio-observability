# Visão Geral da Arquitetura

> Este documento descreve a arquitetura conceitual e modular das bibliotecas de Logging Sistemático, suas responsabilidades e a forma como interagem entre si e com a infraestrutura de observabilidade.

---

## Contexto do Sistema

A biblioteca de Logging não é um processo separado (sidecar ou agente), mas uma dependência embarcada nas aplicações. Seu propósito é unificar a produção de JSON estruturado via `stdout`, entregando ao coletor de ambiente (Fluentd, OTel Collector, FluentBit) um fluxo de eventos com campos canônicos consistentes e contexto de correlação completo.

A responsabilidade da biblioteca termina no `stdout`. O escoamento via rede, roteamento para backends de armazenamento e políticas de retenção são responsabilidade da infraestrutura — não da aplicação.

```
┌──────────────────────────────────────────────────────────────────┐
│                        Serviço da Aplicação                      │
│                                                                  │
│  ┌─────────────────┐     ┌──────────────────────────────────┐   │
│  │  Lógica de      │────▶│        Camada de Logging         │   │
│  │  Negócio        │     │                                  │   │
│  └─────────────────┘     │  LogSistematico  (DSL pública)   │   │
│                          │  @Logged         (interceptor)   │   │
│  ┌─────────────────┐     │  GerenciadorContextoLog  (MDC)   │   │
│  │  Endpoints      │────▶│  SanitizadorDados  (segurança)   │   │
│  │  JAX-RS / HTTP  │     │                                  │   │
│  └─────────────────┘     └─────────────────┬────────────────┘   │
│                                            │ stdout (JSON)       │
└────────────────────────────────────────────┼─────────────────────┘
                                             │
                              ┌──────────────▼──────────────┐
                              │   Coletor de Ambiente        │
                              │  (OTel Collector / Fluentd)  │
                              └──────┬──────────────┬────────┘
                                     │              │
                     ┌───────────────▼──┐    ┌──────▼───────────────┐
                     │  Log Aggregator  │    │  Tracing / Metrics    │
                     │ (Elastic / Loki) │    │ (Jaeger / Prometheus) │
                     └──────────────────┘    └──────────────────────┘
```

---

## Os Três Módulos

O projeto entrega três artefatos independentes sobre um único padrão conceitual. O código de negócio não acessa frameworks diretamente — acessa apenas a API pública da DSL (`LogSistematico`, `@Logged`), que é idêntica nos três módulos.

| Módulo | Tecnologia interna | Destino |
|---|---|---|
| `lib-logging-slf4j` | SLF4J 2.x + Log4j2 + CDI Jakarta EE | Wildfly, TomEE, Payara, OpenLiberty |
| `lib-logging-quarkus` | JBoss Logging + ArC + OTel nativo | Quarkus 3.27, JVM ou native image GraalVM |
| `quarkus-logging-extension` | Quarkus Extension (deployment + runtime) | Quarkus 3.27 com zero configuração manual |

### `lib-logging-slf4j` — Biblioteca portável Jakarta EE

Destinada a containers imperativos tradicionais. Gera JSON via `log4j2.xml` + `JsonTemplateLayout` do Log4j2.

- **Logger:** `LoggerFactory.getLogger(classe)` — obtido internamente no momento da emissão; o desenvolvedor não instancia o logger.
- **MDC:** `org.slf4j.MDC` — gerenciado exclusivamente pelo `GerenciadorContextoLog` (CDI bean `@ApplicationScoped`). Chamadas diretas a `MDC.put()` fora da biblioteca são uma não conformidade.
- **Ativação do interceptor:** O `LoggingInterceptor` exige declaração explícita no `beans.xml` da aplicação cliente — o CDI Weld não descobre interceptores de JARs externos em silêncio. Sem essa entrada, o interceptor é ignorado sem erro.
- **Rastreabilidade:** Extrai `traceId` e `spanId` do span OTel ativo via `Span.current().getSpanContext()`. Se não houver span ativo (ex: job agendado sem instrumentação), os campos de trace são omitidos — sem gerar valores inválidos.

### `lib-logging-quarkus` — Biblioteca nativa Quarkus

Construída para compilação nativa (GraalVM) e compatibilidade total com cenários reativos.

- **Logger:** `Logger.getLogger(classe)` (JBoss Logging) — integração nativa com `quarkus-logging-json`, sem bridge de frameworks.
- **MDC:** `org.jboss.logging.MDC` — gerenciado pelo `GerenciadorContextoLog` CDI bean `@ApplicationScoped`. O ArC descobre o `LogInterceptor` automaticamente via `@Interceptor` no classpath — sem `beans.xml`.
- **Reatividade:** A troca de thread do Vert.x em pipelines Mutiny descarta silenciosamente o `ThreadLocal`. O `quarkus-smallrye-context-propagation` é obrigatório para garantir que MDC e span OTel sobrevivam à troca de thread — habilitado via `quarkus.arc.context-propagation.mdc=true`.
- **JSON:** Ativado por uma linha no `application.properties` (`quarkus.log.console.json=true`). Todos os campos do MDC aparecem automaticamente como chaves de primeiro nível no JSON de saída.
- **OTel:** Auto-instrumentação HTTP via `quarkus-opentelemetry` propaga o cabeçalho `traceparent` (W3C TraceContext) em chamadas de entrada e saída sem código manual.

### `quarkus-logging-extension` — Extensão Quarkus

Empacotada como extensão real com dois sub-artefatos Maven obrigatórios: `deployment` e `runtime`.

- **`deployment`:** Executa em build-time. Registra beans no ArC, gera hints de reflexão e proxy para GraalVM native image, e define os valores padrão de configuração (evitando que o consumidor precise escrever `application.properties` manual).
- **`runtime`:** É o JAR que vai no classpath da aplicação final. Expõe a mesma API pública da `lib-logging-quarkus` com zero configuração adicional.
- **Dev UI:** Integração com o painel `/q/dev` do Quarkus para inspeção de contexto em tempo de desenvolvimento.

A diferença fundamental em relação à biblioteca Quarkus é o split deployment/runtime: a biblioteca é um JAR comum descoberto em runtime; a extensão processa anotações e registra beans em build-time, resultando em startup mais rápido e native image sem configuração manual de reflexão.

---

## Responsabilidades por Camada

| Camada | Componente | Responsabilidade |
|---|---|---|
| **API pública** | `LogSistematico` | Ponto de entrada da DSL; valida sequência de chamadas em tempo de compilação via `sealed interfaces` |
| **API pública** | `@Logged` | Anotação CDI que ativa injeção automática de contexto e coleta de métricas de duração |
| **Contexto** | `GerenciadorContextoLog` | Ciclo de vida do MDC: inicialização, registro de localização, limpeza garantida no `finally` |
| **Contexto** | `LogContexto` | Snapshot imutável do contexto de correlação de uma requisição (`record` Java 21) |
| **Modelo** | `LogEvento` | Modelo imutável 5W1H que transporta o evento do builder até o emissor (`record` Java 21) |
| **Segurança** | `SanitizadorDados` | Mascaramento/redação automático por nome de chave antes de qualquer registro |
| **Infraestrutura** | `LoggingInterceptor` / `LogInterceptor` | CDI `@AroundInvoke`: popula MDC com localização técnica e coleta métricas de duração via Micrometer |
| **Infraestrutura** | Filtro HTTP | `ContainerRequestFilter`: inicializa e limpa o contexto de requisição no ciclo HTTP |

---

## Fluxo de Vida da Requisição

O diagrama abaixo ilustra como os componentes atuam em sequência durante o processamento de uma requisição HTTP:

```
1. Requisição chega (JAX-RS / RESTEasy Reactive)
      │
      ▼
2. Filtro HTTP (ContainerRequestFilter)
   ├─ OTel inicia span e propaga traceId/spanId do cabeçalho traceparent
   └─ GerenciadorContextoLog.inicializar(userId, servico)
      Popula MDC: userId, servico, traceId, spanId
      │
      ▼
3. Interceptor CDI (@Logged / @AroundInvoke)
   ├─ GerenciadorContextoLog.registrarLocalizacao(classe, metodo)
   │  Popula MDC: classe, metodo
   └─ Timer.start() — cronômetro de duração iniciado
      │
      ▼
4. Código de negócio
   └─ LogSistematico.registrando(...)
      ├─ SanitizadorDados mascara/redige campos sensíveis
      ├─ Popula MDC com log_classe, log_metodo, log_motivo, log_canal, detalhe_*
      ├─ Emite JSON para stdout (MDC + evento fundidos pelo formatador)
      └─ Remove campos do evento do MDC no finally interno
      │
      ▼
5. Retorno pelo interceptor (bloco finally)
   ├─ Timer.stop() → métrica registrada no Micrometer
   ├─ Counter de falha incrementado se houve exceção
   └─ MDC.remove(classe, metodo) + GerenciadorContextoLog.limpar()
      Remove todos os campos — thread devolvida ao pool limpa
      │
      ▼
6. Retorno pelo filtro HTTP (fase de resposta)
   └─ GerenciadorContextoLog.limpar() (segunda garantia para ambientes sem @Logged)
```

**Nota sobre jobs sem contexto HTTP:** quando `@Logged` é aplicado em beans acionados por scheduler ou mensageria, o filtro HTTP não é executado. O interceptor inicializa e limpa o MDC integralmente — esse comportamento é correto e intencional. O `traceId` e `spanId` só estarão presentes se o job estiver instrumentado com OTel.

---

## Princípios Arquiteturais

**Stdout como contrato de saída.** A biblioteca produz JSON estruturado para `stdout`. Roteamento para Elasticsearch, Loki, Datadog ou qualquer outro backend é responsabilidade do coletor de infraestrutura (OTel Collector, FluentBit, Fluentd) — não da aplicação.

**Falhas de observabilidade não interrompem o negócio.** Se o backend de tracing, o exportador OTel ou o `MeterRegistry` estiverem indisponíveis, a biblioteca registra a falha localmente e continua operando. A observabilidade nunca é motivo para falha de uma operação de negócio.

**API pública isolada da implementação.** O código de negócio acessa apenas `LogSistematico` e `@Logged`. A escolha entre `lib-logging-slf4j` e `lib-logging-quarkus` é transparente para o desenvolvedor — a migração entre módulos não exige alteração no código de negócio.

**Imutabilidade dos objetos de valor.** `LogEvento`, `LogContexto` e `AuditRecord` são `records` Java 21. Imutabilidade garante thread-safety estrutural sem sincronização e elimina erros de estado compartilhado em ambientes concorrentes.

**MDC como mecanismo de propagação.** O MDC é o único ponto de escrita de contexto. Chamadas diretas a `MDC.put()` fora do `GerenciadorContextoLog` constituem não conformidade — criam campos não canônicos e dificultam o rastreamento de vazamentos de contexto.

---

## Fora do Escopo

### Prefixos de configuração proprietários

Configurações como `observa4j.tracing.exporter` ou `observa4j.exceptions.reporter` não fazem parte do projeto. A plataforma usa as configurações padronizadas do ecossistema Quarkus (`quarkus.otel.exporter.otlp.endpoint`, `quarkus.log.console.json=true`).

### Integração direta aplicação–Logstash/GELF

A arquitetura de deployment em nuvem/Kubernetes orienta saídas de logging sempre para `stdout`. Transmitir logs diretamente via UDP para Graylog ou Logstash acopla a aplicação à infraestrutura de coleta e vai contra o modelo container-native. Esse escoamento é responsabilidade de DaemonSets e coletores externos.

### Módulos de observabilidade além do logging

Métricas (Prometheus), tracing (Jaeger/Zipkin) e exception tracking (Sentry) já são resolvidos pelas extensões nativas do Quarkus e pelo ecossistema OpenTelemetry. A biblioteca não é o ator principal desses pilares — ela produz os identificadores de correlação (`traceId`, `spanId`) que os unem, mas não os gerencia.