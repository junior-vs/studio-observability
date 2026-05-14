# Padrão de Observabilidade: Logging, Tracing e Métricas

> Biblioteca `lib-logging-quarkus` · Java 21 · Quarkus 3.20+

---

## Sumário

- [Parte I — Fundamentos](#parte-i--fundamentos)
  - [1. Observabilidade em Sistemas Distribuídos](#1-observabilidade-em-sistemas-distribuídos)
  - [2. O Log como Dado de Telemetria](#2-o-log-como-dado-de-telemetria)
  - [3. O Framework 5W1H — Anatomia do Evento Semântico](#3-o-framework-5w1h--anatomia-do-evento-semântico)
  - [4. Rastreamento Distribuído — Conceitos](#4-rastreamento-distribuído--conceitos)
  - [5. Métricas — Conceitos](#5-métricas--conceitos)
- [Parte II — OpenTelemetry e Pipeline de Telemetria](#parte-ii--opentelemetry-e-pipeline-de-telemetria)
  - [6. OpenTelemetry — O Padrão CNCF](#6-opentelemetry--o-padrão-cncf)
  - [7. Contexto Reativo — MDC, Vert.x e Mutiny](#7-contexto-reativo--mdc-vertx-e-mutiny)
- [Parte III — Implementação: Logging](#parte-iii--implementação-logging)
  - [8. Arquitetura da Biblioteca de Logging](#8-arquitetura-da-biblioteca-de-logging)
  - [9. Gerenciamento de Contexto via MDC](#9-gerenciamento-de-contexto-via-mdc)
  - [10. A DSL `LogSistematico` — Enforcement do 5W1H](#10-a-dsl-logsistematico--enforcement-do-5w1h)
  - [11. `@Logged` e `LogInterceptor`](#11-logged-e-loginterceptor)
  - [12. `SanitizadorDados` — Proteção de Dados Sensíveis](#12-sanitizadordados--proteção-de-dados-sensíveis)
  - [13. Eventos de Negócio, Auditoria e KEDB](#13-eventos-de-negócio-auditoria-e-kedb)
- [Parte IV — Implementação: Rastreamento Distribuído](#parte-iv--implementação-rastreamento-distribuído)
  - [14. Arquitetura da Camada de Tracing](#14-arquitetura-da-camada-de-tracing)
  - [15. `@Rastreado` e `RastreamentoInterceptor`](#15-rastreado-e-rastreamentointerceptor)
  - [16. `GerenciadorRastreamento` — Ciclo de Vida do Span](#16-gerenciadorrastreamento--ciclo-de-vida-do-span)
  - [17. Pipeline de Enriquecimento de Spans](#17-pipeline-de-enriquecimento-de-spans)
  - [18. Configuração de Exportação e Amostragem](#18-configuração-de-exportação-e-amostragem)
- [Parte V — Implementação: Métricas](#parte-v--implementação-métricas)
  - [19. Arquitetura de Métricas no Quarkus](#19-arquitetura-de-métricas-no-quarkus)
  - [20. Métricas Automáticas do Quarkus](#20-métricas-automáticas-do-quarkus)
  - [21. Métricas de Método via `@Logged`](#21-métricas-de-método-via-logged)
  - [22. Métricas de Negócio Customizadas](#22-métricas-de-negócio-customizadas)
  - [23. Padrões de Gauge](#23-padrões-de-gauge)
  - [24. Padrão Monitor Externo](#24-padrão-monitor-externo)
- [Parte VI — Correlação entre Pilares e Diagnóstico](#parte-vi--correlação-entre-pilares-e-diagnóstico)
  - [25. Fluxo de Investigação de Incidente](#25-fluxo-de-investigação-de-incidente)
  - [26. Padrões Relacionados de Microsserviços](#26-padrões-relacionados-de-microsserviços)
- [Parte VII — Governança e Operação](#parte-vii--governança-e-operação)
  - [27. Registro de Nomes de Campos Canônicos](#27-registro-de-nomes-de-campos-canônicos)
  - [28. Padrões Proibidos](#28-padrões-proibidos)
  - [29. Gestão de Níveis de Severidade](#29-gestão-de-níveis-de-severidade)
  - [30. Checklist de Code Review](#30-checklist-de-code-review)
  - [31. Ciclo de Melhoria Contínua](#31-ciclo-de-melhoria-contínua)
  - [32. Fora do Escopo](#32-fora-do-escopo)

---

## Parte I — Fundamentos

### 1. Observabilidade em Sistemas Distribuídos

#### 1.1. Do Monitoramento à Observabilidade

Monitoramento e observabilidade são frequentemente tratados como sinônimos, mas operam em níveis diferentes de maturidade diagnóstica.

**Monitoramento** é a prática de coletar e alertar sobre métricas conhecidas antecipadamente. É orientado a perguntas pré-definidas: "o serviço está de pé?", "o uso de CPU ultrapassou 80%?", "o número de erros HTTP 500 subiu?". Funciona bem quando as falhas são previsíveis e os limites são conhecidos.

**Observabilidade** é a capacidade de entender o estado interno de um sistema a partir das saídas externas que ele gera — sem necessidade de instrumentação adicional para cada novo tipo de pergunta. Um sistema observável permite que um engenheiro navegue de um sintoma desconhecido até a causa raiz usando os dados já coletados, sem precisar de redeploy, sem adicionar novo código de diagnóstico em produção, sem depender de quem escreveu o serviço.

> *Operar sistemas distribuídos sem observabilidade é equivalente a conduzir um veículo com o painel apagado e o capô selado — o problema só se revela quando o sistema para completamente.*
> — Cindy Sridharan, *Monitoring and Observability*

A distinção prática: monitoramento responde "algo está errado?"; observabilidade responde "por que está errado, exatamente, nesta instância, para este usuário, neste momento?".

Em arquiteturas de microsserviços, o monitoramento isolado se torna insuficiente. Uma única operação do usuário — fechar um pedido, por exemplo — pode atravessar dezenas de serviços. Uma falha silenciosa em `pagamentos-service` pode se manifestar como timeout em `pedidos-service` e como e-mail não enviado em `notificacoes-service`. Sem observabilidade, o sintoma e a causa estão em serviços diferentes, com logs isolados, sem fio condutor entre eles.

---

#### 1.2. Os Três Pilares e sua Interdependência

A observabilidade em sistemas distribuídos é sustentada por três categorias de dados de telemetria — denominadas os três pilares:

| Pilar | Pergunta Central | Natureza do Dado | Custo de Consulta |
|---|---|---|---|
| **Logging** | *"O que aconteceu em um momento específico?"* | Registros discretos de eventos com contexto e severidade | Alto — varredura de volume |
| **Métricas** | *"Com que frequência, volume e tendência?"* | Valores numéricos agregados em séries temporais | Baixo — pré-agregado |
| **Tracing** | *"Por onde a requisição passou e onde demorou?"* | Grafo temporal de operações encadeadas entre serviços | Médio — por `traceId` |

Os três pilares não são alternativas — são complementares. Cada um captura uma dimensão do comportamento do sistema que os outros dois não conseguem capturar com eficiência:

- **Logs** têm alta granularidade e carregam contexto rico, mas não foram projetados para responder perguntas de volume e tendência. Calcular a taxa de erros do último minuto a partir de logs exige varrer e agregar potencialmente milhões de registros em tempo real — operação com latência incompatível com alertas de produção.
- **Métricas** respondem a perguntas de volume e tendência em milissegundos, com custo computacional desprezível, porque são pré-agregadas na origem. Mas métricas agregam: um timer com p99 de dois segundos não diz *qual* requisição demorou dois segundos — apenas que 1% das requisições estão nessa faixa.
- **Traces** reconstroem o caminho de uma requisição individual por múltiplos serviços e revelam onde o tempo foi consumido, mas não substituem logs para diagnóstico de contexto detalhado nem métricas para análise de tendência em janelas de tempo.

A interdependência é estrutural: métricas disparam alertas; traces localizam o componente problemático; logs explicam o contexto exato da falha. Operar com apenas um ou dois pilares produz pontos cegos que só aparecem no pior momento — durante um incidente.

---

#### 1.3. O Mnemônico Operacional

A relação entre os três pilares pode ser resumida em uma frase operacional:

> **Métricas dizem *se* há um problema. Traces dizem *onde* ele está. Logs dizem *qual* foi o erro exato.**

Essa sequência não é apenas didática — é o fluxo real de uma investigação de incidente:

```
1. ALERTA DISPARA  (Prometheus / Grafana)
   └─ métrica: taxa de erro de PagamentoService.processar acima de 5%

2. ANÁLISE DE TENDÊNCIA  (Grafana)
   └─ histograma: p99 de latência subiu de 200ms para 2s nas últimas 2h
   └─ counter de falhas: tipo de exceção predominante é GatewayException

3. LOCALIZAÇÃO DO COMPONENTE  (Jaeger / Grafana Tempo)
   └─ traces com status=ERROR filtrados por serviço e janela de tempo
   └─ grafo de spans: GatewayClient.cobrar responde por 1,8s dos 2s totais

4. DIAGNÓSTICO DETALHADO  (Kibana / Loki)
   └─ filtrar por traceId do trace problemático
   └─ logs: userId, pedidoId, código de erro do gateway, stack trace completo
```

O `traceId` é a chave que torna essa navegação possível: presente em cada linha de log e em cada span, ele conecta os três pilares em uma única operação de investigação, sem varredura manual.

---

#### 1.4. Quando os Pilares se Tornam Necessários

Os três pilares tornam-se necessários à medida que a arquitetura aumenta em complexidade. A tabela abaixo descreve o limiar prático de cada adoção:

| Contexto | O que é suficiente | O que passa a ser necessário |
|---|---|---|
| Monolito único, baixo volume | Logs em texto, alertas básicos de infraestrutura | — |
| Múltiplos serviços, volume moderado | Logs estruturados com agregação centralizada | Métricas por serviço |
| Microsserviços, alto volume, requisições distribuídas | Logs estruturados + métricas | Tracing distribuído com `traceId` propagado |
| Microsserviços, produção crítica, SLOs definidos | Os três pilares integrados | Correlação entre pilares via `traceId` e Exemplars |

Em arquiteturas Quarkus sobre OKD, o ponto de inflexão ocorre quando uma única operação de negócio atravessa mais de dois serviços. A partir desse ponto, a ausência de tracing torna o diagnóstico de falhas intermitentes dependente de correlação manual por timestamp — impraticável em sistemas de alto volume de requisições concorrentes.

O OpenTelemetry é a infraestrutura que une os três sinais em um único pipeline de telemetria, com correlação nativa via `traceId`, sem vendor lock-in e sem alteração de código para trocar de backend. Sua adoção é o fundamento técnico sobre o qual as demais seções deste documento se constroem.

---

### 2. O Log como Dado de Telemetria

#### 2.1. Do Log Artesanal ao Log Semântico

O desenvolvedor tradicional trata o log como rastro de migalhas — `"Passou por aqui"`, `"Erro no banco"`, `"Iniciando processamento"`. Strings concatenadas em momentos de incerteza, escritas para o desenvolvedor que as escreveu, legíveis apenas com o código-fonte ao lado.

O desenvolvedor de observabilidade trata o log como dado de telemetria: um fato estruturado, registrado com contexto suficiente para ser interpretado por qualquer engenheiro, em qualquer momento, sem acesso ao código-fonte e sem o autor disponível para explicar.

> *Em arquiteturas distribuídas, o log não é uma string — é um fato registrado. Cada evento deve possuir contexto suficiente para ser reconstruído sem a necessidade de depuração local.*

A diferença não é estética. É operacional. Considere o mesmo evento registrado das duas formas:

```java
// Log artesanal — rastro de migalhas
log.error("Erro ao salvar pedido " + pedidoId + " para o user " + userId
          + " devido a falha no banco");
```

```json
{
  "timestamp":        "2026-03-11T21:55:00.123Z",
  "level":            "ERROR",
  "message":          "Falha na persistência de pedido",
  "traceId":          "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":           "a3ce929d0e0e4736",
  "userId":           "joao.silva@empresa.com",
  "applicationName":  "pedidos-service",
  "log_classe":       "PedidoService",
  "log_metodo":       "salvar",
  "log_motivo":       "Timeout na conexão com banco",
  "detalhe_pedidoId": "4821"
}
```

O primeiro é uma string. O segundo é um documento indexável com dez campos consultáveis de forma independente. A query `level: ERROR AND detalhe_pedidoId: "4821" AND @timestamp:[now-1h TO now]` retorna o evento exato em milissegundos, de qualquer agregador, sem regex.

---

#### 2.2. JSON como Formato Mandatório

Texto puro exige expressões regulares complexas e frágeis para extrair informação. Uma alteração no formato da mensagem — um espaço a mais, um campo reordenado, uma tradução de label — quebra silenciosamente o parser e corrompe dashboards e alertas sem nenhum erro visível.

JSON resolve esse problema de forma direta: cada dimensão do evento é um campo nomeado, tipado e indexável. Elasticsearch, Loki e Datadog ingeram JSON nativamente — sem configuração de parser, sem regex, sem manutenção de padrões de texto.

| Abordagem | Consultável? | Parseável nativamente? | Indexação consistente? |
|---|---|---|---|
| `System.out.println("Erro id " + id)` | Não | Não | Não |
| `log.info("Pedido {} processado", id)` | Não | Não | Não |
| `LogSistematico` (JSON estruturado) | Sim | Sim | Sim |

A diferença em uma query real:

```
# Texto puro — impossível sem regex frágil e manutenção constante
message: /Erro ao processar pedido [0-9]+/

# JSON estruturado — query direta, robusta, composta
level: ERROR AND detalhe_pedidoId: "4821" AND @timestamp:[now-1h TO now]
```

No Quarkus, a saída JSON é habilitada com uma única propriedade:

```properties
quarkus.log.console.json=true
```

Todos os campos do MDC aparecem automaticamente como chaves de primeiro nível no JSON. Nenhuma configuração adicional é necessária para os campos da biblioteca.

---

#### 2.3. O Log como Fluxo Append-Only

O log é uma sequência ordenada de registros imutáveis. Cada evento é acrescentado ao final — nunca modificado ou removido retroativamente. Essa característica tem três implicações diretas sobre como o sistema deve ser projetado:

**Fonte da verdade.** Um banco de dados registra o estado *atual* de uma entidade. O log registra cada *mudança de estado* ao longo do tempo. Um log completo permite reconstruir o estado de qualquer entidade em qualquer ponto do passado sem consultar o banco. Eventos como `ORDER_CREATED`, `PAYMENT_APPROVED`, `ORDER_DISPATCHED` formam uma linha do tempo auditável da entidade.

**Imutabilidade como contrato.** Alterar ou deletar um registro de log — mesmo para "corrigir" uma mensagem — viola o contrato append-only e pode comprometer investigações de segurança, disputas técnicas e conformidade regulatória (LGPD, SOC 2). Pipelines de coleta devem ser configurados para tratar logs como write-once.

**Fingerprinting por consistência.** Para que ferramentas como Kibana agrupem automaticamente milhares de ocorrências do mesmo tipo de evento em uma única métrica de tendência, o campo `message` deve ser idêntico para o mesmo tipo de evento. Informações variáveis — IDs, valores, timestamps — pertencem a campos estruturados, nunca interpoladas na mensagem:

```java
// PROIBIDO — message varia por requisição, fingerprinting impossível
log.error("Falha ao processar pedido 4821 para joao.silva@empresa.com");

// CORRETO — message estável, dados variáveis em campos estruturados
LogSistematico
    .registrando("Falha na persistência de pedido")
    .em(PedidoService.class, "salvar")
    .comDetalhe("pedidoId", pedidoId)
    .erro(e);
```

---

#### 2.4. Agregação Centralizada como Pré-Requisito

Em microsserviços, cada instância gera seu próprio fluxo de logs em stdout. Uma única operação distribuída produz eventos em N serviços diferentes, em N containers distintos, potencialmente em N pods do OKD. Sem agregação centralizada, os logs de uma operação ficam espalhados — o diagnóstico se torna impraticável independente da qualidade dos eventos individuais.

A arquitetura de coleta recomendada para Quarkus em OKD:

```
Aplicação (stdout — JSON)
        │
        ▼
FluentBit / OTel Collector     ← coleta, enriquece com metadados de pod/namespace
        │
        ├──▶ Loki              ← armazenamento e consulta de logs (Grafana)
        └──▶ Elasticsearch     ← indexação e busca full-text (Kibana)
```

Dois princípios são inegociáveis nessa arquitetura:

**A aplicação emite para stdout — nunca para arquivo, nunca para o coletor diretamente.** Containers descartáveis não gerenciam arquivos. A governança de armazenamento e retenção é responsabilidade da plataforma de orquestração e do coletor. Configurar escrita em arquivo ou rotação de logs no container viola o modelo container-native e cria dependência de estado local que não sobrevive a restarts.

**O transporte usa SSL/TLS em todos os segmentos.** Logs transmitidos em texto claro entre o container e o coletor podem expor campos de contexto — incluindo campos de identidade não mascarados que escaparam ao `SanitizadorDados`. O pipeline FluentBit → Loki/Elasticsearch deve usar TLS de ponta a ponta.

---

### 3. O Framework 5W1H — Anatomia do Evento Semântico

> O framework 5W1H atua como uma restrição de engenharia que garante que cada evento possua contexto suficiente para ser reconstruído sem necessidade de depuração local.

Cada evento de log deve responder a seis perguntas de engenharia. A tabela abaixo mapeia cada dimensão ao seu atributo técnico e à sua fonte de dados na plataforma:

| Dimensão | Pergunta de Engenharia | Atributo Técnico | Fonte de Dados |
|---|---|---|---|
| **Who** | Quem é o ator? Quem desencadeou a ação? | `userId`, `applicationName` | `SecurityIdentity` / JWT / `quarkus.application.name` |
| **What** | Qual fato ou transição de estado ocorreu? | `message`, `detalhe_eventType` | DSL `.registrando()` / Código de Negócio |
| **When** | Qual a posição precisa na linha do tempo? | `timestamp` (UTC/ISO 8601) | Runtime / Clock sincronizado via NTP |
| **Where** | Em que serviço, classe e fluxo? | `traceId`, `spanId`, `log_classe`, `log_metodo` | OTel SDK / MDC / Interceptores CDI |
| **Why** | Qual a motivação ou causa de negócio? | `log_motivo`, `detalhe_errorCode` | Lógica de domínio via `.porque()` |
| **How** | Por qual canal o evento chegou ao sistema? | `log_canal` | Camada de entrada via `.como()` |

As dimensões *Who*, *When* e *Where* de correlação (`traceId`, `spanId`, `userId`, `applicationName`, `timestamp`) são injetadas automaticamente pela infraestrutura via MDC — o desenvolvedor não as declara. As dimensões *What*, *Where* técnico, *Why* e *How* são declaradas explicitamente na DSL.

---

#### 3.1. Who — Identidade e Anonimato Controlado

A dimensão *Who* responde: **quem está envolvido neste evento?**

O *Who* é o que separa uma falha sistêmica — que afeta todos os usuários — de um erro pontual isolado a um único cliente. Em uma investigação pós-incidente, a ausência do `userId` transforma horas de diagnóstico em varredura cega.

Em sistemas Quarkus, o *Who* opera em dois níveis:

**Identidade do ator humano** — extraída do `SecurityIdentity`. Essencial para auditoria LGPD e resolução de disputas técnicas. Quando não há usuário autenticado (chamadas de sistema, jobs agendados), o campo recebe o valor `"anonimo"` — nunca é omitido nem preenchido com `null`. Isso garante que queries como `userId: "anonimo"` isolem operações de sistema de operações de usuário, sem produzir resultados nulos que quebram agregações.

**Identidade do serviço** — o campo `applicationName`, preenchido automaticamente a partir de `quarkus.application.name`. Em um agregador que consolida logs de dezenas de microsserviços, esse campo é o que permite distinguir qual serviço gerou qual evento:

```json
{
  "userId":          "joao.silva@empresa.com",
  "applicationName": "pedidos-service"
}
```

Em comunicações service-to-service, o `applicationName` do serviço chamador propaga-se via `traceparent` — o `traceId` identifica a requisição originada pelo usuário, mesmo quando o serviço atual não tem acesso direto à identidade do usuário original.

---

#### 3.2. What — Descrição Factual do Evento

A dimensão *What* responde: **o que exatamente aconteceu?**

É a dimensão mais visível do log — o `message` aparece em primeiro lugar no Kibana e no Grafana Loki. Mensagens vagas são os maiores inimigos do MTTR: `"Erro no processamento"` pode corresponder a centenas de eventos distintos sem nenhuma pista sobre o que falhou.

**Regras para mensagens:**

- Usar linguagem factual no passado. `"Pedido criado"`, `"Login falhou"`, `"Pagamento recusado"` são fatos. `"Criando pedido..."` é uma intenção que pode nunca ter sido concluída.
- Manter o texto da `message` estável entre ocorrências do mesmo tipo de evento. Informações variáveis — IDs, valores, timestamps — pertencem a campos estruturados via `.comDetalhe()`, nunca interpoladas na mensagem. Isso habilita fingerprinting automático no Kibana.
- Não duplicar informações já presentes em campos estruturados.

```java
// PROIBIDO — vago, sem fingerprinting possível
LogSistematico.registrando("Erro no processamento")...

// CORRETO — específico, message estável, dados variáveis em campos dedicados
LogSistematico
    .registrando("Falha ao processar pagamento")
    .em(PagamentoService.class, "processar")
    .comDetalhe("pedidoId", pedidoId)
    .erro(e);
```

**Eventos técnicos vs. eventos de negócio**

O *What* abrange dois tipos:

- **Eventos técnicos** — falhas de integração, exceções, estados de fluxo interno. Destinados a engenheiros e SRE.
- **Eventos de negócio** — `ORDER_COMPLETED`, `CHECKOUT_STARTED`, `PAYMENT_FAILED`. Destinados também a times de produto e analytics. Devem incluir o campo canônico `detalhe_eventType` para serem identificáveis como categoria distinta nas ferramentas de observabilidade, sem depender de parse do campo `message`.

```java
LogSistematico
    .registrando("Pedido concluído")
    .em(PedidoService.class, "concluir")
    .comDetalhe("eventType",   "ORDER_COMPLETED")
    .comDetalhe("pedidoId",    pedido.getId())
    .comDetalhe("valorTotal",  pedido.getValorTotal())
    .info();
```

---

#### 3.3. When — A Linha do Tempo Distribuída

A dimensão *When* responde: **exatamente quando este evento ocorreu?**

Em sistemas distribuídos, o tempo é relativo — e essa relatividade tem consequências diretas para a reconstrução de causa e efeito entre serviços. Um timestamp sozinho não é suficiente: ele precisa ser comparável com timestamps de outros serviços para que a sequência cronológica seja determinável.

**Requisitos obrigatórios:**

**Precisão de milissegundos.** Dois eventos no mesmo segundo em serviços diferentes não têm ordem determinável sem milissegundos. Em Quarkus com Vert.x — runtime de alta concorrência — eventos podem ocorrer com diferença de microssegundos. O formato mandatório é ISO 8601 com milissegundos em UTC: `"2026-03-11T21:55:00.123Z"`.

**UTC obrigatório.** Timestamps em fuso horário local distorcem investigações em sistemas multi-regionais ou em equipes distribuídas. O formato `2026-03-11T21:55:00.123Z` é inequívoco; `2026-03-11T18:55:00.123-03:00` exige conversão mental a cada comparação entre serviços.

**Sincronização NTP.** O `quarkus-logging-json` emite timestamps em UTC — mas isso é necessário, não suficiente. Se os relógios dos nodes do OKD estiverem dessincronizados, os timestamps em UTC serão incorretos mesmo que bem formatados. Todos os nodes devem estar sincronizados via NTP. Em OKD 4, a sincronização é gerenciada pelo operador `chrony` — sua saúde deve ser monitorada como pré-requisito da observabilidade.

O campo `timestamp` é preenchido automaticamente pelo formatador no momento da emissão — o desenvolvedor não o declara.

---

#### 3.4. Where — Topologia e Localização

A dimensão *Where* revela: **onde na cadeia lógica do código e na malha da infraestrutura este evento surgiu?**

O *Where* opera em três escalas complementares:

**Localização técnica no código** — registrada via `.em(Classe.class, "metodo")`, informa a origem precisa do evento sem necessidade de interpretar stack traces completos para eventos não excepcionais. Quando há exceção, o stack trace completo é serializado automaticamente pelo formatador.

```java
LogSistematico
    .registrando("Pedido criado")
    .em(PedidoService.class, "criar")   // ← classe e método no JSON
    .info();
```

**Identificadores de correlação distribuída** — em sistemas que processam milhares de requisições concorrentes, o `traceId` e o `spanId` são o que separa os eventos de uma requisição específica dos eventos de todas as outras:

```json
{
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":  "a3ce929d0e0e4736"
}
```

O `traceId` — gerado pelo OpenTelemetry e propagado via cabeçalho `traceparent` (W3C TraceContext) — une os eventos de todos os serviços que participaram da mesma operação. O `spanId` identifica a operação individual atual dentro do trace, permitindo localizar o nó exato da árvore de execução onde ocorreu a falha. Filtrar por `traceId` em qualquer agregador reconstrói a jornada completa da requisição por N serviços, em ordem cronológica, com um único filtro.

**Identidade do serviço** — o campo `applicationName` informa em qual microsserviço o evento ocorreu. Junto com o `traceId`, permite navegar de um evento em `pedidos-service` para o evento correlacionado em `pagamentos-service` sem ambiguidade.

---

#### 3.5. Why — Motivação de Negócio

A dimensão *Why* responde: **qual a causa ou motivação de negócio deste evento?**

No JSON desta biblioteca, essa dimensão aparece no campo `log_motivo`.

O *Why* é o contexto de negócio que o código técnico não consegue deduzir sozinho — e que diferencia uma arquitetura de logging madura de uma superficial. Um operador de plantão que vê `"Pagamento recusado"` às 3 da manhã precisa do *Why* para agir sem consultar o desenvolvedor que escreveu o código.

```java
LogSistematico
    .registrando("Pagamento recusado")
    .em(PagamentoService.class, "processar")
    .porque("Gateway recusou a transação — saldo insuficiente")  // ← Why
    .comDetalhe("errorCode", "PAG-4022")
    .erro(e);
```

**Regra crítica:** `.porque()` deve expressar a causa em termos de domínio de negócio — nunca em termos técnicos. A distinção:

| Preenchimento | Classificação | Útil para o operador? |
|---|---|---|
| `"Gateway recusou a transação — saldo insuficiente"` | Why de negócio | ✅ Sim — ação clara |
| `"IOException ao chamar o gateway"` | What técnico disfarçado de Why | ❌ Não — sem contexto de negócio |
| `e.getMessage()` | Mensagem de exceção | ❌ Não — pertence ao `.erro(e)` |

O objeto de exceção completo — com stack trace e cadeia de causas — é passado ao terminador `.erro(e)`. O `.porque()` é o contexto de negócio que explica *por que* aquele código estava sendo executado.

---

#### 3.6. How — Canal de Origem

A dimensão *How* responde: **por qual canal ou mecanismo este evento chegou ao sistema?**

No JSON desta biblioteca, essa dimensão aparece no campo `log_canal`.

O *How* é o contexto arquitetural do evento — informa se a ação foi disparada por uma requisição HTTP síncrona, uma mensagem de fila assíncrona, um job agendado ou uma chamada interna. Essa informação é essencial para distinguir comportamentos esperados de comportamentos anômalos:

| Evento | Canal | Interpretação |
|---|---|---|
| `LOGIN_FAILED` | `API REST — POST /auth/login` | Possível tentativa de força bruta |
| `LOGIN_FAILED` | `Job de migração — batch noturno` | Comportamento esperado |
| `PAYMENT_FAILED` | `KAFKA_CONSUMER — topico pagamentos` | Falha assíncrona — investigar DLQ |
| `PAYMENT_FAILED` | `API REST — POST /pedidos/{id}/pagar` | Falha síncrona — retornar erro ao cliente |

```java
LogSistematico
    .registrando("Nota fiscal processada")
    .em(NotaFiscalService.class, "processar")
    .como("Job assíncrono — scheduler diário 02:00 UTC")   // ← How
    .info();
```

Canais comuns: `API REST — POST /pedidos`, `KAFKA_CONSUMER — topico:pagamentos`, `QUARTZ_JOB — NfeProcessamentoJob`, `GRPC — PedidoService/Criar`.

---

#### 3.7. Casos de Uso Além do Debugging

Um evento com conformidade rigorosa ao 5W1H destranca casos de uso que vão além do diagnóstico de erros:

**Analytics em tempo real.** Eventos como `ORDER_COMPLETED` com `valorTotal`, `currency` e `userId` alimentam dashboards de KPIs diretamente no Kibana ou Grafana, sem necessidade de um banco de dados de analytics separado ou SDK de terceiros. O log estruturado é o pipeline de analytics.

**Conformidade regulatória (LGPD).** A trilha de auditoria construída sobre eventos 5W1H responde exatamente às perguntas exigidas por uma investigação regulatória: quem acessou (Who), quais dados (What), quando (When), de qual sistema (Where), por qual justificativa (Why) e por qual canal (How). Isso transforma o log de ferramenta de diagnóstico em evidência probatória.

**Detecção proativa de anomalias.** A consistência estrutural do 5W1H habilita alertas baseados em padrão: um pico de `LOGIN_FAILED` do mesmo `userId` em curto intervalo sinaliza tentativa de força bruta; uma queda de 80% em `ORDER_COMPLETED` em relação à baseline sinaliza falha silenciosa no frontend; qualquer evento com `detalhe_errorCode: "PAG-4022"` acima de um threshold dispara notificação no canal de plantão. Sem estrutura consistente, esses alertas não são possíveis.

**Resolução de disputas técnicas.** O payload e a resposta de uma chamada a um gateway externo, registrados como eventos 5W1H com `traceId` e timestamp, constituem evidência técnica irrefutável para encerrar disputas sobre o que foi enviado, quando foi enviado e qual foi a resposta — sem depender de logs do sistema do parceiro.

---

### 4. Rastreamento Distribuído — Conceitos

#### 4.1. O Problema: Requisições sem Identidade

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

> *Cada instância de serviço registra informações sobre as requisições que processa usando um formato padrão. Cada requisição recebe um identificador único que é propagado por todos os serviços participantes, permitindo correlação completa de ponta a ponta.*
> — Chris Richardson, microservices.io

---

#### 4.2. Trace, Span e Hierarquia de Execução

**Trace** representa o ciclo de vida completo de uma requisição externa — desde a ação do usuário até a resposta final, atravessando todos os serviços envolvidos. É identificado por um `traceId` globalmente único, gerado no ponto de entrada do sistema e propagado por todos os saltos de rede subsequentes. Estruturalmente, um trace é uma **árvore de Spans**.

**Span** é a unidade básica de trabalho dentro de um trace. Cada operação significativa é representada por um Span independente: a execução de um endpoint HTTP, uma consulta ao banco de dados, uma chamada a serviço externo, um método de negócio crítico.

Um Span registra obrigatoriamente:

| Campo | Descrição |
|---|---|
| `trace_id` | Identificador do trace raiz — mesmo valor em todos os spans da operação |
| `span_id` | Identificador único deste span dentro do trace |
| `parent_span_id` | Span pai que originou este (ausente apenas no Root Span) |
| `operation_name` | O que este span representa (`POST /pedidos`, `SELECT orders`) |
| `start_time` | Timestamp UTC com precisão de nanosegundos |
| `end_time` | Timestamp UTC com precisão de nanosegundos |
| `status` | `OK`, `ERROR` ou `UNSET` |
| `attributes` | Pares chave-valor com metadados da operação |

Além dos campos obrigatórios, Spans carregam **atributos** (pares chave-valor para busca e filtragem — URL, método HTTP, código de status, ID de entidade) e **eventos de Span** (mensagens associadas a momentos específicos dentro do Span, úteis para registrar exceções no contexto exato onde ocorreram).

**Hierarquia Root Span e Child Spans:** o Span criado no ponto de entrada da requisição — geralmente o endpoint HTTP — é o Root Span. Cada operação subsequente cria um Child Span vinculado ao Span pai que o originou. Essa hierarquia responde duas perguntas fundamentais:

- **Ordem de execução:** qual serviço chamou qual, em que sequência.
- **Distribuição de latência:** a comparação entre a duração do Root Span e a soma dos Child Spans revela imediatamente onde o tempo está sendo consumido.

```
Root Span: POST /pedidos                          [0ms ──────────────────── 250ms]
  └─ Child Span: PedidoService.criar              [5ms ──────────────── 245ms]
       ├─ Child Span: EstoqueService.reservar     [10ms ──── 80ms]
       ├─ Child Span: PagamentoService.processar  [90ms ──────────── 200ms]
       │    └─ Child Span: GatewayClient.cobrar   [95ms ─────────── 195ms]
       └─ Child Span: notificacoes.enviar         [205ms ── 240ms]
```

Nesse exemplo, o gargalo é `GatewayClient.cobrar` — 100ms de 250ms totais, visível imediatamente sem necessidade de instrumentação adicional.

---

#### 4.3. Propagação de Contexto e W3C TraceContext

Para que o rastreamento funcione entre serviços distintos, o `traceId` e o `spanId` do pai precisam viajar junto com cada requisição. Esse mecanismo é a **Propagação de Contexto**.

O padrão de mercado é o **W3C TraceContext** (recomendação W3C, 2021), que define o formato do cabeçalho HTTP `traceparent`:

```
traceparent: 00-[traceId-128bits]-[parentSpanId-64bits]-01
              │   │                 │                    │
              │   └─ 32 hex chars   └─ 16 hex chars      └─ flags (01 = sampled)
              └─ versão do protocolo
```

O padrão W3C garante interoperabilidade entre implementações de diferentes fornecedores. Um serviço instrumentado com Quarkus/OTel propaga contexto para serviços Node.js, Python ou .NET sem nenhuma configuração adicional — desde que todos respeitem o W3C TraceContext.

No Quarkus, a propagação é automática: `quarkus-opentelemetry` injeta e extrai o cabeçalho `traceparent` em todas as chamadas HTTP realizadas via clientes instrumentados (RESTClient, Vert.x HTTP Client). O desenvolvedor não manipula cabeçalhos manualmente.

---

#### 4.4. `traceId` vs. `spanId` — Granularidades Complementares

Os dois identificadores operam em granularidades diferentes dentro da mesma árvore de execução:

| Identificador | Granularidade | Propósito |
|---|---|---|
| `traceId` | Toda a transação — atravessa múltiplos serviços | Correlacionar todos os spans de ponta a ponta; identificador de busca no Jaeger/Grafana Tempo |
| `spanId` | Uma operação individual — um método, uma query, uma chamada downstream | Identificar o nó exato da árvore onde ocorreu a falha ou o gargalo de latência |

O `traceId` é **constante** ao longo de toda a requisição distribuída. O `spanId` **muda a cada nova unidade de trabalho**, sempre referenciando o `spanId` do Span pai que o originou. Juntos, formam o par mínimo necessário para diagnóstico completo em ambiente distribuído.

**Anti-padrão — `traceId` gerado manualmente:** gerar `UUID.randomUUID()` e usá-lo como `traceId` cria um identificador que não existe em nenhuma árvore de rastreamento. Ele não correlaciona com nenhum span no Jaeger, não aparece em nenhum trace no Grafana Tempo e torna o campo completamente inútil para diagnóstico distribuído. O `traceId` deve sempre ser extraído do contexto OTel ativo.

---

#### 4.5. Correlação com Logs: o Elo entre os Pilares

O rastreamento distribuído atinge seu potencial máximo quando `traceId` e `spanId` estão presentes em **cada linha de log** emitida durante a execução. Essa correlação transforma logs isolados em evidências de uma narrativa coerente.

```json
{
  "timestamp":        "2026-03-11T21:55:00.123Z",
  "level":            "ERROR",
  "message":          "Falha ao processar pagamento",
  "traceId":          "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":           "a3ce929d0e0e4736",
  "userId":           "joao.silva@empresa.com",
  "applicationName":  "pagamentos-service",
  "log_motivo":       "Gateway recusou a transação",
  "detalhe_ordemId":  "9912"
}
```

Com esses campos presentes, o fluxo de investigação de um incidente é:

```
1. Alerta dispara — taxa de erro acima de limiar (Prometheus/Grafana)
2. Engenheiro abre Jaeger e busca traces com status=ERROR na janela de tempo
3. Localiza o trace problemático pelo traceId — visualiza o grafo de spans
4. Identifica que GatewayClient.cobrar retornou erro
5. Navega diretamente para os logs correlacionados em Kibana/Loki pelo traceId
6. Logs exibem o contexto completo: userId, ordemId, código de gateway, stack trace
```

O `traceId` é a chave que une o alerta de métrica, o grafo de trace e o detalhe do log — em uma única operação de investigação, sem varreduras manuais.

**Registro correto do `spanId` nos logs:** quando um Child Span é criado para um método de negócio, o `spanId` nos logs subsequentes deve ser o do Child Span — não o do Root Span. Isso permite localizar exatamente qual operação dentro do grafo gerou cada linha de log.

---

#### 4.6. Trade-offs e Gerenciamento de Volume

**Overhead transacional:** a coleta de traces adiciona esforço computacional — injeção de cabeçalhos em cada chamada HTTP, criação e encerramento de objetos Span, exportação via rede. O paper Dapper (Google, 2010) documenta o requisito fundamental: o overhead deve ser suficientemente baixo para que a instrumentação possa ser habilitada em todos os serviços em produção sem que os times precisem escolher entre observabilidade e performance. Isso depende de amostragem adequada — coletar 100% dos spans em sistemas de alto volume é impraticável.

**Complexidade de infraestrutura:** requer configuração e operação de tecnologias dedicadas — OTel Collector, backend de traces (Jaeger, Grafana Tempo), armazenamento de spans com retenção adequada. Em ecossistemas pequenos, o custo operacional pode superar o benefício diagnóstico.

**Gerenciamento de volume — Tail-Based Sampling:** em sistemas de grande escala, a decisão de reter ou descartar um trace é tomada *após* o trace completar, garantindo que traces com erros sejam sempre retidos independente da taxa de amostragem de traces normais. O OTel Collector é o componente responsável por essa política — a aplicação emite 100% dos spans e o Collector aplica a filtragem sem alteração de código na aplicação.

---

### 5. Métricas — Conceitos

#### 5.1. O que Logs e Traces não Respondem

Logs e traces são indispensáveis para diagnóstico — eles respondem *o que aconteceu* e *por onde a requisição passou*. Mas não foram projetados para responder perguntas de volume e tendência sobre o sistema como um todo:

- A taxa de erros no último minuto subiu ou desceu?
- O tempo de resposta do endpoint `POST /pedidos` nos últimos 30 dias segue tendência de degradação?
- Quantas requisições simultâneas o sistema está atendendo agora?
- A fila de processamento está crescendo ou estabilizando?

Responder essas perguntas a partir de logs exigiria consultar e agregar potencialmente milhões de registros em tempo real — operação com latência incompatível com alertas de produção. Métricas existem para tornar essas perguntas respondíveis em milissegundos, com custo computacional desprezível.

---

#### 5.2. O que é uma Métrica e sua Natureza de Série Temporal

Uma métrica é uma **medição numérica capturada em um ponto no tempo**, associada a um nome e a um conjunto de dimensões (*tags*). Métricas são armazenadas em **bancos de dados de séries temporais**, onde cada nova medição é adicionada ao final da série — sem sobrescrever valores anteriores.

Essa natureza de série temporal é o que torna métricas adequadas para análise de tendência, detecção de anomalias e configuração de alertas. Quando o Prometheus armazena `http_server_requests_seconds_count`, ele mantém cada valor com seu timestamp — permitindo calcular a taxa de crescimento, identificar picos e configurar alertas por limiar.

Três características fundamentais distinguem métricas de logs:

**Pré-agregação:** ao contrário de logs, métricas são calculadas na origem e transmitidas já em forma agregada. O contador de requisições não envia um evento por requisição — acumula e envia a contagem periodicamente.

**Baixa cardinalidade por design:** cada combinação única de nome de métrica e conjunto de tags produz uma série temporal independente. O número total de séries deve ser controlado — tags com alta cardinalidade saturam o sistema de coleta.

**Resolução temporal configurável:** métricas são amostradas em intervalos regulares (ex: a cada 15 segundos pelo Prometheus). Eventos que ocorrem entre dois *scrapes* são agregados, não individualmente registrados.

---

#### 5.3. Tipos Fundamentais: Counter, Gauge, Timer, Distribution Summary

O Micrometer define quatro tipos primitivos de medidores, suficientes para modelar qualquer fenômeno observável em sistemas de software:

**Counter (Contador)** — registra um valor que somente aumenta. Nunca decresce; quando o serviço reinicia, retorna a zero. Usado para eventos que ocorrem e são contados: requisições processadas, erros lançados, mensagens consumidas, pagamentos aprovados.

```
metodo.falha{classe="PagamentoService", metodo="processar", excecao="GatewayException"}
http.requests.total{uri="/pedidos", method="POST", status="200"}
```

**Gauge (Medidor)** — registra um valor que pode aumentar ou diminuir. Captura o valor instantâneo no momento da leitura — não acumula. Usado para estados correntes: tamanho de fila, conexões ativas em pool, uso de memória, threads em execução.

```
jvm.memory.used{area="heap"}
db.connections.active{pool="pedidos-ds"}
fila.processamento.tamanho{topico="pagamentos"}
```

> O Micrometer não mantém referência forte ao objeto observado pelo Gauge. Se o objeto for coletado pelo GC, o Gauge passa a retornar `NaN`. Sempre mantenha uma referência forte ao objeto instrumentado.

**Timer (Cronômetro)** — mede latência e frequência de operações. Armazena internamente a soma de todas as durações, a contagem de ocorrências e o valor máximo em uma janela de tempo decrescente. Suporta **histogramas** e **percentis** (p50, p95, p99) — fundamentais para SLOs.

```
metodo.execucao{classe="PedidoService", metodo="criar"}
http.server.requests{uri="/pedidos", method="POST"}
```

**Distribution Summary (Sumário de Distribuição)** — semelhante ao Timer, mas para valores não temporais arbitrários. Registra distribuição de magnitudes: tamanho de payload, número de itens em uma resposta, valor de transações.

```
http.response.size{uri="/pedidos", method="GET"}
pagamento.valor{moeda="BRL", gateway="Cielo"}
```

---

#### 5.4. Dimensões (Tags) e o Risco de Explosão de Cardinalidade

Tags são pares chave-valor associados a uma métrica no momento do registro. São o mecanismo que torna métricas multidimensionais — permitem fatiar e agrupar dados sem criar métricas separadas para cada combinação.

**Risco de explosão de cardinalidade:** cada combinação única de nome de métrica e conjunto de tag-values produz uma série temporal independente no banco de tempo. Tags com alta cardinalidade — valores que variam por requisição — multiplicam exponencialmente o número de séries, saturando armazenamento e memória do sistema de coleta.

| Uso de tag | Cardinalidade | Adequado para métrica? |
|---|---|---|
| `status` (`200`, `404`, `500`) | Baixa — poucos valores fixos | ✅ Correto |
| `metodo` (`criar`, `buscar`, `deletar`) | Baixa — conjunto fechado | ✅ Correto |
| `userId` (milhões de usuários distintos) | Alta — cresce com usuários | ❌ Explosão de cardinalidade |
| `traceId` (único por requisição) | Extremamente alta | ❌ Nunca usar como tag |
| `pedidoId` (único por pedido) | Alta — cresce com volume | ❌ Use logs/traces para isso |

**Regra:** use tags apenas para valores de um conjunto fechado e pequeno. Para correlacionar uma métrica com uma entidade ou usuário específico, use o `traceId` — ele existe para isso, via Exemplars (seção 24.5).

---

#### 5.5. Correlação com Logs e Traces

Métricas, por sua natureza agregada, não identificam *qual* requisição causou um problema — identificam *que* existe um problema e *onde* no sistema ele está concentrado. O fluxo de investigação típico atravessa os três pilares em sequência:

```
1. ALERTA DISPARA (Prometheus/Grafana)
   └─ métricas: taxa de erro de PagamentoService.processar acima de 5%

2. ANÁLISE DE TENDÊNCIA (Grafana)
   └─ histograma: p99 de latência subiu de 200ms para 2s nas últimas 2h
   └─ counter de falhas: exceção predominante é GatewayException

3. LOCALIZAÇÃO DO COMPONENTE (Jaeger/Grafana Tempo)
   └─ traces com status=ERROR filtrados por serviço e janela de tempo
   └─ grafo de spans: GatewayClient.cobrar responde por 1,8s dos 2s totais

4. DIAGNÓSTICO DETALHADO (Kibana/Loki)
   └─ filtrar por traceId do trace problemático
   └─ logs: userId, pedidoId, código de erro do gateway, stack trace completo
```

O `traceId` presente em cada linha de log e em cada span é a chave que torna essa navegação possível. Métricas apontam o problema; traces localizam o componente; logs explicam o contexto exato.

---

#### 5.6. Trade-offs

**Perda de granularidade individual:** métricas agregam. Um Timer com p99 de 2 segundos não diz *qual* requisição demorou 2 segundos — apenas que 1% das requisições estão nessa faixa. Para identificar a requisição específica, o `traceId` no log é o caminho. Exemplars (seção 24.5) reduzem esse atrito ao embutir o `traceId` diretamente na amostra do histograma.

**Custo de amostragem periódica:** o Prometheus coleta métricas por *scrape* a cada N segundos. Eventos que ocorrem e se resolvem entre dois scrapes podem não ser capturados. Para fenômenos de curta duração ou ocorrência rara, logs e traces são mais confiáveis.

**Explosão de cardinalidade:** o risco mais grave na prática. Uma tag com alta cardinalidade pode multiplicar o número de séries temporais por ordens de magnitude, saturando memória e armazenamento. Monitorar a cardinalidade do sistema de métricas é parte da operação de observabilidade.

**Overhead de coleta:** timers e contadores em caminhos de alta frequência têm custo — criação de objetos, operações de incremento com sincronização, transmissão periódica. Em caminhos críticos de performance, o tipo de medidor e a estratégia de registro devem ser escolhidos com cuidado.

---

## Parte II — OpenTelemetry e Pipeline de Telemetria
## Parte II — OpenTelemetry e Pipeline de Telemetria

### 6. OpenTelemetry — O Padrão CNCF

O **OpenTelemetry** (OTel) é um projeto de código aberto mantido pela **CNCF** (*Cloud Native Computing Foundation*) que fornece APIs, SDKs e protocolos padronizados para instrumentar, gerar, coletar e exportar dados de telemetria — traces, métricas e logs — de forma unificada.

Antes do OpenTelemetry, cada plataforma de observabilidade exigia sua própria biblioteca proprietária de instrumentação, criando *vendor lock-in* estrutural: migrar do Datadog para o New Relic implicava reescrever toda a instrumentação. O OTel eliminou esse problema — o código é instrumentado **uma única vez** usando o padrão aberto, e os dados podem ser roteados para qualquer plataforma compatível via configuração, sem alteração de código.

---

#### 6.1. Instrumentação Automática e Manual

A instrumentação pode ocorrer em dois níveis complementares:

**Automática:** em frameworks como o Quarkus, a extensão `quarkus-opentelemetry` instrumenta endpoints REST, clientes HTTP e operações de banco de dados sem nenhuma alteração no código de negócio. O Root Span da requisição e os Child Spans das chamadas downstream são criados e encerrados pelo framework.

**Manual (customizada):** quando a instrumentação automática não cobre uma operação relevante — um método de negócio crítico, uma etapa de processamento de lote, uma operação com semântica de domínio importante — o desenvolvedor usa a anotação `@WithSpan` ou a API `Tracer` diretamente para criar Spans adicionais com atributos de domínio. Na `lib-logging-quarkus`, essa camada é encapsulada pela anotação `@Rastreado` e pelo `RastreamentoInterceptor` (seção 15).

A combinação de instrumentação automática + manual é o que garante cobertura completa: o framework cobre o perímetro externo (HTTP, banco, filas), e a instrumentação manual adiciona granularidade nos pontos de negócio onde a latência e os erros têm significado de domínio.

---

#### 6.2. Protocolo OTLP e Pipeline de Coleta

O OpenTelemetry usa o protocolo **OTLP** (*OpenTelemetry Protocol*) para transmitir dados de telemetria da aplicação para o backend de análise. O protocolo suporta gRPC e HTTP/Protobuf.

```
Aplicação (OTel SDK)
        │
        │  OTLP (gRPC ou HTTP/Protobuf)
        ▼
OTel Collector  ──(filtragem, aggregation, tail sampling)──▶  Backend de Análise
                                                               (Jaeger / Grafana Tempo / Datadog)
```

Em ambientes de desenvolvimento, a aplicação pode exportar diretamente para o backend, dispensando o Collector intermediário. Em produção, o **OTel Collector é obrigatório** pelos seguintes motivos:

- **Desacoplamento:** trocar de backend (Jaeger → Grafana Tempo) é alteração de configuração do Collector — sem toque no código da aplicação.
- **Tail-Based Sampling:** a decisão de reter ou descartar um trace com base no resultado final (erro vs. sucesso) só é possível no Collector, após o trace completar. A aplicação emite 100% dos spans.
- **Enriquecimento:** o Collector adiciona metadados de infraestrutura — `kubernetes.pod.name`, `kubernetes.namespace`, `container.id` — sem acoplamento à aplicação.
- **Roteamento:** um único pipeline pode alimentar simultaneamente Jaeger (traces), Loki (logs) e Prometheus (métricas).

---

#### 6.3. Backends de Visualização

| Backend | Característica Principal |
|---|---|
| **Jaeger** | Open-source, amplamente adotado em cloud-native; cronogramas detalhados e análise de causa raiz integrada |
| **Grafana Tempo** | Integração nativa com Loki (logs) e Prometheus (métricas) — unifica os três pilares em um único painel operacional |
| **Zipkin** | Alternativa madura, especialmente em ecossistemas Spring |
| **Elastic APM** | Integração natural com o stack ELK — correlação nativa com logs indexados no Elasticsearch |
| **Datadog** | Plataforma gerenciada com correlação automática entre os três pilares e alertas inteligentes |

Em ambientes OKD, **Grafana Tempo** é a escolha natural quando o stack já inclui Prometheus e Loki — os três compartilham o mesmo plano de controle no Grafana e permitem navegação direta entre alerta → trace → log sem troca de ferramenta.

---

#### 6.4. Exportação Unificada via OTLP (`quarkus-micrometer-opentelemetry`)

O Quarkus suporta dois modos de exportação de métricas:

**Prometheus** (`quarkus-micrometer-registry-prometheus`): expõe `/q/metrics` para coleta por pull. Adequado quando o stack já tem Prometheus como coletor e Grafana como visualização.

**OTLP unificado** (`quarkus-micrometer-opentelemetry`): envia métricas pelo mesmo pipeline OTLP usado por traces e logs — um único endpoint, um único Collector, um único protocolo. Recomendado quando o OTel Collector já está no pipeline, pois elimina o Prometheus como coletor separado.

```xml
<!-- Modo Prometheus — pull via /q/metrics -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Modo OTLP — push unificado com traces e logs -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-opentelemetry</artifactId>
</dependency>
```

```properties
# Endpoint único para traces, métricas e logs — modo OTLP unificado
quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317

# Amostragem: always_on envia 100% dos spans para o Collector.
# Políticas de Tail-Based Sampling residem no OTel Collector — não na aplicação.
quarkus.otel.traces.sampler=always_on

# Nome do serviço: rótulo em todos os spans, métricas e logs exportados.
# Deve ser único por microsserviço no ecossistema.
quarkus.application.name=pedidos-service
```

O diagrama completo do pipeline em OKD:

```
pedidos-service (Quarkus)
  ├─ Traces  ──┐
  ├─ Métricas ─┤  OTLP/gRPC  ──▶  OTel Collector
  └─ Logs ─────┘                        │
                                         ├──▶ Grafana Tempo   (traces)
                                         ├──▶ Prometheus      (métricas)
                                         └──▶ Loki            (logs)
                                                    │
                                               Grafana (visualização unificada)
```

---

### 7. Contexto Reativo — MDC, Vert.x e Mutiny

Esta seção documenta um comportamento crítico específico de Quarkus com programação reativa. A ausência de entendimento sobre esse comportamento é a causa mais comum de `traceId` e `spanId` ausentes ou incorretos em logs de produção — sem nenhum erro visível.

---

#### 7.1. O Problema do `ThreadLocal` em Pipelines Assíncronos

O MDC do JBoss Logging (e do SLF4J) usa `ThreadLocal` internamente. Em Java tradicional (modelo bloqueante, uma thread por requisição), isso funciona perfeitamente: a thread que inicia a requisição é a mesma que a encerra, e o MDC persiste durante toda a execução.

O Quarkus com Vert.x e Mutiny quebra esse modelo. Cada operador reativo (`onItem()`, `onFailure()`, `flatMap()`) pode executar em uma thread diferente da thread que montou a cadeia. O `ThreadLocal` da thread original não é visível na thread do continuation — o MDC é silenciosamente perdido.

O problema concreto:

```java
@ApplicationScoped
@Logged
@Rastreado
public class PagamentoService {

    public Uni<Pagamento> processar(OrdemPagamento ordem) {
        // Thread A — interceptors executam aqui
        // MDC: { traceId: "4bf9...", spanId: "a3ce..." }
        // MDC.put("spanId", childSpanId) executado pelo RastreamentoInterceptor

        return gateway.processar(ordem)          // retorna Uni — executa em Thread B
            .onItem().invoke(pagamento -> {
                // Thread B — continuation reativa
                // MDC.get("spanId") aqui pode retornar: null, vazio, ou o spanId do Root Span
                // — dependendo de qual thread do pool Vert.x executou este operador

                LogSistematico
                    .registrando("Gateway respondeu")
                    .em(PagamentoService.class, "processar")
                    .comDetalhe("pagamentoId", pagamento.getId())
                    .info();
                // JSON resultante: spanId ausente ou incorreto
            });
    }
}
```

O log emitido dentro de `.onItem().invoke()` pode não carregar o `spanId` do Child Span criado pelo `@Rastreado` — tornando a correlação log↔trace ineficaz exatamente onde mais importa: no continuation reativo onde a resposta do gateway foi processada.

---

#### 7.2. `quarkus-smallrye-context-propagation`

A extensão `quarkus-smallrye-context-propagation` resolve o problema de propagação do MDC em pipelines Mutiny/Vert.x. Ela captura o contexto MDC — e o contexto OTel — no momento do `subscribe()` e o restaura em cada thread que executa continuations da cadeia reativa.

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-context-propagation</artifactId>
</dependency>
```

Com a extensão ativa, o Quarkus usa `VertxContextStorageProvider` como implementação do `ContextStorage` do OTel SDK. O span OTel ativo é propagado via Vert.x Context — não via `ThreadLocal` — tornando-o acessível em qualquer continuation da cadeia reativa, independente de qual thread do pool Vert.x a executa.

**O que é propagado automaticamente:**

- Contexto OTel (span ativo, `traceId`, `spanId`)
- MDC do JBoss Logging / SLF4J
- `SecurityIdentity` do Quarkus

**Limitação importante:** a extensão propaga o contexto que estava presente no momento do `subscribe()` — não no momento em que cada operador executa. Isso significa que o MDC escrito pelo `RastreamentoInterceptor` só é propagado para os continuations se o `subscribe()` ocorrer *após* o interceptor ter escrito no MDC. Em uso normal com CDI interceptors, isso é garantido pela ordem de execução dos interceptors — mas deve ser verificado em pipelines reativos construídos manualmente fora do contexto CDI.

---

#### 7.3. Comportamento Correto em Métodos que Retornam `Uni`/`Multi`

O mecanismo de propagação via `VertxContextStorageProvider` garante que o span OTel ativo seja acessível em continuations reativas. Porém, o `MDC.put("spanId", ...)` executado no início do interceptor escreve o valor na thread do interceptor — e esse valor é o que será propagado para os continuations.

Para garantir que o `spanId` correto (do Child Span) apareça em logs emitidos dentro de continuations reativas, o `GerenciadorContextoLog` deve extrair o `spanId` do span OTel **corrente no momento da emissão do log** — não depender exclusivamente do valor escrito no MDC no início da requisição.

O `quarkus-opentelemetry` garante que `Span.current()` retorna o span correto em qualquer ponto da cadeia reativa quando `quarkus-smallrye-context-propagation` está ativo. Isso é o que deve ser usado como fonte de verdade:

```java
// FRÁGIL em contexto reativo — valor escrito uma vez no início do interceptor
String spanId = (String) MDC.get("spanId");

// CORRETO — extrai o spanId do span OTel corrente no momento da emissão
String spanId = Span.current().getSpanContext().getSpanId();
```

O `GerenciadorContextoLog` deve usar `Span.current()` para preencher o `spanId` no MDC a cada evento de log quando há span OTel ativo, garantindo que o valor sempre reflita o span corrente — não o span que estava ativo quando o MDC foi inicializado.

---

#### 7.4. Leitura de `traceId`/`spanId` no Momento da Emissão

O contrato de leitura dos identificadores de correlação na biblioteca:

```java
// GerenciadorContextoLog — lógica de preenchimento por evento de log
private void sincronizarComSpanAtivo() {
    var spanContext = Span.current().getSpanContext();

    if (spanContext.isValid()) {
        // Span OTel ativo — usa os identificadores do span corrente
        MDC.put("traceId", spanContext.getTraceId());
        MDC.put("spanId",  spanContext.getSpanId());
    }
    // Se não há span ativo, traceId e spanId permanecem como foram
    // inicializados pelo LogContextoFiltro no início da requisição.
}
```

**Checklist de verificação para métodos reativos com `@Rastreado`:**

O seguinte comportamento deve ser verificado em code review para qualquer método anotado com `@Rastreado` que retorne `Uni` ou `Multi`:

- `quarkus-smallrye-context-propagation` está declarado como dependência Maven.
- Logs emitidos dentro de `.onItem()`, `.onFailure()`, `.flatMap()` e outros operadores contêm `spanId` não nulo.
- O `spanId` nos continuations corresponde ao Child Span criado pelo `@Rastreado` — não ao Root Span da requisição HTTP.
- O `traceId` é consistente entre todos os logs da cadeia reativa da mesma requisição.

A forma mais simples de verificar: emitir um log de teste dentro de um continuation reativo em ambiente de desenvolvimento e inspecionar o JSON no stdout. Se `spanId` for `"0000000000000000"` (valor inválido do OTel) ou ausente, a propagação não está funcionando.

---

## Parte III — Implementação: Logging
## Parte III — Implementação: Logging

### 8. Arquitetura da Biblioteca de Logging

#### 8.1. Estrutura de Pacotes

A biblioteca organiza suas responsabilidades em pacotes com separação explícita de concerns. Cada pacote tem uma única responsabilidade e não cria dependência circular com os demais:

```
lib-logging-quarkus/
└── src/main/java/br/com/seudominio/log/
    ├── annotations/
    │   ├── Logged.java                  ← @InterceptorBinding CDI (logging)
    │   └── Rastreado.java               ← @InterceptorBinding CDI (tracing)
    ├── context/
    │   ├── LogContexto.java             ← record imutável com os campos do MDC
    │   ├── GerenciadorContextoLog.java  ← único ponto de escrita do MDC
    │   └── SanitizadorDados.java        ← mascaramento de dados sensíveis
    ├── core/
    │   └── LogEvento.java               ← record imutável da cadeia DSL
    ├── dsl/
    │   ├── LogEtapas.java               ← sealed interface — contrato da cadeia fluente
    │   └── LogSistematico.java          ← ponto de entrada da DSL
    ├── filtro/
    │   └── LogContextoFiltro.java       ← inicialização e limpeza do MDC por requisição
    ├── interceptor/
    │   ├── LogInterceptor.java          ← @Logged: MDC de localização + métricas Micrometer
    │   └── RastreamentoInterceptor.java ← @Rastreado: Child Span OTel + MDC de spanId
    └── tracing/
        ├── EnriquecedorSpan.java        ← interface do pipeline de enriquecimento
        ├── EnriquecedorMetadados.java   ← OTel Semantic Conventions (prioridade 10)
        ├── EnriquecedorIdentidade.java  ← enduser.id via SecurityIdentity (prioridade 20)
        └── GerenciadorRastreamento.java ← ciclo de vida do Span + sincronização MDC
```

**Princípios de design:**

- `annotations/` — apenas definições de binding CDI, sem lógica.
- `context/` — toda escrita no MDC passa pelo `GerenciadorContextoLog`. Nenhum outro componente chama `MDC.put()` diretamente.
- `dsl/` — a `sealed interface LogEtapas` com `permits LogSistematico` impede que o contrato da cadeia fluente seja estendido acidentalmente fora da biblioteca.
- `interceptor/` — dois interceptors com `@Priority` distintas garantem ordem determinística de execução quando ambos estão presentes no mesmo bean.
- `tracing/` — isolado de `context/` para preservar separação entre responsabilidades de logging e de rastreamento.

---

#### 8.2. Dependências e Configuração de Saída JSON

**`pom.xml` — dependências obrigatórias:**

```xml
<!-- Auto-instrumentação HTTP, CDI Tracer, propagação W3C TraceContext -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>

<!--
    Propagação de MDC e span OTel em pipelines reativos Mutiny/Vert.x.
    Obrigatório para qualquer serviço com métodos que retornam Uni ou Multi.
    Sem esta extensão, traceId e spanId são silenciosamente perdidos
    em continuations reativas — ver seção 7.
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-context-propagation</artifactId>
</dependency>

<!-- Saída JSON no console — campos MDC como chaves de primeiro nível -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-logging-json</artifactId>
</dependency>

<!-- Métricas de método via @Logged (Timer + Counter) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

**`application.properties` — configuração mínima:**

```properties
# ─── Logging ──────────────────────────────────────────────────────────────────

# Saída JSON no console — campos MDC como chaves de primeiro nível no JSON
quarkus.log.console.json=true

# Nível padrão de produção — DEBUG desativado por padrão
quarkus.log.level=INFO

# DEBUG ativável dinamicamente por pacote sem reinicialização
# quarkus.log.category."br.com.seudominio".level=DEBUG

# ─── OpenTelemetry ────────────────────────────────────────────────────────────

# Endpoint OTLP — Jaeger em desenvolvimento, OTel Collector em produção
quarkus.otel.exporter.otlp.endpoint=http://jaeger:4317

# always_on: 100% dos spans exportados para o Collector
# Tail-Based Sampling reside no Collector, não na aplicação
quarkus.otel.traces.sampler=always_on

# Rótulo do serviço em todos os spans, métricas e logs exportados
quarkus.application.name=pedidos-service

# ─── Micrometer / Prometheus ──────────────────────────────────────────────────

quarkus.micrometer.export.prometheus.enabled=true

# Exclui endpoints internos do Quarkus das métricas HTTP
quarkus.micrometer.binder.http-server.ignore-patterns=/q/health.*,/q/metrics
```

---

#### 8.3. Plataforma Base: Java 21 e Quarkus 3.20+

| Requisito | Especificação | Justificativa |
|---|---|---|
| Versão Java | Java 21 (mínimo) | `sealed interfaces`, `records`, pattern matching com `switch` |
| Framework | Quarkus 3.20+ | CDI nativo, `quarkus-opentelemetry`, `quarkus-logging-json` |
| Objetos de valor | `record` Java 21 | `LogEvento`, `LogContexto` — imutabilidade thread-safe sem sincronização |
| DSL | `sealed interface` com `permits` | Impede extensão acidental do contrato fora da biblioteca |
| Injeção de dependências | CDI nativo (`@ApplicationScoped`, `@Inject`) | Sem anotações Spring — compatibilidade nativa com ArC |
| Concorrência reativa | SmallRye Context Propagation | Propagação de MDC e span OTel em pipelines Mutiny/Vert.x |

O uso de `sealed interface` na DSL é uma decisão de design intencional: `LogEtapas` com `permits LogSistematico` torna o contrato da cadeia fluente exclusivo da biblioteca. Nenhuma classe externa pode implementar `LogEtapas` e criar desvios do padrão que passariam despercebidos em code review.

O uso de `records` para `LogEvento` e `LogContexto` elimina boilerplate de equals/hashCode/toString e garante imutabilidade estrutural — um evento de log não pode ser modificado após criado, o que é consistente com o princípio append-only do log semântico.

---

### 9. Gerenciamento de Contexto via MDC

O MDC (*Mapped Diagnostic Context*) é um mapa thread-local que acrescenta pares chave-valor automaticamente a todo evento de log emitido naquela thread. É o mecanismo que permite que `userId`, `traceId` e `applicationName` apareçam em todos os logs de uma requisição sem que o desenvolvedor os passe explicitamente em cada chamada.

---

#### 9.1. `GerenciadorContextoLog` — Responsabilidade Única de Escrita

O `GerenciadorContextoLog` é o **único ponto de escrita do MDC** na biblioteca. Nenhum outro componente chama `MDC.put()` diretamente — chamadas dispersas no código de aplicação são não conformidade (anti-padrão P9).

Responsabilidades do `GerenciadorContextoLog`:

- Inicializar o MDC no início de cada requisição com `userId` e `applicationName`.
- Sincronizar `traceId` e `spanId` com o span OTel ativo via `Span.current().getSpanContext()` — não a partir de valores armazenados no início da requisição (ver seção 7.4).
- Registrar localização técnica (`classe`, `metodo`) quando acionado pelo `LogInterceptor`.
- Limpar o MDC ao final da requisição — sem exceção.

```java
@ApplicationScoped
public class GerenciadorContextoLog {

    private static final String CAMPO_USER_ID          = "userId";
    private static final String CAMPO_APPLICATION_NAME = "applicationName";
    private static final String CAMPO_TRACE_ID         = "traceId";
    private static final String CAMPO_SPAN_ID          = "spanId";
    private static final String CAMPO_CLASSE           = "classe";
    private static final String CAMPO_METODO           = "metodo";

    @ConfigProperty(name = "quarkus.application.name")
    String applicationName;

    /** Inicializa o MDC no início da requisição HTTP. */
    public void inicializar(String userId) {
        MDC.put(CAMPO_USER_ID,          userId != null ? userId : "anonimo");
        MDC.put(CAMPO_APPLICATION_NAME, applicationName);
        sincronizarComSpanAtivo();
    }

    /**
     * Sincroniza traceId e spanId com o span OTel corrente.
     *
     * Chamado no início da requisição e deve ser chamado pelo
     * formatador de cada evento de log para garantir que o spanId
     * reflita o span corrente — não o span do início da requisição.
     * Isso é crítico em pipelines reativos onde o span ativo muda
     * entre continuations (ver seção 7).
     */
    public void sincronizarComSpanAtivo() {
        var spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            MDC.put(CAMPO_TRACE_ID, spanContext.getTraceId());
            MDC.put(CAMPO_SPAN_ID,  spanContext.getSpanId());
        }
    }

    /** Registra localização técnica — chamado pelo LogInterceptor via @Logged. */
    public void registrarLocalizacao(String classe, String metodo) {
        MDC.put(CAMPO_CLASSE, classe);
        MDC.put(CAMPO_METODO, metodo);
    }

    /** Remove campos de localização técnica — chamado no finally do LogInterceptor. */
    public void removerLocalizacao() {
        MDC.remove(CAMPO_CLASSE);
        MDC.remove(CAMPO_METODO);
    }

    /** Limpa todo o MDC — chamado no finally do LogContextoFiltro. */
    public void limpar() {
        MDC.clear();
    }
}
```

**Regra de propriedade:** o `GerenciadorContextoLog` é `@ApplicationScoped` — sem estado de requisição. Todo estado de contexto reside no MDC (thread-local), não no bean. Isso garante thread-safety sem sincronização explícita.

---

#### 9.2. `LogContextoFiltro` — Inicialização e Limpeza por Requisição

O `LogContextoFiltro` é um filtro JAX-RS (`@ServerRequestFilter` / `ContainerRequestFilter`) que delimita o ciclo de vida do MDC por requisição HTTP. É o ponto de entrada de todas as requisições e o ponto de saída garantido de limpeza.

```java
@Provider
@Priority(Priorities.AUTHENTICATION - 10)   // executa antes dos filtros de segurança
public class LogContextoFiltro implements ContainerRequestFilter, ContainerResponseFilter {

    @Inject
    GerenciadorContextoLog gerenciador;

    @Inject
    @CurrentIdentityAssociation
    Instance<SecurityIdentity> securityIdentity;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        // Extrai userId do principal autenticado — "anonimo" se não autenticado
        String userId = extrairUserId();
        gerenciador.inicializar(userId);
    }

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        // Limpeza garantida na fase de resposta — independente de exceção no handler
        gerenciador.limpar();
    }

    private String extrairUserId() {
        try {
            if (securityIdentity.isResolvable()) {
                var identity = securityIdentity.get();
                if (identity != null && !identity.isAnonymous()) {
                    return identity.getPrincipal().getName();
                }
            }
        } catch (Exception ignored) {
            // SecurityIdentity indisponível fora de contexto HTTP — não é erro
        }
        return "anonimo";
    }
}
```

**Prioridade do filtro:** executa com prioridade anterior aos filtros de segurança (`AUTHENTICATION - 10`). Isso garante que o MDC já está inicializado quando qualquer filtro de autenticação emitir logs — o `userId` estará como `"anonimo"` nesse ponto e será atualizado após autenticação se necessário.

**Contexto não-HTTP:** jobs agendados (`@Scheduled`), consumers de mensageria e operações em batch não passam pelo filtro HTTP. Nesses contextos, o `GerenciadorContextoLog.inicializar()` deve ser chamado manualmente no início da operação, com o `userId` adequado (`"anonimo"` para jobs de sistema, identificador do consumer para mensageria).

```java
@ApplicationScoped
public class ProcessadorPagamentosJob {

    @Inject
    GerenciadorContextoLog gerenciador;

    @Scheduled(cron = "0 0 2 * * ?")
    public void processar() {
        gerenciador.inicializar("anonimo");  // job de sistema — sem usuário
        try {
            // processamento...
        } finally {
            gerenciador.limpar();            // limpeza obrigatória no finally
        }
    }
}
```

---

#### 9.3. Limpeza Garantida e Prevenção de Context Leak

Context leak é um dos bugs mais difíceis de rastrear em produção: o `userId` de uma requisição aparece em logs de outra, o `traceId` de um trace aparece em spans de outro, métricas são atribuídas a uma operação errada. O sintoma é intermitente e dependente de qual thread do pool atendeu qual requisição — impossível de reproduzir de forma determinística.

A prevenção tem dois mecanismos obrigatórios e complementares:

**Mecanismo 1 — `finally` no filtro HTTP:** o `LogContextoFiltro` implementa `ContainerResponseFilter` e chama `gerenciador.limpar()` na fase de resposta. Mesmo que o handler lance uma exceção não tratada, o JAX-RS garante a execução dos filtros de resposta — o MDC é sempre limpo ao final da requisição HTTP.

**Mecanismo 2 — `finally` nos interceptors:** o `LogInterceptor` remove os campos de localização (`classe`, `metodo`) no bloco `finally` do `@AroundInvoke`. O `RastreamentoInterceptor` restaura o `spanId` do pai no bloco `finally`. Ambos garantem que campos de escopo de método não vazem para além da execução do método interceptado.

```
Requisição HTTP
    │
    ▼
LogContextoFiltro.filter(request)     ← MDC inicializado: userId, applicationName, traceId, spanId
    │
    ▼
RastreamentoInterceptor               ← MDC: spanId atualizado para Child Span
    │  try {
    ▼
LogInterceptor                        ← MDC: classe, metodo adicionados
    │  try {
    ▼
Método de negócio
    │
    ▼
LogInterceptor.finally                ← MDC: classe, metodo removidos
    │
    ▼
RastreamentoInterceptor.finally       ← MDC: spanId restaurado para o pai
    │
    ▼
LogContextoFiltro.filter(response)    ← MDC: limpo completamente (MDC.clear())
```

**Regra de verificação:** qualquer `MDC.put()` no código da aplicação ou da biblioteca que não tenha um `MDC.remove()` ou `MDC.clear()` correspondente em um bloco `finally` é context leak em potencial. Code review deve barrar sistematicamente esse padrão (anti-padrão P7).

---

### 10. A DSL `LogSistematico` — Enforcement do 5W1H

#### 10.1. Contrato da Cadeia Fluente

#### 10.2. Dimensões Obrigatórias vs. Opcionais

#### 10.3. Terminadores de Nível: `.info()`, `.warn()`, `.erro(e)`, `.erroERelanca(e)`

---

### 11. `@Logged` e `LogInterceptor`

#### 11.1. Injeção Automática de Localização Técnica no MDC

#### 11.2. Métricas Automáticas: `metodo.execucao` e `metodo.falha`

#### 11.3. Ativação e Prioridade CDI

---

### 12. `SanitizadorDados` — Proteção de Dados Sensíveis

#### 12.1. Categorias de Mascaramento Automático

#### 12.2. Redação Total: Responsabilidade do Chamador

#### 12.3. Conformidade LGPD

---

### 13. Eventos de Negócio, Auditoria e KEDB

#### 13.1. Distinção entre Eventos Técnicos e de Negócio

#### 13.2. `eventType` como Identificador Canônico

#### 13.3. Códigos de Erro e Base de Conhecimento (KEDB)

#### 13.4. Campos de Auditoria: `actorId`, `entityType`, `stateBefore`, `stateAfter`

---

## Parte IV — Implementação: Rastreamento Distribuído

### 14. Arquitetura da Camada de Tracing

#### 14.1. Estrutura de Pacotes

#### 14.2. Dependências Maven

---

### 15. `@Rastreado` e `RastreamentoInterceptor`

#### 15.1. Ciclo de Vida do Child Span

#### 15.2. Ordem de Execução com `@Logged`: Priority CDI

#### 15.3. Restauração do `spanId` do Pai no `finally`

---

### 16. `GerenciadorRastreamento` — Ciclo de Vida do Span

#### 16.1. Criação, Enriquecimento e Encerramento

#### 16.2. Marcação de Erro e `recordException`

#### 16.3. Sincronização com o MDC

---

### 17. Pipeline de Enriquecimento de Spans

#### 17.1. Interface `EnriquecedorSpan` e Descoberta via CDI

#### 17.2. `EnriquecedorMetadados` — OTel Semantic Conventions

#### 17.3. `EnriquecedorIdentidade` — `enduser.id` via `SecurityIdentity`

#### 17.4. Enriquecedores de Negócio Customizados

---

### 18. Configuração de Exportação e Amostragem

#### 18.1. `application.properties` — OTLP, Sampler e `service.name`

#### 18.2. Tail-Based Sampling no OTel Collector

#### 18.3. Backends: Jaeger, Grafana Tempo, Zipkin

---

## Parte V — Implementação: Métricas

### 19. Arquitetura de Métricas no Quarkus

#### 19.1. Micrometer como Abstração

#### 19.2. Extensões Maven: Prometheus vs. OTLP

#### 19.3. Configuração: `application.properties`

#### 19.4. `MeterRegistry` via CDI

---

### 20. Métricas Automáticas do Quarkus

#### 20.1. Requisições HTTP (RESTEasy Reactive)

#### 20.2. JVM: Memória, GC, Threads

#### 20.3. Pool de Conexões (Agroal)

#### 20.4. Netty / Vert.x

---

### 21. Métricas de Método via `@Logged`

#### 21.1. `metodo.execucao` — Timer com Histograma

#### 21.2. `metodo.falha` — Counter por Tipo de Exceção

#### 21.3. PromQL: Taxa de Erro e Percentis por Método

---

### 22. Métricas de Negócio Customizadas

#### 22.1. Counter — Eventos de Negócio

#### 22.2. Timer Manual — Operações sem `@Logged`

#### 22.3. Anotações Declarativas: `@Timed`, `@Counted`

---

### 23. Padrões de Gauge

#### 23.1. Padrão 1 — Observador de Coleção (`gaugeCollectionSize`)

#### 23.2. Padrão 2 — Observador de Objeto (`Gauge.builder` + `ToDoubleFunction`)

#### 23.3. Padrão 3 — Imperativo (`AtomicLong`)

#### 23.4. Padrão 4 — Múltiplas Dimensões Dinâmicas (`MultiGauge`)

#### 23.5. Padrão 5 — Duração desde Evento (`TimeGauge`)

---

### 24. Padrão Monitor Externo

#### 24.1. White-box vs. Black-box Monitoring

#### 24.2. Estrutura: Domínio Sem Acoplamento a Métricas

#### 24.3. Monitor com Estado em Memória

#### 24.4. Monitor com Estado Calculado Periodicamente (`@Scheduled`)

#### 24.5. Exemplars: Ligação Direta Métrica → Trace

---

## Parte VI — Correlação entre Pilares e Diagnóstico

### 25. Fluxo de Investigação de Incidente

#### 25.1. Alerta → Trace → Log: Navegação entre os Três Pilares

#### 25.2. `traceId` como Chave de Correlação Universal

#### 25.3. Diagrama de Fluxo: Requisição com `@Logged` + `@Rastreado`

---

### 26. Padrões Relacionados de Microsserviços

#### 26.1. Log Aggregation

#### 26.2. API Gateway como Ponto de Criação do Root Span

#### 26.3. Circuit Breaker e Métricas de Estado

#### 26.4. Saga e Rastreabilidade de Transações Distribuídas

---

## Parte VII — Governança e Operação

### 27. Registro de Nomes de Campos Canônicos

#### 27.1. Convenção de Nomenclatura

#### 27.2. Campos de Identidade e Correlação

#### 27.3. Campos de Localização Técnica

#### 27.4. Campos da DSL (`log_`)

#### 27.5. Campos de Negócio (`detalhe_`)

#### 27.6. Campos de Auditoria

---

### 28. Padrões Proibidos

#### 28.1. P1 — Saída via Fluxo de Sistema (`System.out`, `printStackTrace`)

#### 28.2. P2 — Concatenação de Strings e Pseudo-JSON

#### 28.3. P3 — Registro Apenas da Mensagem da Exceção

#### 28.4. P4 — Mensagens Genéricas sem Identificadores de Entidade

#### 28.5. P5 — Log-and-Throw sem Contexto Adicional

#### 28.6. P6 — `traceId` Gerado Manualmente

#### 28.7. P7 — MDC sem Limpeza no `finally`

#### 28.8. P8 — Computação Custosa sem Guarda de Nível

#### 28.9. P9 — Manipulação Direta do MDC fora do `GerenciadorContextoLog`

---

### 29. Gestão de Níveis de Severidade

#### 29.1. Tabela de Níveis e Uso Correto

#### 29.2. `FATAL` no JBoss Logging vs. SLF4J

#### 29.3. Ativação Dinâmica de `DEBUG` em Produção

---

### 30. Checklist de Code Review

---

### 31. Ciclo de Melhoria Contínua

#### 31.1. Retroalimentação Pós-Incidente

#### 31.2. Métricas de Qualidade do Pipeline de Telemetria

---

### 32. Fora do Escopo

#### 32.1. API Fluent Direta do SLF4J 2.x

#### 32.2. `ExceptionReporter` e Backends de Rastreamento (v0.3)

#### 32.3. `AuditRecord` e `@Auditable` (v0.3)

#### 32.4. Output GELF/Graylog Nativo na Aplicação

#### 32.5. Log Rotation e Escrita em Arquivo

#### 32.6. `@AroundInvoke` Manual pelo Desenvolvedor

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
- Benjamin Sigelman et al. — [Dapper, a Large-Scale Distributed Systems Tracing Infrastructure](https://research.google/pubs/pub36356/) (Google, 2010)
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
- [Quarkus — OpenTelemetry Guide](https://quarkus.io/guides/opentelemetry)
- [Quarkus — Micrometer Metrics Guide](https://quarkus.io/guides/telemetry-micrometer)
- [Quarkus — Observability Guide](https://quarkus.io/guides/observability)
- [Elasticsearch Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/)

**Ferramentas:**
- [Jaeger](https://www.jaegertracing.io/)
- [Grafana Tempo](https://grafana.com/oss/tempo/)
- [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/)
- [Grafana Loki](https://grafana.com/oss/loki/)
- [Prometheus](https://prometheus.io/)
- [Grafana](https://grafana.com/)
- [Elasticsearch + Kibana (ELK)](https://www.elastic.co/)