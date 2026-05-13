---
goal: Implementar métricas Micrometer no interceptor de logging da biblioteca lib-logging-quarkus
version: 1.1
date_created: 2026-03-28
last_updated: 2026-03-28
owner: br.com.vsjr.labs
status: 'In progress'
tags: [feature, observability, metrics, micrometer, quarkus, interceptor]
---

# Introduction

![Status: In progress](https://img.shields.io/badge/status-In%20progress-yellow)

Este plano implementa métricas de execução e falha para métodos anotados com `@Logged` na biblioteca `lib-logging-quarkus`, conforme a especificação de métricas. O plano é atômico, determinístico e executável por agente automatizado ou por desenvolvedor, com tarefas por fase, dependências explícitas e critérios mensuráveis de conclusão.

## 1. Requirements & Constraints

- **REQ-001**: Registrar `Timer` Micrometer `metodo.execucao` para toda invocação interceptada por `LogInterceptor.interceptar(...)`, incluindo sucesso e falha.
- **REQ-002**: Incrementar `Counter` Micrometer `metodo.falha` quando o método interceptado lançar exceção.
- **REQ-003**: Configurar `metodo.execucao` com `publishPercentileHistogram()` para suporte a `histogram_quantile` no Prometheus.
- **REQ-004**: Aplicar tags obrigatórias:
  - `metodo.execucao`: `classe`, `metodo`
  - `metodo.falha`: `classe`, `metodo`, `excecao`
- **REQ-005**: Injetar `MeterRegistry` por construtor em `LogInterceptor`.
- **REQ-006**: Manter compatibilidade do fluxo atual de MDC (`GerenciadorContextoLog.enriquecer(...)` e `limparEnriquecimento()`).
- **SEC-001**: Proibir dados de alta cardinalidade em tags (`userId`, `traceId`, `requestId`, ids de entidade).
- **SEC-002**: Tag `excecao` deve conter somente `Throwable.getClass().getSimpleName()`.
- **CON-001**: Falhas do `MeterRegistry` não podem interromper fluxo de negócio; capturar exceções e registrar `WARN`.
- **CON-002**: Iniciar medição antes de `contexto.proceed()` e finalizar no bloco `finally`.
- **CON-003**: Preservar configuração default com métricas desativadas (`quarkus.micrometer.enabled=false`).
- **CON-004**: Não incluir lógica de negócio em `LogInterceptor`.
- **GUD-001**: Usar nomes de métricas em lowercase com separação por ponto (`metodo.execucao`, `metodo.falha`).
- **GUD-002**: Reutilizar padrões de robustez já adotados no projeto para isolamento de falha de observabilidade.
- **PAT-001**: Padrão try/catch local em emissão de métrica para isolamento de falha de infraestrutura.
- **PAT-002**: Manter alinhamento entre contexto de log (`classe`, `metodo`) e tags de métrica.

## 2. Implementation Steps

### Implementation Phase 1

- GOAL-001: Preparar infraestrutura de código para instrumentação de métricas no interceptor sem alterar comportamento funcional atual.

Completion Criteria:
- `LogInterceptor` compila com `MeterRegistry` injetado por construtor.
- Fluxo MDC existente permanece intacto.
- Build local de módulo sem falhas de compilação.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-001 | Atualizar `lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability/interceptor/LogInterceptor.java`: adicionar import de `io.micrometer.core.instrument.MeterRegistry` e `io.micrometer.core.instrument.Timer`. | ✅ | 2026-03-28 |
| TASK-002 | Alterar assinatura do construtor de `LogInterceptor` para receber `MeterRegistry` (resolução opcional via `Instance<MeterRegistry>`) além de `GerenciadorContextoLog`. | ✅ | 2026-03-28 |
| TASK-003 | Adicionar campo final `meterRegistry` em `LogInterceptor` e atribuir no construtor. | ✅ | 2026-03-28 |
| TASK-004 | Validar que `pom.xml` já contém `quarkus-micrometer-registry-prometheus`; se ausente, adicionar em `lib-logging-quarkus/pom.xml`. | ✅ | 2026-03-28 |

### Implementation Phase 2

- GOAL-002: Implementar coleta de métricas de duração e falha em `LogInterceptor.interceptar(...)` com isolamento de falha.

Completion Criteria:
- `metodo.execucao` e `metodo.falha` são registrados conforme especificação.
- Exceções do `MeterRegistry` não propagam.
- Fluxo de exceção original de negócio permanece inalterado.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-005 | Em `LogInterceptor.interceptar(InvocationContext contexto)`, iniciar `Timer.Sample sample = Timer.start(meterRegistry)` imediatamente após `gerenciador.enriquecer(contexto)`. | ✅ | 2026-03-28 |
| TASK-006 | Implementar método privado `String resolverClasse(InvocationContext contexto)` em `LogInterceptor` para obter nome estável da classe alvo (usar `contexto.getMethod().getDeclaringClass().getSimpleName()` como fonte principal). | ✅ | 2026-03-28 |
| TASK-007 | Implementar método privado `String resolverMetodo(InvocationContext contexto)` retornando `contexto.getMethod().getName()`. | ✅ | 2026-03-28 |
| TASK-008 | Implementar método privado `void registrarFalha(InvocationContext contexto, Throwable erro)` registrando `meterRegistry.counter("metodo.falha", "classe", classe, "metodo", metodo, "excecao", erro.getClass().getSimpleName()).increment()` em bloco try/catch local com `WARN` em caso de falha de métrica. | ✅ | 2026-03-28 |
| TASK-009 | Implementar método privado `void registrarExecucao(InvocationContext contexto, Timer.Sample sample)` registrando `sample.stop(Timer.builder("metodo.execucao").tag("classe", classe).tag("metodo", metodo).publishPercentileHistogram().register(meterRegistry))` em bloco try/catch local com `WARN` em caso de falha de métrica. | ✅ | 2026-03-28 |
| TASK-010 | Atualizar bloco `try/catch/finally` de `interceptar(...)`: no `catch`, chamar `registrarFalha(...)` e relançar a exceção original; no `finally`, chamar `registrarExecucao(...)` e `gerenciador.limparEnriquecimento()`. | ✅ | 2026-03-28 |

### Implementation Phase 3

- GOAL-003: Definir configuração e documentação operacional para habilitação controlada de métricas.

Completion Criteria:
- Configuração de métricas permanece desativada por padrão.
- Caminho de habilitação documentado e reproduzível.
- Documentação do módulo atualizada sem ambiguidade.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-011 | Revisar `lib-logging-quarkus/src/main/resources/application.properties` e manter valores default: `quarkus.micrometer.enabled=false` e `quarkus.micrometer.export.prometheus.enabled=false`. | ✅ | 2026-03-28 |
| TASK-012 | Adicionar comentários de ativação explícita em `application.properties` para ambiente de teste/validação (`true` para micrometer e prometheus). | ✅ | 2026-03-28 |
| TASK-013 | Atualizar `lib-logging-quarkus/README.md` com seção "Métricas do LogInterceptor": nomes de métricas, tags e exemplo de consulta `/q/metrics`. |  |  |
| TASK-014 | Atualizar `lib-logging-quarkus/AGENTS.md` com checklist de manutenção das métricas (`metodo.execucao`, `metodo.falha`, cardinalidade de tags). |  |  |

### Implementation Phase 4

- GOAL-004: Implementar suíte de testes automatizados cobrindo sucesso, falha, fallback de métrica e endpoint de exposição.

Completion Criteria:
- Testes unitários e de integração passam localmente.
- Cobertura inclui todos os ramos relevantes de `LogInterceptor`.
- Critérios AC-001 a AC-008 da especificação mapeados em testes.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-015 | Criar `lib-logging-quarkus/src/test/java/br/com/vsjr/labs/observability/interceptor/LogInterceptorMetricsUnitTest.java` com `SimpleMeterRegistry` validando incremento de `metodo.execucao` em caso de sucesso. |  |  |
| TASK-016 | No mesmo teste, validar incremento de `metodo.falha` e `metodo.execucao` em caso de exceção lançada pelo método interceptado. |  |  |
| TASK-017 | Implementar cenário de falha de infraestrutura de métrica usando stub/mocking de `MeterRegistry` que lança exceção em `counter().increment()` e em `Timer.builder(...).register(...)`; validar que exceção de negócio original é preservada. |  |  |
| TASK-018 | Criar teste de integração Quarkus `lib-logging-quarkus/src/test/java/br/com/vsjr/labs/observability/interceptor/LogInterceptorMetricsIntegrationTest.java` para consultar `/q/metrics` e verificar presença de `metodo_execucao_seconds_count` e `metodo_falha_total`. | ✅ | 2026-03-28 |
| TASK-019 | Criar perfil de teste com métricas desativadas e validar que `@Logged` continua funcionando sem erro de injeção e sem quebrar logs. | ✅ | 2026-03-28 |
| TASK-020 | Executar `mvnw.cmd -f lib-logging-quarkus/pom.xml test` e registrar resultado no plano com data de conclusão das tarefas aprovadas. | ✅ | 2026-03-28 |

## 3. Alternatives

- **ALT-001**: Instrumentar com `@Timed` e `@Counted` em cada classe de aplicação.
  - Não escolhido porque dispersa responsabilidade e não garante cobertura uniforme de todos os métodos `@Logged`.
- **ALT-002**: Criar interceptor separado apenas para métricas.
  - Não escolhido para evitar sobreposição de interceptores e complexidade de ordem/prioridade CDI.
- **ALT-003**: Usar OpenTelemetry Metrics API diretamente.
  - Não escolhido porque Quarkus recomenda Micrometer para métricas de aplicação e já há dependência no módulo.
- **ALT-004**: Registrar tags com `traceId` para correlação direta.
  - Não escolhido por violar restrição de cardinalidade e risco de explosão de séries.

## 4. Dependencies

- **DEP-001**: `lib-logging-quarkus/pom.xml` com `io.quarkus:quarkus-micrometer-registry-prometheus`.
- **DEP-002**: CDI ativo via `io.quarkus:quarkus-arc` para construção/injeção de interceptor.
- **DEP-003**: `GerenciadorContextoLog` disponível e funcional para ciclo de vida de MDC.
- **DEP-004**: Ambiente de teste Quarkus com JUnit 5 e cliente HTTP Java (`java.net.http.HttpClient`).
- **DEP-005**: Stack de observabilidade local opcional (`containers-dev/docker-compose.yml`) para validação manual com Prometheus/Grafana.

## 5. Files

- **FILE-001**: `lib-logging-quarkus/src/main/java/br/com/vsjr/labs/observability/interceptor/LogInterceptor.java` — adicionar instrumentação de timer/counter e tratamento de falha.
- **FILE-002**: `lib-logging-quarkus/src/main/resources/application.properties` — garantir defaults de métricas desativadas e comentários de ativação.
- **FILE-003**: `lib-logging-quarkus/pom.xml` — confirmar dependência Micrometer Prometheus.
- **FILE-004**: `lib-logging-quarkus/src/test/java/br/com/vsjr/labs/observability/interceptor/LogInterceptorMetricsUnitTest.java` — novos testes unitários.
- **FILE-005**: `lib-logging-quarkus/src/test/java/br/com/vsjr/labs/observability/interceptor/LogInterceptorMetricsIntegrationTest.java` — novos testes de integração.
- **FILE-006**: `lib-logging-quarkus/README.md` — documentação de uso das métricas.
- **FILE-007**: `lib-logging-quarkus/AGENTS.md` — atualização de checklist operacional de agente.

## 6. Testing

- **TEST-001**: Sucesso síncrono: invocar método `@Logged` sem exceção e verificar `metodo.execucao` count = 1.
- **TEST-002**: Falha síncrona: invocar método `@Logged` com exceção e verificar `metodo.execucao` count = 1 e `metodo.falha` count = 1 com tag `excecao` correta.
- **TEST-003**: Isolamento de falha de métrica: simular erro de `MeterRegistry`; validar que o método de negócio mantém comportamento original.
- **TEST-004**: Exposição Prometheus: com métricas habilitadas em perfil de teste, validar presença textual de `metodo_execucao_seconds_count` em `/q/metrics`.
- **TEST-005**: Modo desativado: com `quarkus.micrometer.enabled=false`, validar que não há regressão no fluxo de `LogInterceptor`.
- **TEST-006**: Sanidade de cardinalidade: validar que tags emitidas são apenas `classe`, `metodo`, `excecao` (sem ids dinâmicos).
- **TEST-007**: Regressão de logs: validar que `gerenciador.limparEnriquecimento()` continua sendo executado no `finally`.

## 7. Risks & Assumptions

- **RISK-001**: Aumento de overhead em métodos de alta frequência por criação/lookup de timer.
- **RISK-002**: Nome de classe em proxies CDI pode gerar tag instável se resolução for feita via classe do proxy.
- **RISK-003**: Falsos negativos em testes de `/q/metrics` se endpoint não for acionado após geração de amostra.
- **RISK-004**: Divergência entre configuração local e pipeline CI para perfil de métricas habilitadas.
- **ASSUMPTION-001**: `MeterRegistry` está disponível por CDI quando extensão Micrometer estiver no classpath.
- **ASSUMPTION-002**: O endpoint `/q/metrics` seguirá convenção Prometheus da extensão Quarkus.
- **ASSUMPTION-003**: Não haverá mudança de contrato dos nomes de métricas definidos na especificação durante esta execução.
- **ASSUMPTION-004**: O padrão de tratamento de falha em observabilidade (não interromper negócio) é obrigatório para todas as tarefas deste plano.

## 8. Related Specifications / Further Reading

- `spec/spec-design-metrics-lib-logging-quarkus.md`
- `concepts/METRICS.md`
- `concepts/CODING_STANDARDS.md`
- `concepts/FIELD_NAMES.md`
- `lib-logging-quarkus/AGENTS.md`
- https://quarkus.io/guides/telemetry-micrometer
- https://quarkus.io/guides/telemetry-micrometer-to-opentelemetry
- https://docs.micrometer.io/micrometer/reference/concepts/timers.html