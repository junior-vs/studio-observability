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
  - [10. A DSL `Log` — Enforcement do 5W1H](#10-a-dsl-log--enforcement-do-5w1h)
  - [11. `@Logged` e `LogInterceptor`](#11-logged-e-loginterceptor)
  - [12. `SanitizadorDados` — Proteção de Dados Sensíveis](#12-sanitizadordados--proteção-de-dados-sensíveis)
  - [13. Eventos de Negócio, Auditoria e KEDB](#13-eventos-de-negócio-auditoria-e-kedb)
- [Parte IV — Implementação: Rastreamento Distribuído](#parte-iv--implementação-rastreamento-distribuído)
  - [14. Arquitetura da Camada de Tracing](#14-arquitetura-da-camada-de-tracing)
  - [15. `@Traced` e `TracingInterceptor`](#15-traced-e-tracinginterceptor)
  - [16. `GerenciadorTracing` — Ciclo de Vida do Span](#16-gerenciadortracing--ciclo-de-vida-do-span)
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
| `Log` (JSON estruturado) | Sim | Sim | Sim |

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
Log
    .registrando(PedidoEvent.FALHA_PERSISTENCIA_PEDIDO)
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

**A recomendação para ambientes container-native é emitir para stdout e não para o coletor diretamente.** Containers descartáveis normalmente não devem gerenciar arquivos. A governança de armazenamento e retenção costuma ser responsabilidade da plataforma de orquestração e do coletor. A biblioteca, porém, não impõe política produtiva sobre escrita em arquivo; essa decisão pertence ao projeto consumidor.

**O transporte usa SSL/TLS em todos os segmentos.** Logs transmitidos em texto claro entre o container e o coletor podem expor campos de contexto — incluindo campos de identidade não mascarados que escaparam ao `SanitizadorDados`. O pipeline FluentBit → Loki/Elasticsearch deve usar TLS de ponta a ponta.

---

### 3. O Framework 5W1H — Anatomia do Evento Semântico

> O framework 5W1H atua como uma restrição de engenharia que garante que cada evento possua contexto suficiente para ser reconstruído sem necessidade de depuração local.

Cada evento de log deve responder a seis perguntas de engenharia. A tabela abaixo mapeia cada dimensão ao seu atributo técnico e à sua fonte de dados na plataforma:

| Dimensão | Pergunta de Engenharia | Atributo Técnico | Fonte de Dados |
|---|---|---|---|
| **Who** | Quem é o ator? Quem desencadeou a ação? | `userId`, `applicationName` | `SecurityIdentity` / JWT / `quarkus.application.name` |
| **What** | Qual fato ou transição de estado ocorreu? | `message`, `detalhe_eventType` | DSL `.registrando(Event)` / enum de negócio |
| **When** | Qual a posição precisa na linha do tempo? | `timestamp` (UTC/ISO 8601) | Runtime / Clock sincronizado via NTP |
| **Where** | Em que serviço, classe e fluxo? | `traceId`, `spanId`, `log_classe`, `log_metodo` | OTel SDK / MDC / Interceptores CDI |
| **Why** | Qual a motivação ou causa de negócio? | `log_motivo`, `detalhe_errorCode` | Lógica de domínio via `.porque()` |
| **How** | Por qual ponto de entrada o evento chegou ao sistema? | `log_entrypoint` | DSL `.como(Entrypoint)` / enum de entrada |

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

É a dimensão mais visível do log — o `message` aparece em primeiro lugar no Kibana e no Grafana Loki. Na biblioteca, o `message` vem de um `Event`, normalmente implementado por um enum. Mensagens vagas são os maiores inimigos do MTTR: um evento como `ERRO_PROCESSAMENTO` pode corresponder a centenas de situações distintas sem nenhuma pista sobre o que falhou.

**Regras para mensagens:**

- Usar linguagem factual no passado. `"Pedido criado"`, `"Login falhou"`, `"Pagamento recusado"` são fatos. `"Criando pedido..."` é uma intenção que pode nunca ter sido concluída.
- Manter o texto da `message` estável entre ocorrências do mesmo tipo de evento. Informações variáveis — IDs, valores, timestamps — pertencem a campos estruturados via `.comDetalhe()`, nunca interpoladas na mensagem. Isso habilita fingerprinting automático no Kibana.
- Não duplicar informações já presentes em campos estruturados.
- Declarar o evento via `Event`. Texto livre em `.registrando("...")` não faz parte do contrato público; cada domínio pode criar seus próprios enums implementando `Event`.

```java
// PROIBIDO — vago, sem fingerprinting possível
Log.registrando(EventoGenerico.ERRO_PROCESSAMENTO)...

// CORRETO — específico, message estável, dados variáveis em campos dedicados
Log
    .registrando(PagamentoEvent.FALHA_PROCESSAR_PAGAMENTO)
    .em(PagamentoService.class, "processar")
    .comDetalhe("pedidoId", pedidoId)
    .erro(e);
```

**Eventos técnicos vs. eventos de negócio**

O *What* abrange dois tipos:

- **Eventos técnicos** — falhas de integração, exceções, estados de fluxo interno. Destinados a engenheiros e SRE.
- **Eventos de negócio** — `ORDER_COMPLETED`, `CHECKOUT_STARTED`, `PAYMENT_FAILED`. Destinados também a times de produto e analytics. Devem incluir o campo canônico `detalhe_eventType` para serem identificáveis como categoria distinta nas ferramentas de observabilidade, sem depender de parse do campo `message`.

```java
Log
    .registrando(PedidoEvent.PEDIDO_CONCLUIDO)
    .em(PedidoService.class, "concluir")
    .comDetalhe("eventType",   PedidoEvent.PEDIDO_CONCLUIDO.name())
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

**Localização técnica no código** — registrada explicitamente via `.em(Classe.class, "metodo")` ou automaticamente via `.aqui()`, informa a origem precisa do evento sem necessidade de interpretar stack traces completos para eventos não excepcionais. Quando há exceção, o stack trace completo é serializado automaticamente pelo formatador.

```java
Log
    .registrando(PedidoEvent.PEDIDO_CRIADO)
    .em(PedidoService.class, "criar")   // ← classe e método no JSON
    .info();
```

Quando a localização explícita seria apenas repetição do ponto de chamada, `.aqui()` captura a classe e o método reais do consumidor, ignorando frames internos da biblioteca:

```java
Log
    .registrando(PedidoEvent.PEDIDO_CRIADO)
    .aqui()
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
Log
    .registrando(PagamentoEvent.PAGAMENTO_RECUSADO)
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

#### 3.6. How — Entrypoint de Origem

A dimensão *How* responde: **por qual ponto de entrada este evento chegou ao sistema?**

No JSON desta biblioteca, essa dimensão aparece no campo `log_entrypoint`.

O *How* é o contexto arquitetural do evento — informa se a ação foi disparada por uma requisição HTTP síncrona, uma mensagem de fila assíncrona, um job agendado ou uma chamada interna. Essa informação é essencial para distinguir comportamentos esperados de comportamentos anômalos. Assim como `Event`, o entrypoint é restrito por contrato: `.como(...)` aceita implementações de `Entrypoint`, não texto livre.

| Evento | Entrypoint | Interpretação |
|---|---|---|
| `LOGIN_FAILED` | `EntrypointEnum.API_REST` | Possível tentativa de força bruta |
| `LOGIN_FAILED` | `EntrypointEnum.SCHEDULER` | Comportamento esperado em job controlado |
| `PAYMENT_FAILED` | `EntrypointEnum.KAFKA_CONSUMER` | Falha assíncrona — investigar DLQ |
| `PAYMENT_FAILED` | `EntrypointEnum.API_REST` | Falha síncrona — retornar erro ao cliente |

```java
Log
    .registrando(NotaFiscalEvent.NOTA_FISCAL_PROCESSADA)
    .em(NotaFiscalService.class, "processar")
    .como(EntrypointEnum.SCHEDULER)   // ← How
    .info();
```

Entrypoints comuns no enum padrão: `API_REST`, `KAFKA_CONSUMER`, `SCHEDULER`, `GRPC`. Sistemas de negócio podem criar enums próprios implementando `Entrypoint` quando precisarem de categorias mais específicas.

---

#### 3.7. Casos de Uso Além do Debugging

Um evento com conformidade rigorosa ao 5W1H destranca casos de uso que vão além do diagnóstico de erros:

**Analytics em tempo real.** Eventos como `ORDER_COMPLETED` com `valorTotal`, `currency` e `userId` alimentam dashboards de KPIs diretamente no Kibana ou Grafana, sem necessidade de um banco de dados de analytics separado ou SDK de terceiros. O log estruturado é o pipeline de analytics.

**Conformidade regulatória (LGPD).** A trilha de auditoria construída sobre eventos 5W1H responde exatamente às perguntas exigidas por uma investigação regulatória: quem acessou (Who), quais dados (What), quando (When), de qual sistema (Where), por qual justificativa (Why) e por qual ponto de entrada (How). Isso transforma o log de ferramenta de diagnóstico em evidência probatória.

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

**Manual (customizada):** quando a instrumentação automática não cobre uma operação relevante — um método de negócio crítico, uma etapa de processamento de lote, uma operação com semântica de domínio importante — o desenvolvedor usa a anotação `@WithSpan` ou a API `Tracer` diretamente para criar Spans adicionais com atributos de domínio. Na `lib-logging-quarkus`, essa camada é encapsulada pela anotação `@Traced` e pelo `TracingInterceptor` (seção 15).

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
@Traced
public class PagamentoService {

    public Uni<Pagamento> processar(OrdemPagamento ordem) {
        // Thread A — interceptors executam aqui
        // MDC: { traceId: "4bf9...", spanId: "a3ce..." }
        // MDC.put("spanId", childSpanId) executado pelo TracingInterceptor

        return gateway.processar(ordem)          // retorna Uni — executa em Thread B
            .onItem().invoke(pagamento -> {
                // Thread B — continuation reativa
                // MDC.get("spanId") aqui pode retornar: null, vazio, ou o spanId do Root Span
                // — dependendo de qual thread do pool Vert.x executou este operador

                Log
                    .registrando(PagamentoEvent.GATEWAY_RESPONDEU)
                    .em(PagamentoService.class, "processar")
                    .comDetalhe("pagamentoId", pagamento.getId())
                    .info();
                // JSON resultante: spanId ausente ou incorreto
            });
    }
}
```

O log emitido dentro de `.onItem().invoke()` pode não carregar o `spanId` do Child Span criado pelo `@Traced` — tornando a correlação log↔trace ineficaz exatamente onde mais importa: no continuation reativo onde a resposta do gateway foi processada.

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

**Limitação importante:** a extensão propaga o contexto que estava presente no momento do `subscribe()` — não no momento em que cada operador executa. Isso significa que o MDC escrito pelo `TracingInterceptor` só é propagado para os continuations se o `subscribe()` ocorrer *após* o interceptor ter escrito no MDC. Em uso normal com CDI interceptors, isso é garantido pela ordem de execução dos interceptors — mas deve ser verificado em pipelines reativos construídos manualmente fora do contexto CDI.

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

**Checklist de verificação para métodos reativos com `@Traced`:**

O seguinte comportamento deve ser verificado em code review para qualquer método anotado com `@Traced` que retorne `Uni` ou `Multi`:

- `quarkus-smallrye-context-propagation` está declarado como dependência Maven.
- Logs emitidos dentro de `.onItem()`, `.onFailure()`, `.flatMap()` e outros operadores contêm `spanId` não nulo.
- O `spanId` nos continuations corresponde ao Child Span criado pelo `@Traced` — não ao Root Span da requisição HTTP.
- O `traceId` é consistente entre todos os logs da cadeia reativa da mesma requisição.

A forma mais simples de verificar: emitir um log de teste dentro de um continuation reativo em ambiente de desenvolvimento e inspecionar o JSON no stdout. Se `spanId` for `"0000000000000000"` (valor inválido do OTel) ou ausente, a propagação não está funcionando.

---

## Parte III — Implementação: Logging
## Parte III — Implementação: Logging

### 8. Arquitetura da Biblioteca de Logging

#### 8.1. Estrutura de Pacotes

A biblioteca organiza suas responsabilidades em pacotes com separação explícita de concerns. O artefato é uma biblioteca comum para uso por aplicações Quarkus — não uma extensão Quarkus. Cada pacote tem uma única responsabilidade e não cria dependência circular com os demais:

```
lib-logging-quarkus/
└── src/main/java/br/com/seudominio/log/
    ├── annotations/
    │   ├── Logged.java                  ← @InterceptorBinding CDI (logging)
    │   └── Traced.java               ← @InterceptorBinding CDI (tracing)
    ├── context/
    │   ├── LogContexto.java             ← record imutável com os campos do MDC
    │   ├── GerenciadorContextoLog.java  ← único ponto de escrita do MDC
    │   └── SanitizadorDados.java        ← mascaramento de dados sensíveis
    ├── core/
    │   └── LogEvento.java               ← record imutável da cadeia DSL
    ├── dsl/
    │   ├── Event.java                   ← interface extensível para eventos
    │   ├── EventEnum.java               ← enum padrão de eventos
    │   ├── Entrypoint.java              ← interface extensível para pontos de entrada
    │   ├── EntrypointEnum.java          ← enum padrão de pontos de entrada
    │   ├── LogEtapas.java               ← sealed interface — contrato da cadeia fluente
    │   └── Log.java                     ← ponto de entrada da DSL
    ├── filtro/
    │   └── LogContextoFiltro.java       ← inicialização e limpeza do MDC por requisição
    ├── interceptor/
    │   ├── LogInterceptor.java          ← @Logged: MDC de localização + métricas Micrometer
    │   └── TracingInterceptor.java     ← @Traced: Child Span OTel + MDC de spanId
    └── tracing/
        ├── EnriquecedorTracing.java     ← interface do pipeline de enriquecimento
        ├── EnriquecedorMetadados.java   ← OTel Semantic Conventions (prioridade 10)
        ├── EnriquecedorIdentidade.java  ← enduser.id via SecurityIdentity (prioridade 20)
        └── GerenciadorTracing.java      ← ciclo de vida do Span + sincronização MDC
```

**Princípios de design:**

- `annotations/` — apenas definições de binding CDI, sem lógica.
- `context/` — toda escrita no MDC passa pelo `GerenciadorContextoLog`. Nenhum outro componente chama `MDC.put()` diretamente.
- `dsl/` — `Event` e `Entrypoint` são pontos de extensão controlados por enums; a `sealed interface LogEtapas` com `permits Log` impede que o contrato da cadeia fluente seja estendido acidentalmente fora da biblioteca.
- `interceptor/` — dois interceptors com `@Priority` distintas garantem ordem determinística de execução quando ambos estão presentes no mesmo bean.
- `tracing/` — isolado de `context/` para preservar separação entre responsabilidades de logging e de rastreamento.

Exemplos de uso devem existir em módulo, projeto ou documentação separados. Pacotes de exemplo não fazem parte do artefato principal publicado da biblioteca.

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

O uso de `sealed interface` na DSL é uma decisão de design intencional: `LogEtapas` com `permits Log` torna o contrato da cadeia fluente exclusivo da biblioteca. Nenhuma classe externa pode implementar `LogEtapas` e criar desvios do padrão que passariam despercebidos em code review.

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

**Mecanismo 2 — `finally` nos interceptors:** o `LogInterceptor` remove os campos de localização (`classe`, `metodo`) no bloco `finally` do `@AroundInvoke`. O `TracingInterceptor` restaura o `spanId` do pai no bloco `finally`. Ambos garantem que campos de escopo de método não vazem para além da execução do método interceptado.

```
Requisição HTTP
    │
    ▼
LogContextoFiltro.filter(request)     ← MDC inicializado: userId, applicationName, traceId, spanId
    │
    ▼
TracingInterceptor               ← MDC: spanId atualizado para Child Span
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
TracingInterceptor.finally       ← MDC: spanId restaurado para o pai
    │
    ▼
LogContextoFiltro.filter(response)    ← MDC: limpo completamente (MDC.clear())
```

**Regra de verificação:** qualquer `MDC.put()` no código da aplicação ou da biblioteca que não tenha um `MDC.remove()` ou `MDC.clear()` correspondente em um bloco `finally` é context leak em potencial. Code review deve barrar sistematicamente esse padrão (anti-padrão P7).

---
### 10. A DSL `Log` — Enforcement do 5W1H

#### 10.1. Contrato da Cadeia Fluente

A DSL `Log` não é apenas uma API conveniente — é um **mecanismo de enforcement em tempo de compilação**. O compilador Java impede que um log seja emitido sem as dimensões obrigatórias *What* (`.registrando(Event)`) e *Where* técnico (`.em(...)` ou `.aqui()`). Logs incompletos são erros de compilação, não bugs silenciosos em produção.

Isso é viabilizado pelo uso de `sealed interface` (Java 21) no tipo `LogEtapas`, com `permits` restrito exclusivamente a `Log`. A cadeia de métodos retorna tipos progressivamente mais restritos — cada etapa só expõe os métodos válidos naquele ponto do contrato:

```
Log
    .registrando(evento)           // What  — obrigatório: Event
    .em(classe, metodo)            // Where — explícito
    // ou .aqui()                  // Where — automático, pelo ponto de chamada
  [ .porque(motivo)         ]      // Why   — opcional: causa de negócio
  [ .como(entrypoint)       ]      // How   — opcional: Entrypoint
  [ .comDetalhe(chave, val) ]*     // extra — zero ou mais campos de domínio
    .info() | .debug() | .warn() | .erro(ex) | .erroERelanca(ex)
             └──── terminadores — emitem o evento e encerram a cadeia
```

As dimensões *Who* (`userId`, `applicationName`) e *When* (`timestamp`) são injetadas automaticamente via MDC pelo `GerenciadorContextoLog` — o desenvolvedor não as declara.

Exemplo completo com todas as dimensões:

```java
Log
    .registrando(PagamentoEvent.PAGAMENTO_RECUSADO)       // What
    .em(PagamentoService.class, "processar")                 // Where técnico
    .porque("Gateway recusou — saldo insuficiente")          // Why
    .como(EntrypointEnum.API_REST)                            // How
    .comDetalhe("pedidoId",    pedidoId)
    .comDetalhe("errorCode",   "PAG-4022")
    .comDetalhe("gatewayCode", e.getCodigo())
    .erro(e);
```

JSON resultante:

```json
{
  "timestamp":           "2026-03-11T21:55:00.123Z",
  "level":               "ERROR",
  "message":             "Pagamento recusado",
  "traceId":             "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":              "a3ce929d0e0e4736",
  "userId":              "joao.silva@empresa.com",
  "applicationName":     "pagamentos-service",
  "classe":              "PagamentoService",
  "metodo":              "processar",
  "log_classe":          "PagamentoService",
  "log_metodo":          "processar",
  "log_motivo":          "Gateway recusou — saldo insuficiente",
  "log_entrypoint":       "API_REST",
  "detalhe_pedidoId":    "9912",
  "detalhe_errorCode":   "PAG-4022",
  "detalhe_gatewayCode": "INSUFFICIENT_FUNDS",
  "stack_trace":         "br.com.dominio.GatewayException: INSUFFICIENT_FUNDS\n\tat ..."
}
```

---

#### 10.2. Dimensões Obrigatórias vs. Opcionais

| Dimensão | Método DSL | Obrigatoriedade | Campo JSON resultante |
|---|---|---|---|
| *What* | `.registrando(Event)` | **Obrigatório** — erro de compilação se ausente | `message` |
| *Where* técnico | `.em(Classe.class, "metodo")` ou `.aqui()` | **Obrigatório** — erro de compilação se ausente | `log_classe`, `log_metodo` |
| *Why* | `.porque(texto)` | Opcional pela DSL; obrigatório por convenção em `ERROR` e `WARN` | `log_motivo` |
| *How* | `.como(Entrypoint)` | Opcional | `log_entrypoint` |
| Detalhes | `.comDetalhe(chave, valor)` | Opcional; repetível (zero ou mais) | `detalhe_{chave}` |
| *Who* | — | Automático via MDC | `userId`, `applicationName` |
| *When* | — | Automático via formatador | `timestamp` |
| Correlação | — | Automático via MDC + OTel | `traceId`, `spanId` |

**Convenção para eventos de negócio:** eventos de negócio devem ser representados por enums de domínio que implementam `Event`. Quando o mesmo evento também precisar ser agrupado em ferramentas de analytics, incluir `.comDetalhe("eventType", evento.name())`. O campo `detalhe_eventType` é o que torna o evento identificável como categoria distinta em queries — sem depender de parsear o campo `message`.

**Governança de alta cardinalidade:** campos como `pedidoId`, `userId`, `valorTotal` devem ser declarados via `.comDetalhe()` — nunca interpolados na mensagem. Isso preserva o fingerprinting estável do `message` e mantém os dados variáveis em campos indexáveis e consultáveis de forma independente.

---

#### 10.3. Terminadores de Nível

Os terminadores encerram a cadeia fluente, determinam o nível de severidade do evento e acionam a emissão do JSON. São o único ponto onde o evento é materializado — nenhuma I/O ocorre antes.

| Terminador | Nível | Comportamento |
|---|---|---|
| `.info()` | `INFO` | Emite o evento. Operações que alteram estado: persistência, autenticação, chamadas externas. |
| `.debug()` | `DEBUG` | Emite o evento. Fluxos internos e decisões condicionais. Não habilitado em produção por padrão. |
| `.warn()` | `WARN` | Emite o evento. Situações anômalas recuperáveis: fallbacks ativados, validações rejeitadas. |
| `.erro(e)` | `ERROR` | Emite o evento com stack trace completo. Exceção serializada como `stack_trace`. Loga e retorna — não relança. |
| `.erroERelanca(e)` | `ERROR` | Emite o evento com stack trace completo e **relança a exceção original**. Uso: fronteiras onde a exceção deve ser propagada para o chamador após registro. |

**`.erro(e)` vs. `.erroERelanca(e)` — quando usar cada um:**

```java
// .erro(e) — a exceção é tratada aqui; o fluxo continua
catch (GatewayException e) {
    Log
        .registrando(PagamentoEvent.FALHA_PROCESSAR_PAGAMENTO)
        .em(PagamentoService.class, "processar")
        .porque("Gateway retornou erro definitivo")
        .comDetalhe("errorCode", "PAG-4022")
        .erro(e);
    return ResultadoPagamento.falhou(e.getCodigo());
}

// .erroERelanca(e) — a exceção deve ser propagada; o log adiciona contexto antes de subir
catch (DatabaseException e) {
    Log
        .registrando(EventError.FALHA_CRITICA_PERSISTENCIA)
        .em(PedidoRepository.class, "salvar")
        .porque("Conexão com banco indisponível")
        .comDetalhe("pedidoId", pedido.getId())
        .erroERelanca(e);
    // A exceção sobe para o chamador — nunca alcança esta linha
}
```

**Guarda de nível para computações custosas:** se a construção do contexto envolve serialização ou operação com custo de CPU, proteger com guarda de nível antes de montar a cadeia:

```java
// Sem guarda — serializa o objeto mesmo se DEBUG estiver desabilitado
Log.registrando(PedidoEvent.ESTADO_DO_PEDIDO_COM_PAYLOAD_INTERPOLADO)...

// Correto — custo pago apenas se o nível estiver habilitado
if (log.isDebugEnabled()) {
    Log
        .registrando(PedidoEvent.ESTADO_INTERMEDIARIO)
        .em(PedidoService.class, "processar")
        .comDetalhe("pedido", objectMapper.writeValueAsString(pedido))
        .debug();
}
```

---

### 11. `@Logged` e `LogInterceptor`

#### 11.1. Injeção Automática de Localização Técnica no MDC

A anotação `@Logged` é um `@InterceptorBinding` CDI que, quando aplicada a um bean ou método, ativa o `LogInterceptor` — um interceptor `@AroundInvoke` que injeta automaticamente os campos `classe` e `metodo` no MDC antes da execução do método e os remove no `finally`.

```java
@Logged
@ApplicationScoped
public class PedidoService {

    public Pedido criar(NovoPedidoRequest request) {
        // MDC contém: { classe: "PedidoService", metodo: "criar" }
        // Todos os logs emitidos dentro deste método incluem esses campos automaticamente

        Log
            .registrando(PedidoEvent.PEDIDO_CRIADO)
            .em(PedidoService.class, "criar")
            .comDetalhe("pedidoId", pedido.getId())
            .info();

        return pedido;
    }
}
```

Comportamento do interceptor:

```java
@Interceptor
@Logged
@Priority(2000)
public class LogInterceptor {

    @Inject
    GerenciadorContextoLog gerenciador;

    @Inject
    MeterRegistry registry;

    @AroundInvoke
    public Object interceptar(InvocationContext contexto) throws Exception {
        var metodo = contexto.getMethod();
        var classe = metodo.getDeclaringClass().getSimpleName();
        var nomeMetodo = metodo.getName();

        gerenciador.registrarLocalizacao(classe, nomeMetodo);
        var inicio = System.nanoTime();

        try {
            return contexto.proceed();
        } catch (Exception e) {
            // Incrementa counter de falhas com tipo de exceção como tag
            registry.counter("metodo.falha",
                "classe",   classe,
                "metodo",   nomeMetodo,
                "excecao",  e.getClass().getSimpleName()
            ).increment();
            throw e;
        } finally {
            // Registra duração no Timer — executado sempre, inclusive em caso de exceção
            registry.timer("metodo.execucao",
                "classe", classe,
                "metodo", nomeMetodo
            ).record(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);

            // Limpeza garantida — previne context leak entre métodos consecutivos
            gerenciador.removerLocalizacao();
        }
    }
}
```

**`@Logged` em classe vs. em método:** aplicar `@Logged` na classe intercepta todos os métodos públicos do bean. Para interceptar apenas métodos específicos — por exemplo, excluir métodos de ciclo de vida do CDI — aplicar `@Logged` diretamente no método.

---

#### 11.2. Métricas Automáticas: `metodo.execucao` e `metodo.falha`

O `LogInterceptor` registra automaticamente duas métricas para cada método `@Logged`, sem nenhuma instrumentação adicional:

**`metodo.execucao` — Timer com histograma**

Registra a duração de cada invocação bem-sucedida e com erro. Tags: `classe` e `metodo`. Com `publishPercentileHistogram()` habilitado no `MeterRegistry`, expõe percentis p50, p95 e p99 diretamente via Prometheus.

```promql
# p99 de latência do PedidoService.criar nos últimos 5 minutos
histogram_quantile(0.99,
  rate(metodo_execucao_seconds_bucket{
    classe="PedidoService", metodo="criar"
  }[5m])
)
```

**`metodo.falha` — Counter por tipo de exceção**

Incrementado a cada exceção lançada pelo método. A tag `excecao` carrega o `getSimpleName()` da exceção — tornando possível detectar picos de um tipo específico de exceção sem abrir os logs:

```promql
# Taxa de GatewayException por segundo nos últimos 2 minutos
rate(metodo_falha_total{
  classe="PagamentoService",
  metodo="processar",
  excecao="GatewayException"
}[2m])
```

Correlação típica durante um incidente: o counter `metodo.falha` dispara o alerta; o `traceId` em um log de `ERROR` correlacionado leva ao trace no Jaeger; o trace aponta o Child Span com latência anômala.

---

#### 11.3. Ativação e Prioridade CDI

No Quarkus com ArC, interceptors anotados com `@Interceptor` são descobertos automaticamente — não requer declaração em `beans.xml`. A `@Priority` determina a ordem de execução quando múltiplos interceptors se sobrepõem no mesmo método.

**Prioridade e ordem de execução com `@Traced`:**

A anotação `@Traced` (seção 15) cria um Child Span OTel por método instrumentado. Para que o `spanId` do Child Span apareça corretamente nos logs emitidos dentro do método, o `TracingInterceptor` deve executar **antes** do `LogInterceptor` — ou seja, deve ter prioridade numericamente menor:

```java
@Interceptor @Traced @Priority(1000)  // executa primeiro — cria o Child Span
public class TracingInterceptor { ... }

@Interceptor @Logged    @Priority(2000)  // executa depois — lê o spanId já atualizado
public class LogInterceptor { ... }
```

Ordem resultante de execução (pilha de interceptors):

```
Requisição HTTP chega
    └─ TracingInterceptor.antes()   // cria Child Span, atualiza MDC com spanId filho
         └─ LogInterceptor.antes()       // registra classe/metodo no MDC, inicia Timer
              └─ método de negócio()     // todos os logs aqui têm spanId do Child Span
         └─ LogInterceptor.finally()     // para Timer, remove classe/metodo do MDC
    └─ TracingInterceptor.finally() // encerra Child Span, restaura spanId do pai
```

Se a ordem for invertida — `LogInterceptor` com prioridade menor que `TracingInterceptor` — o Timer começa antes do Child Span ser criado, e o `spanId` nos logs pode refletir o span pai em vez do Child Span do método. A convenção de prioridade `1000/2000` deve ser mantida sem alteração.

---
### 12. `SanitizadorDados` — Proteção de Dados Sensíveis

#### 12.1. Categorias de Mascaramento Automático

O `SanitizadorDados` é invocado automaticamente pela DSL antes de qualquer campo declarado via `.comDetalhe(chave, valor)` ser serializado no JSON. A interceptação ocorre pelo nome da chave — o desenvolvedor não precisa indicar que um campo é sensível; o sanitizador aplica a categoria correspondente por inferência do nome.

Dois graus de proteção são aplicados:

| Categoria | Chaves interceptadas | Valor no JSON | Justificativa |
|---|---|---|---|
| **Credenciais** | `password`, `senha`, `token`, `accesstoken`, `refreshtoken`, `authorization`, `apikey`, `cvv`, `secret` | `"****"` | Credenciais não devem ser inspecionáveis nem mesmo por operadores com acesso ao agregador |
| **Dados pessoais** | `cpf`, `rg`, `email`, `celular`, `cardnumber`, `numerocartao` | `"[PROTEGIDO]"` | Conformidade LGPD — dado pessoal identificável não deve trafegar em claro nos logs |
| **Demais** | qualquer outra chave | valor original | Sem restrição |

O mascaramento é aplicado independente de maiúsculas/minúsculas na chave — `Email`, `EMAIL` e `email` produzem o mesmo resultado.

Implementação com `switch` de pattern matching (Java 21):

```java
@ApplicationScoped
public class SanitizadorDados {

    public Object sanitizar(String chave, Object valor) {
        return switch (categorizarChave(chave.toLowerCase())) {
            case CREDENCIAL    -> "****";
            case DADO_PESSOAL  -> "[PROTEGIDO]";
            case INOFENSIVO    -> valor;
        };
    }

    private Categoria categorizarChave(String chaveNormalizada) {
        return switch (chaveNormalizada) {
            case "password", "senha", "token", "accesstoken",
                 "refreshtoken", "authorization", "apikey",
                 "cvv", "secret"
                    -> Categoria.CREDENCIAL;
            case "cpf", "rg", "email", "celular",
                 "cardnumber", "numerocartao"
                    -> Categoria.DADO_PESSOAL;
            default -> Categoria.INOFENSIVO;
        };
    }

    private enum Categoria { CREDENCIAL, DADO_PESSOAL, INOFENSIVO }
}
```

O uso de `switch` com pattern matching em vez de cadeias de `if-else` garante exaustividade verificada pelo compilador — adicionar uma nova categoria sem tratar os casos é erro de compilação.

---

#### 12.2. Redação Total: Responsabilidade do Chamador

O `SanitizadorDados` substitui o valor de um campo por um marcador — mas o **campo ainda aparece no JSON**. Para campos que exigem **redação completa** (omissão total do campo no JSON), a exclusão deve ocorrer antes da chamada a `.comDetalhe()`.

```java
// Mascaramento automático — campo aparece no JSON com valor substituído
.comDetalhe("token", tokenJWT)
// Resultado: "detalhe_token": "****"

// Redação total — campo omitido completamente do JSON
// O chamador decide não incluir o campo
if (deveRegistrarToken) {
    logBuilder.comDetalhe("tokenHash", DigestUtils.sha256Hex(tokenJWT));
}
// Resultado: campo ausente do JSON
```

Casos que exigem redação total em vez de mascaramento:

- Campos cujo nome ou a presença do campo em si é informação sensível (ex: campo `biometria` — mesmo `"****"` revela que dados biométricos foram processados).
- Campos que combinados com outros campos permitem re-identificação (ex: `dataNascimento` + `cep` + `genero`).
- Campos de resposta de APIs financeiras que podem conter dados regulados além das chaves interceptadas.

**O sanitizador é a última linha de defesa — não a única.** A revisão arquitetural de quais campos devem ou não aparecer nos logs é responsabilidade do time de desenvolvimento e do DPO. O `SanitizadorDados` previne vazamentos por descuido; não substitui o design deliberado de privacidade.

---

#### 12.3. Conformidade LGPD

A LGPD (Lei 13.709/2018) exige que dados pessoais identificáveis sejam tratados com finalidade específica e com acesso restrito. Logs de produção acessados por operadores, engenheiros de SRE e ferramentas de analytics constituem processamento de dados pessoais — e devem obedecer aos mesmos princípios de minimização de dados que qualquer outro processamento.

Implicações diretas para a biblioteca:

**Minimização:** registrar apenas os campos necessários para o diagnóstico. `userId` (identificador) é necessário; CPF completo, não. O `SanitizadorDados` faz essa separação automaticamente para as categorias conhecidas.

**Rastreabilidade de acesso:** o campo `userId` em cada evento é o que permite responder, diante de uma auditoria regulatória, *quais dados de qual usuário foram processados, quando e por qual sistema*. A presença consistente do `userId` nos logs não é apenas uma boa prática de observabilidade — é um requisito regulatório para trilha de auditoria.

**Retenção:** a política de retenção de logs (ex: 90 dias no Elasticsearch, 1 ano no cold storage) deve ser documentada e cumprir os prazos da LGPD. Logs com dados pessoais — mesmo mascarados — devem respeitar o período de retenção acordado com o titular dos dados.

**Direito ao esquecimento:** se um titular solicitar a exclusão de seus dados, logs que contenham `userId` identificável precisam ser tratados. A arquitetura de coleta (Elasticsearch com retenção por índice diário ou semanal) deve suportar a exclusão de registros por `userId` — ou a política de mascaramento deve garantir que os logs não sejam reidentificáveis após o prazo de retenção.

---

### 13. Eventos de Negócio, Auditoria e KEDB

#### 13.1. Distinção entre Eventos Técnicos e de Negócio

Os logs de uma aplicação servem a dois públicos distintos com necessidades diferentes:

| Tipo de Evento | Público | Características | Exemplo |
|---|---|---|---|
| **Técnico** | Engenheiros, SRE | Falhas de integração, exceções, estados de fluxo interno, latência | `"Timeout ao conectar no banco"` |
| **De Negócio** | Produto, Analytics, Compliance | Transições de estado do domínio, KPIs, trilha de auditoria | `"Pedido concluído"` com `ORDER_COMPLETED` |

A diferença não é apenas semântica — é de rastreabilidade. Ferramentas de analytics e dashboards de produto precisam filtrar eventos de negócio sem varrer eventos técnicos, e vice-versa. O campo `detalhe_eventType` é o discriminador que torna isso possível sem parsear o `message`.

Eventos de negócio devem ser tratados como cidadãos de primeira classe na arquitetura de observabilidade — não como logs incidentais do fluxo de negócio. Majors et al. (Observability Engineering, cap. 3) descrevem exatamente esse padrão: eventos de negócio ricos em contexto são o que diferencia observabilidade de monitoramento.

---

#### 13.2. `Event` e `eventType` como Identificadores Canônicos

O identificador primário do tipo de evento de negócio é o próprio `Event` passado a `Log.registrando(Event)`. Cada domínio deve modelar seus eventos como enum implementando a interface `Event`; a biblioteca fornece um enum padrão para eventos comuns.

Quando o evento também precisa ser consultado como dimensão explícita em dashboards ou analytics, o campo `detalhe_eventType` deve receber o nome canônico do enum. Deve ser uma string em `SCREAMING_SNAKE_CASE`, estável entre versões:

```java
Log
    .registrando(PedidoEvent.PEDIDO_CONCLUIDO)
    .em(PedidoService.class, "concluir")
    .porque("Pagamento confirmado pelo gateway")
    .como(EntrypointEnum.API_REST)
    .comDetalhe("eventType",  PedidoEvent.PEDIDO_CONCLUIDO.name())
    .comDetalhe("pedidoId",   pedido.getId())
    .comDetalhe("valorTotal", pedido.getValorTotal())
    .comDetalhe("currency",   "BRL")
    .comDetalhe("userId",     pedido.getUserId())
    .info();
```

JSON resultante:

```json
{
  "timestamp":           "2026-03-11T21:55:00.123Z",
  "level":               "INFO",
  "message":             "Pedido concluído",
  "traceId":             "4bf92f3577b34da6a3ce929d0e0e4736",
  "userId":              "joao.silva@empresa.com",
  "applicationName":     "pedidos-service",
  "detalhe_eventType":   "ORDER_COMPLETED",
  "detalhe_pedidoId":    "9912",
  "detalhe_valorTotal":  349.90,
  "detalhe_currency":    "BRL"
}
```

Query no Kibana para dashboard de analytics em tempo real:

```
detalhe_eventType: "ORDER_COMPLETED" AND @timestamp:[now-1h TO now]
```

Query para taxa de conversão do dia:

```
detalhe_eventType: "ORDER_COMPLETED" / detalhe_eventType: "CHECKOUT_STARTED"
```

**Catálogo de eventos:** cada domínio deve manter um catálogo documentado dos enums que implementam `Event`. Isso previne variações como `ORDER_COMPLETE` vs `ORDER_COMPLETED` vs `PEDIDO_CONCLUIDO` que fragmentam as queries de analytics.

---

#### 13.3. Códigos de Erro e Base de Conhecimento (KEDB)

Eventos críticos de negócio e infraestrutura devem receber **códigos de erro únicos e estáveis** (ex: `PAG-4022`, `VND-3001`, `NF-5010`). Esses códigos são a chave de ligação entre o evento em produção e a **KEDB** (*Known Error Database*) — repositório interno que documenta causa raiz, impacto e procedimento de remediação para cada código.

O contrato do código de erro:

| Atributo | Requisito |
|---|---|
| **Unicidade** | Um código identifica um único tipo de falha — nunca reutilizar |
| **Estabilidade** | O código não muda entre versões — é referenciado em runbooks, alertas e dashboards |
| **Prefixo de domínio** | Formato `{DOMÍNIO}-{NÚMERO}` — `PAG` para pagamentos, `VND` para vendas, `NF` para nota fiscal |
| **Sem semântica no número** | O número é opaco — não codifica severidade, categoria ou versão |

```java
Log
    .registrando(PagamentoEvent.FALHA_PROCESSAR_PAGAMENTO)
    .em(PagamentoService.class, "processar")
    .porque("Gateway recusou a transação")
    .comDetalhe("errorCode",         "PAG-4022")
    .comDetalhe("pedidoId",          pedidoId)
    .comDetalhe("codigoErroGateway", e.getCodigo())
    .erro(e);
```

Quando um operador recebe um alerta com `detalhe_errorCode: "PAG-4022"` às 3 da manhã, consulta a KEDB e executa o procedimento documentado — sem precisar interpretar o `message`, sem precisar abrir o código-fonte, sem depender do desenvolvedor que escreveu o código. O código é estável; a mensagem pode variar entre versões.

**Integração com alertas:** o `errorCode` deve ser uma tag em alertas do Alertmanager/Grafana. Isso permite que um único alerta genérico ("taxa de erro acima do limiar") inclua o `errorCode` como label, direcionando automaticamente o operador para o runbook correto sem análise manual dos logs.

---

#### 13.4. Campos de Auditoria: `actorId`, `entityType`, `stateBefore`, `stateAfter`

Eventos de auditoria documentam **quem fez o quê sobre qual entidade**, com o estado da entidade antes e depois da operação. São a evidência técnica para conformidade regulatória (LGPD, SOC 2), investigações de segurança e resolução de disputas.

**Estado atual (v0.2):** o `AuditRecord` e a anotação `@Auditable` estão planejados para v0.3. Até então, eventos de auditoria são registrados via `Log` com os campos obrigatórios declarados explicitamente via `.comDetalhe()`.

**Campos obrigatórios de auditoria:**

| Campo | Tipo | Descrição | Declaração |
|---|---|---|---|
| `actorId` | `string` | Identificador de quem executou a ação — pode diferir do `userId` da requisição (ex: operador agindo em nome de cliente) | `.comDetalhe("actorId", ...)` |
| `entityType` | `string` | Tipo da entidade afetada — `"Pedido"`, `"Contrato"`, `"Usuario"` | `.comDetalhe("entityType", ...)` |
| `entityId` | `string` | Identificador da instância da entidade afetada | `.comDetalhe("entityId", ...)` |
| `stateBefore` | `string` | Estado da entidade antes da operação — serializado como JSON string | `.comDetalhe("stateBefore", ...)` |
| `stateAfter` | `string` | Estado da entidade após a operação | `.comDetalhe("stateAfter", ...)` |
| `outcome` | `string` | Resultado da operação: `"SUCCESS"`, `"FAILURE"`, `"PARTIAL"` | `.comDetalhe("outcome", ...)` |

```java
Log
    .registrando(ContratoEvent.CONTRATO_CANCELADO)
    .em(ContratoService.class, "cancelar")
    .porque("Solicitação do cliente via canal de atendimento")
    .como(EntrypointEnum.API_REST)
    .comDetalhe("eventType",    ContratoEvent.CONTRATO_CANCELADO.name())
    .comDetalhe("actorId",      operadorId)
    .comDetalhe("entityType",   "Contrato")
    .comDetalhe("entityId",     contratoId)
    .comDetalhe("stateBefore",  contratoAntes.toJsonString())
    .comDetalhe("stateAfter",   contratoDepois.toJsonString())
    .comDetalhe("outcome",      "SUCCESS")
    .info();
```

**Inconsistência de nomenclatura resolvida:** o `FIELD_NAMES.md` define `actorId`, `entityType` e `stateBefore` como campos canônicos sem prefixo. Na v0.2, declarados via `.comDetalhe()`, esses campos aparecem no JSON como `detalhe_actorId`, `detalhe_entityType` e `detalhe_stateBefore`. O prefixo `detalhe_` será removido quando a abstração `@Auditable` for implementada na v0.3 — que emitirá esses campos diretamente como chaves de primeiro nível sem prefixo. Queries construídas agora devem usar `detalhe_actorId`; serão atualizadas na migração para v0.3.

**`actorId` vs. `userId`:** o `userId` (campo de correlação automático) identifica o usuário autenticado na requisição HTTP. O `actorId` (campo de auditoria explícito) identifica quem é o responsável pela ação de negócio — que pode ser diferente em cenários de impersonação, operações de backoffice ou processamentos em lote onde um operador age em nome de outro usuário.

---

## Parte IV — Implementação: Rastreamento Distribuído

### 14. Arquitetura da Camada de Tracing

#### 14.1. Estrutura de Pacotes

A camada de tracing é organizada em `tracing/` — separada do pacote `context/` de logging para preservar a separação de responsabilidades. Cada pacote tem um contrato único e um ciclo de evolução independente.

```
lib-logging-quarkus/
└── src/main/java/br/com/seudominio/log/
    ├── annotations/
    │   ├── Logged.java                    ← @InterceptorBinding CDI — logging
    │   └── Traced.java                 ← @InterceptorBinding CDI — tracing
    ├── context/
    │   ├── LogContexto.java
    │   ├── GerenciadorContextoLog.java
    │   └── SanitizadorDados.java
    ├── core/
    │   └── LogEvento.java
    ├── dsl/
    │   ├── LogEtapas.java
    │   └── Log.java
    ├── filtro/
    │   └── LogContextoFiltro.java
    ├── interceptor/
    │   ├── LogInterceptor.java
    │   └── TracingInterceptor.java   ← CDI @AroundInvoke + OTel Tracer
    └── tracing/
        ├── EnriquecedorTracing.java          ← interface do pipeline de enriquecimento
        ├── EnriquecedorMetadados.java     ← atributos técnicos OTel (prioridade 10)
        ├── EnriquecedorIdentidade.java    ← enduser.id via SecurityIdentity (prioridade 20)
        └── GerenciadorTracing.java   ← ciclo de vida do Span + sincronização MDC
```

A implementação satisfaz os cinco requisitos do padrão Distributed Tracing (Richardson — microservices.io):

| Requisito | Mecanismo | Componente |
|---|---|---|
| Geração automática de `traceId` e `spanId` | `quarkus-opentelemetry` auto-instrumenta endpoints JAX-RS | Nativo — sem código adicional |
| Propagação W3C TraceContext entre serviços | Cabeçalho `traceparent` via `quarkus-opentelemetry` | Nativo — sem código adicional |
| Registro de início, fim e metadados por span | CDI `@AroundInvoke` com OTel `Tracer` | `@Traced` + `TracingInterceptor` |
| Exportação para backend configurável | `application.properties` + OTLP | Configuração — sem código adicional |
| `traceId` em cada linha de log | MDC populado pelo `GerenciadorContextoLog` | `LogContextoFiltro` + `GerenciadorContextoLog` |

---

#### 14.2. Dependências Maven

A API do OpenTelemetry já está disponível transitivamente via `quarkus-opentelemetry`. Nenhuma dependência adicional é necessária para a camada de tracing.

```xml
<!--
    Provê auto-instrumentação HTTP, cliente REST e banco de dados.
    Expõe io.opentelemetry.api.trace.Tracer injetável via CDI.
    Exporta traces via OTLP para o backend configurado.
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>

<!--
    Garante propagação do span OTel ativo e do MDC
    em pipelines reativos Mutiny/Vert.x.
    Obrigatório para métodos que retornam Uni<T> ou Multi<T>.
    Ver seção 7 para o comportamento sem esta dependência.
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-context-propagation</artifactId>
</dependency>
```

---

### 15. `@Traced` e `TracingInterceptor`

#### 15.1. Ciclo de Vida do Child Span

A anotação `@Traced` é um `@InterceptorBinding` CDI que ativa o `TracingInterceptor` — um interceptor `@AroundInvoke` que encapsula o ciclo de vida completo de um Child Span OTel para cada método interceptado.

```java
package br.com.seudominio.log.annotations;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.*;

/**
 * Ativa rastreamento distribuído para um bean ou método CDI.
 *
 * Quando aplicada, o TracingInterceptor cria um Child Span no span OTel ativo,
 * registra metadados da operação (classe, método, início/fim) e propaga o spanId
 * atualizado para o MDC — mantendo a correlação com os logs emitidos dentro do método.
 *
 * Pode ser combinada com @Logged no mesmo bean sem conflito:
 * @Logged gerencia o MDC de logging; @Traced gerencia o span OTel.
 * Quando usadas juntas, a ordem de execução é controlada por @Priority:
 * TracingInterceptor executa primeiro (cria o span), depois LogInterceptor.
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Traced {}
```

O ciclo de vida do Child Span em cada invocação:

```
Antes do método:
  1. Captura spanId do pai (MDC corrente) — para restauração no finally
  2. Cria Child Span via Tracer.spanBuilder(nomeSpan).setParent(Context.current())
  3. Torna o Child Span corrente via span.makeCurrent() — retorna Scope
  4. Atualiza MDC: spanId ← spanId do Child Span
  5. Executa pipeline de enriquecimento (EnriquecedorTracing em ordem de prioridade)

Método de negócio executa — logs emitidos aqui têm spanId do Child Span

Em caso de exceção:
  6. span.setStatus(ERROR) + span.recordException(e)

No finally (sempre):
  7. scope.close()            — restaura o span pai como corrente no contexto OTel
  8. span.end()               — registra end_time e envia para o exportador
  9. MDC: spanId ← spanId do pai restaurado
```

```java
package br.com.seudominio.log.interceptor;

import br.com.seudominio.log.annotations.Traced;
import br.com.seudominio.log.tracing.GerenciadorTracing;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

@Traced
@Interceptor
@Priority(Interceptor.Priority.APPLICATION - 10)   // executa antes do LogInterceptor
public class TracingInterceptor {

    GerenciadorTracing gerenciador;

    public TracingInterceptor(GerenciadorTracing gerenciador) {
        this.gerenciador = gerenciador;
    }

    @AroundInvoke
    public Object rastrear(InvocationContext contexto) throws Exception {
        var metodo     = contexto.getMethod();
        var classe     = metodo.getDeclaringClass().getSimpleName();
        var nomeMetodo = metodo.getName();
        var nomeSpan   = classe + "." + nomeMetodo;

        // Salva o spanId do pai antes de criar o Child Span
        var spanIdPai = (String) MDC.get("spanId");

        var contextoSpan = gerenciador.iniciar(nomeSpan, contexto);
        try {
            return contexto.proceed();
        } catch (Exception e) {
            gerenciador.marcarErro(contextoSpan, e);
            throw e;
        } finally {
            // Restaura o spanId do pai — independente de exceção
            gerenciador.encerrar(contextoSpan, spanIdPai);
        }
    }
}
```

---

#### 15.2. Ordem de Execução com `@Logged`: Priority CDI

Quando `@Logged` e `@Traced` são aplicados ao mesmo bean, a ordem de execução dos interceptors é determinada por `@Priority`. A convenção da biblioteca:

```
@Priority(APPLICATION - 10)  →  TracingInterceptor  (prioridade numericamente menor = executa primeiro)
@Priority(APPLICATION)       →  LogInterceptor            (prioridade numericamente maior = executa depois)
```

Resultado da pilha de execução:

```
Requisição HTTP chega
  └─ TracingInterceptor.antes()
       ├─ Captura spanId do pai
       ├─ Cria Child Span, torna corrente
       ├─ Atualiza MDC: spanId ← Child Span
       └─ LogInterceptor.antes()
            ├─ Registra classe/metodo no MDC
            ├─ Inicia Timer Micrometer
            └─ Método de negócio()
                 └─ Logs aqui têm: traceId + spanId (Child Span) + classe + metodo
            └─ LogInterceptor.finally()
                 ├─ Para Timer Micrometer
                 └─ Remove classe/metodo do MDC
  └─ TracingInterceptor.finally()
       ├─ scope.close() — restaura span pai como corrente
       ├─ span.end()    — registra end_time, exporta
       └─ MDC: spanId ← spanId do pai restaurado
```

**Por que a ordem importa:** se `LogInterceptor` executasse primeiro, o Timer Micrometer mediria a duração incluindo a criação do Child Span pelo `TracingInterceptor` — que é overhead de infraestrutura, não do método de negócio. Com `TracingInterceptor` primeiro, o Timer mede apenas o método de negócio — comportamento correto.

Adicionalmente: o `spanId` do Child Span já está no MDC quando o `LogInterceptor` registra `classe` e `metodo`. Todos os logs do método terão o `spanId` correto desde o primeiro evento.

---

#### 15.3. Restauração do `spanId` do Pai no `finally`

A restauração do `spanId` do pai no `finally` não é um detalhe de implementação — é um requisito de correção.

O MDC é `ThreadLocal`. Após o `TracingInterceptor` atualizar o `spanId` para o Child Span, o método de negócio e todos os seus logs usam esse valor. Ao final do método, se o `spanId` não for restaurado, os logs emitidos após o retorno — em camadas superiores da pilha, no `LogContextoFiltro`, em outros métodos interceptados na mesma thread — continuarão usando o `spanId` do Child Span, que já foi encerrado.

O resultado: logs fora do escopo do método `@Traced` apontando para um `spanId` de um span encerrado — span que não existirá no Jaeger para quem tentar navegar por ele.

```java
// No finally do TracingInterceptor
} finally {
    gerenciador.encerrar(contextoSpan, spanIdPai);
    // GerenciadorTracing.encerrar():
    //   scope.close()                         ← restaura span pai no contexto OTel
    //   span.end()                            ← encerra o Child Span
    //   MDC.put("spanId", spanIdPai)          ← restaura spanId do pai no MDC
    //   — ou MDC.remove("spanId") se spanIdPai == null (método era o Root Span)
}
```

**Caso de borda — método sem span pai:** quando `@Traced` é aplicado em um método chamado fora de um contexto de requisição HTTP (ex: job agendado, consumidor de mensagem), `spanIdPai` pode ser `null`. O `GerenciadorTracing.encerrar()` trata esse caso removendo o campo `spanId` do MDC em vez de restaurá-lo — evitando que um valor `null` apareça no JSON como `"spanId": "null"`.

---

### 16. `GerenciadorTracing` — Ciclo de Vida do Span

#### 16.1. Criação, Enriquecimento e Encerramento

O `GerenciadorTracing` centraliza toda a lógica OTel — criação, enriquecimento e encerramento de spans. Essa separação tem dois objetivos: tornar o `TracingInterceptor` um coordenador simples sem lógica OTel direta, e tornar cada responsabilidade testável de forma isolada sem a pilha CDI completa.

O pipeline de enriquecimento usa `Instance<EnriquecedorTracing>` do CDI — o `GerenciadorTracing` não tem conhecimento de nenhuma implementação concreta. Novos enriquecedores são descobertos automaticamente quando declarados como beans `@ApplicationScoped` — sem alteração nesta classe.

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
public class GerenciadorTracing {

    private static final String CAMPO_SPAN_ID = "spanId";

    private final Tracer                    tracer;
    private final Instance<EnriquecedorTracing> enriquecedores;

    public GerenciadorTracing(Tracer tracer,
                                   Instance<EnriquecedorTracing> enriquecedores) {
        this.tracer        = tracer;
        this.enriquecedores = enriquecedores;
    }

    /**
     * Cria um Child Span e executa o pipeline de enriquecimento.
     *
     * Fluxo:
     *   1. Cria span filho a partir do contexto OTel ativo (Context.current())
     *   2. Torna o span filho corrente (Scope)
     *   3. Atualiza o MDC com o spanId do filho
     *   4. Executa os enriquecedores em ordem crescente de prioridade
     */
    public ContextoSpan iniciar(String nomeSpan, InvocationContext contexto) {
        var span = tracer.spanBuilder(nomeSpan)
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        var scope = span.makeCurrent();

        // Atualiza o MDC com o spanId do Child Span recém-criado.
        // O valor anterior (spanId do pai) foi salvo pelo TracingInterceptor
        // antes de chamar iniciar() — e será restaurado em encerrar().
        MDC.put(CAMPO_SPAN_ID, span.getSpanContext().getSpanId());

        enriquecedores.stream()
                .sorted(Comparator.comparingInt(EnriquecedorTracing::prioridade))
                .forEach(e -> e.enriquecer(span, contexto));

        return new ContextoSpan(span, scope);
    }

    /**
     * Encerra o span e restaura o spanId do pai no MDC.
     *
     * Executado sempre no finally — independente de exceção.
     * Falhas de infraestrutura OTel são absorvidas para não interromper o negócio.
     */
    public void encerrar(ContextoSpan ctx, String spanIdPai) {
        try {
            ctx.scope().close();   // restaura o span pai como corrente no contexto OTel
            ctx.span().end();      // registra end_time e envia ao exportador
        } catch (Exception e) {
            // Falha de infraestrutura — nunca interrompe o fluxo de negócio
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
            // Falha de infraestrutura — nunca interrompe o fluxo de negócio
        }
    }

    public record ContextoSpan(Span span, Scope scope) {}
}
```

---

#### 16.2. Marcação de Erro e `recordException`

Quando o método interceptado lança uma exceção, o `TracingInterceptor` chama `gerenciador.marcarErro(contextoSpan, e)` antes de relançar a exceção. Isso produz dois efeitos no span:

**`setStatus(ERROR, mensagem)`:** marca o span com status `ERROR` no backend de traces. Jaeger e Grafana Tempo filtram traces por status — permitindo isolar traces problemáticos sem varrer todos os traces na janela de tempo.

**`recordException(causa)`:** adiciona um evento de span do tipo `exception` com os campos `exception.type`, `exception.message` e `exception.stacktrace` — definidos nas OTel Semantic Conventions. A exceção fica visível na linha do tempo do span no Jaeger, no ponto exato onde ocorreu, sem necessidade de navegar para os logs.

```
Span: PagamentoService.processar  [0ms ────────────────── ERROR ── 180ms]
  │
  └─ Event: exception (em 178ms)
       exception.type:       "br.com.dominio.GatewayException"
       exception.message:    "INSUFFICIENT_FUNDS"
       exception.stacktrace: "br.com.dominio.GatewayException: INSUFFICIENT_FUNDS
                               at br.com.dominio.GatewayClient.cobrar(GatewayClient.java:42)
                               ..."
```

**Isolamento de falhas de infraestrutura:** ambas as chamadas — `setStatus` e `recordException` — estão em bloco `try-catch`. Uma falha no exportador OTel, no SDK ou em qualquer componente de infraestrutura de observabilidade absorve a exceção localmente e não interfere na propagação da exceção original do negócio. A observabilidade nunca é justificativa para falhar uma operação de negócio.

---

#### 16.3. Sincronização com o MDC

A sincronização entre o span OTel e o MDC ocorre em dois momentos no `GerenciadorTracing`:

**Na criação do Child Span (`iniciar`):** após `span.makeCurrent()`, o Child Span é o span corrente no contexto OTel. O MDC é atualizado imediatamente com o `spanId` do Child Span. A partir deste ponto, todos os logs emitidos na thread corrente — incluindo os do método de negócio — carregam o `spanId` do Child Span.

**No encerramento (`encerrar`):** após `scope.close()`, o span pai volta a ser o span corrente no contexto OTel. O MDC é restaurado com o `spanId` do pai. A partir deste ponto, logs emitidos após o retorno do método `@Traced` voltam a carregar o `spanId` do pai.

```
MDC.spanId antes do @Traced: "a3ce929d0e0e4736"   (Root Span ou Span pai)
                                  │
  GerenciadorTracing.iniciar()
  MDC.spanId durante o @Traced: "b7df840f1a2c3e51"  (Child Span criado pelo @Traced)
                                  │
  GerenciadorTracing.encerrar()
MDC.spanId após o @Traced: "a3ce929d0e0e4736"    (restaurado ao valor anterior)
```

**Relação com o `quarkus-smallrye-context-propagation`:** a sincronização descrita acima garante o `spanId` correto no MDC para código síncrono. Para continuations reativas (`Uni`, `Multi`), o `quarkus-smallrye-context-propagation` propaga o contexto OTel — incluindo o span corrente — entre trocas de thread do Vert.x. O `GerenciadorContextoLog` lê `Span.current().getSpanContext()` no momento de cada emissão de log, garantindo o `spanId` correto independente de qual thread do pool Vert.x executou o continuation. Ver seção 7 para o comportamento detalhado em contexto reativo.

---
### 17. Pipeline de Enriquecimento de Spans

#### 17.1. Interface `EnriquecedorTracing` e Descoberta via CDI

O pipeline de enriquecimento é o mecanismo pelo qual atributos OTel são adicionados a cada span criado pelo `@Traced`. A extensibilidade é garantida pela interface `EnriquecedorTracing` — o `GerenciadorTracing` não tem conhecimento de nenhuma implementação concreta e não precisa ser modificado quando novos atributos de negócio são adicionados.

```java
package br.com.seudominio.log.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.interceptor.InvocationContext;

/**
 * Contrato do pipeline de enriquecimento de spans.
 *
 * Implementações são descobertas automaticamente pelo CDI via
 * Instance<EnriquecedorTracing> no GerenciadorTracing.
 * Novos atributos obrigatórios ou de negócio entram como novos beans
 * @ApplicationScoped sem alterar o núcleo da biblioteca.
 */
public interface EnriquecedorTracing {

    /**
     * Enriquece o span com atributos OTel.
     *
     * @param span     span ativo no momento da interceptação
     * @param contexto contexto CDI — use getParameters() para acessar
     *                 os argumentos reais do método interceptado
     */
    void enriquecer(Span span, InvocationContext contexto);

    /**
     * Ordem de execução no pipeline — valor menor executa primeiro.
     * Padrão: Integer.MAX_VALUE.
     */
    default int prioridade() {
        return Integer.MAX_VALUE;
    }
}
```

**Bandas de prioridade convencionadas:**

| Faixa | Tipo | Responsável |
|---|---|---|
| 1–50 | Atributos técnicos obrigatórios — OTel Semantic Conventions | Biblioteca (`EnriquecedorMetadados`, `EnriquecedorIdentidade`) |
| 100–499 | Atributos de negócio por domínio | Time de desenvolvimento da aplicação |
| 500+ | Atributos transversais de plataforma | Times de infraestrutura ou plataforma |

A separação em bandas evita colisão de prioridade entre enriquecedores da biblioteca e enriquecedores da aplicação. Enriquecedores de negócio com prioridade 100+ sempre executam após os atributos técnicos obrigatórios já estarem presentes no span.

---

#### 17.2. `EnriquecedorMetadados` — OTel Semantic Conventions

Adiciona os atributos técnicos obrigatórios definidos pelas [OTel Code Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/code/). Executa primeiro no pipeline (prioridade 10).

```java
package br.com.seudominio.log.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class EnriquecedorMetadados implements EnriquecedorTracing {

    @ConfigProperty(name = "quarkus.application.name")
    String applicationName;

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        var metodo = contexto.getMethod();
        var classe = metodo.getDeclaringClass();

        span.setAttribute("service.name",    applicationName);
        span.setAttribute("code.namespace",  classe.getName());
        span.setAttribute("code.function",   metodo.getName());
    }

    @Override
    public int prioridade() { return 10; }
}
```

Atributos resultantes:

| Atributo OTel | Exemplo de valor | Uso no Jaeger/Grafana Tempo |
|---|---|---|
| `service.name` | `"pagamentos-service"` | Filtro de serviço na visão de trace; identificação do nó na malha |
| `code.namespace` | `"br.com.dominio.PagamentoService"` | Identificação precisa da classe — evita ambiguidade entre classes homônimas em pacotes diferentes |
| `code.function` | `"processar"` | Identificação do método — visível na linha do tempo do span |

O `service.name` em cada span é o que permite ao Jaeger construir o grafo de chamadas entre serviços — mostrando quais microsserviços se comunicam e com qual latência. Sem esse atributo, todos os spans de uma requisição apareceriam como pertencentes ao mesmo serviço.

---

#### 17.3. `EnriquecedorIdentidade` — `enduser.id` via `SecurityIdentity`

Adiciona a identidade do usuário autenticado ao span. Executa após `EnriquecedorMetadados` (prioridade 20). Usa `Instance<SecurityIdentity>` com guarda `isResolvable()` — nunca lança `ContextNotActiveException` em contextos sem requisição HTTP ativa (jobs, consumidores de fila).

```java
package br.com.seudominio.log.tracing;

import io.opentelemetry.api.trace.Span;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;

@ApplicationScoped
public class EnriquecedorIdentidade implements EnriquecedorTracing {

    @Inject
    Instance<SecurityIdentity> identityInstance;

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        if (!identityInstance.isResolvable()) return;

        var identity = identityInstance.get();
        if (identity == null || identity.isAnonymous()) return;

        span.setAttribute("enduser.id", identity.getPrincipal().getName());
    }

    @Override
    public int prioridade() { return 20; }
}
```

**Por que `enduser.id` no span e `userId` no MDC são complementares:**

O `userId` no MDC garante que cada linha de log carregue a identidade do usuário. O `enduser.id` no span garante que a identidade apareça no grafo do Jaeger/Grafana Tempo — permitindo filtrar traces por usuário diretamente na UI do backend de tracing, sem navegar para os logs. Em investigações de incidente com impacto restrito a um usuário específico, essa filtragem reduz significativamente o escopo de análise.

---

#### 17.4. Enriquecedores de Negócio Customizados

Enriquecedores de negócio implementam `EnriquecedorTracing`, são anotados com `@ApplicationScoped` e são descobertos automaticamente pelo CDI sem qualquer alteração no `GerenciadorTracing`. O `InvocationContext.getParameters()` expõe os argumentos reais da invocação — útil para extrair identificadores de domínio visíveis no Jaeger sem alterar o código de negócio.

**Exemplo 1 — Atributos de domínio por método:**

```java
/**
 * Enriquece spans do EstoqueService com atributos de reserva de estoque.
 * Prioridade 100: executa após os enriquecedores de infraestrutura (10 e 20).
 */
@ApplicationScoped
public class EnriquecedorEstoque implements EnriquecedorTracing {

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        if (!"reservar".equals(contexto.getMethod().getName())) return;

        var params = contexto.getParameters();
        if (params == null || params.length < 2) return;

        // Java 21: pattern matching com instanceof — sem cast explícito
        if (params[0] instanceof String sku && params[1] instanceof Integer quantidade) {
            span.setAttribute("estoque.sku",        sku);
            span.setAttribute("estoque.quantidade", String.valueOf(quantidade));
        }
    }

    @Override
    public int prioridade() { return 100; }
}
```

**Exemplo 2 — Atributos de domínio por tipo de argumento:**

```java
/**
 * Enriquece qualquer span cujo primeiro argumento seja uma OrdemPagamento.
 * Adiciona identificadores de negócio visíveis na timeline do Jaeger.
 */
@ApplicationScoped
public class EnriquecedorOrdemPagamento implements EnriquecedorTracing {

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        var params = contexto.getParameters();
        if (params == null || params.length == 0) return;

        if (params[0] instanceof OrdemPagamento ordem) {
            span.setAttribute("pagamento.ordemId",  ordem.getId());
            span.setAttribute("pagamento.valor",    ordem.getValor().toString());
            span.setAttribute("pagamento.moeda",    ordem.getMoeda());
            span.setAttribute("pagamento.gateway",  ordem.getGateway());
        }
    }

    @Override
    public int prioridade() { return 110; }
}
```

**Atributos resultantes em um span `PagamentoService.processar`:**

| Atributo | Origem | Visível em |
|---|---|---|
| `service.name` | `EnriquecedorMetadados` | Jaeger — grafo de serviços |
| `code.namespace` | `EnriquecedorMetadados` | Jaeger — detalhe do span |
| `code.function` | `EnriquecedorMetadados` | Jaeger — detalhe do span |
| `enduser.id` | `EnriquecedorIdentidade` | Jaeger — filtro por usuário |
| `pagamento.ordemId` | `EnriquecedorOrdemPagamento` | Jaeger — busca por ID de ordem |
| `pagamento.valor` | `EnriquecedorOrdemPagamento` | Jaeger — detalhe do span |
| `pagamento.gateway` | `EnriquecedorOrdemPagamento` | Jaeger — filtro por gateway |

**Boas práticas para enriquecedores de negócio:**

- Atributos de alta cardinalidade (IDs de entidade) são adequados em spans — ao contrário de tags de métricas. O Jaeger armazena e busca por atributo de forma eficiente sem o risco de explosão de cardinalidade do Prometheus.
- Nunca adicionar dados sensíveis (CPF, token, senha) como atributo de span — os spans são armazenados no backend de tracing sem mascaramento automático.
- Manter o escopo do enriquecedor restrito ao método ou tipo de argumento que o justifica — evitar enriquecedores genéricos que adicionam atributos a todos os spans independente do contexto.

---

### 18. Configuração de Exportação e Amostragem

#### 18.1. `application.properties` — OTLP, Sampler e `service.name`

```properties
# ─── Identidade do Serviço ─────────────────────────────────────────────────────

# Nome único do microsserviço no ecossistema.
# Aparece como rótulo em todos os spans, métricas e logs exportados.
# Usado pelo EnriquecedorMetadados como atributo service.name em cada span.
quarkus.application.name=pagamentos-service

# ─── Exportação de Traces via OTLP ────────────────────────────────────────────

# Endpoint do OTel Collector em produção.
# Em desenvolvimento: pode apontar diretamente para Jaeger (porta 4317 gRPC).
quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317

# Protocolo de exportação: grpc (padrão, menor overhead) ou http/protobuf.
# quarkus.otel.exporter.otlp.protocol=grpc

# ─── Amostragem ───────────────────────────────────────────────────────────────

# always_on: a aplicação emite 100% dos spans para o Collector.
# Políticas de Tail-Based Sampling residem no OTel Collector — não na aplicação.
# Alternativa para ambientes com restrição de volume: parentbased_traceidratio
# com quarkus.otel.traces.sampler.arg=0.1 (10% dos traces raiz amostrados).
quarkus.otel.traces.sampler=always_on

# ─── Propagação de Contexto ────────────────────────────────────────────────────

# W3C TraceContext é o padrão; configuração explícita apenas se necessário
# adicionar suporte a outros formatos (B3, Jaeger legacy).
# quarkus.otel.propagators=tracecontext,baggage

# ─── Logging ──────────────────────────────────────────────────────────────────

quarkus.log.console.json=true
quarkus.log.level=INFO

# ─── Métricas ─────────────────────────────────────────────────────────────────

quarkus.micrometer.binder.http-server.ignore-patterns=/q/health.*,/q/metrics
```

**Configuração por perfil (dev vs. produção):**

```properties
# application-dev.properties — desenvolvimento local
# Aponta diretamente para Jaeger sem OTel Collector intermediário
%dev.quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
%dev.quarkus.log.level=DEBUG

# application-prod.properties — produção OKD
# Aponta para o OTel Collector do namespace de observabilidade
%prod.quarkus.otel.exporter.otlp.endpoint=http://otel-collector.observabilidade.svc.cluster.local:4317
%prod.quarkus.log.level=INFO
```

---

#### 18.2. Tail-Based Sampling no OTel Collector

**Por que Tail-Based Sampling no Collector, não na aplicação:**

O Head-Based Sampling (na aplicação) decide amostrar ou descartar um trace no início — antes de saber se ele conterá erros ou latência anômala. O resultado: traces de requisições problemáticas podem ser descartados exatamente quando mais importam.

O Tail-Based Sampling (no Collector) decide *após* o trace completar. O Collector acumula todos os spans de um trace e, ao receber o span raiz com status `ERROR` ou latência acima do limiar, retém o trace completo — independente da taxa de amostragem configurada para traces normais.

```yaml
# otel-collector-config.yaml — configuração de Tail-Based Sampling
processors:
  tail_sampling:
    decision_wait: 10s         # tempo máximo de espera por todos os spans do trace
    num_traces: 100000         # traces em memória simultaneamente
    policies:
      # Política 1: sempre reter traces com erros
      - name: erros
        type: status_code
        status_code: { status_codes: [ERROR] }

      # Política 2: sempre reter traces com latência acima de 2 segundos
      - name: latencia_alta
        type: latency
        latency: { threshold_ms: 2000 }

      # Política 3: amostrar 10% dos traces normais (sem erro, sem latência alta)
      - name: amostragem_normal
        type: probabilistic
        probabilistic: { sampling_percentage: 10 }
```

Com essa configuração, a aplicação emite 100% dos spans (`always_on`) e o Collector retém:
- 100% dos traces com erro
- 100% dos traces com latência acima de 2 segundos
- 10% dos traces normais — suficiente para análise de tendência

---

#### 18.3. Backends: Jaeger, Grafana Tempo, Zipkin

Todos os backends são configuráveis via `application.properties` sem alteração de código. A troca de backend é alteração de configuração do OTel Collector — a aplicação sempre exporta para o Collector via OTLP.

| Backend | Endpoint no Collector | Característica principal |
|---|---|---|
| **Jaeger** | `http://jaeger:4317` (gRPC) | UI nativa para análise de traces; adequado como backend standalone |
| **Grafana Tempo** | `http://tempo:4317` (gRPC) | Integração nativa com Loki e Prometheus no Grafana; navegação direta trace↔log↔métrica |
| **Zipkin** | `http://zipkin:9411/api/v2/spans` (HTTP) | Alternativa madura; API REST amplamente suportada |
| **Elastic APM** | `http://apm-server:8200` | Integração natural com índices Elasticsearch; correlação nativa com logs indexados no ELK |

**Configuração do Collector para múltiplos backends simultâneos:**

```yaml
# otel-collector-config.yaml — exportação para Jaeger e Grafana Tempo em paralelo
exporters:
  otlp/jaeger:
    endpoint: jaeger:4317
    tls: { insecure: false }

  otlp/tempo:
    endpoint: tempo:4317
    tls: { insecure: false }

pipelines:
  traces:
    receivers:  [otlp]
    processors: [tail_sampling, batch]
    exporters:  [otlp/jaeger, otlp/tempo]   # ambos recebem os mesmos traces
```

Essa arquitetura permite migrar progressivamente de Jaeger para Grafana Tempo — ambos operando em paralelo durante o período de transição — sem nenhuma alteração nas aplicações.

**Diagrama completo do pipeline em OKD:**

```
pagamentos-service (Quarkus)
  ├─ Traces  ──┐
  ├─ Métricas ─┤  OTLP/gRPC (porta 4317)
  └─ Logs ─────┘
                 │
                 ▼
         OTel Collector
          (tail sampling,
           enriquecimento,
           roteamento)
                 │
                 ├──▶ Grafana Tempo    ← traces
                 ├──▶ Prometheus       ← métricas (scrape /q/metrics)
                 └──▶ Loki             ← logs
                            │
                       Grafana         ← visualização unificada
                       (alerta → trace → log em um único painel)
```
---
## Parte V — Implementação: Métricas

### 19. Arquitetura de Métricas no Quarkus

#### 19.1. Micrometer como Abstração

O Quarkus usa **Micrometer** como abstração de métricas — a abordagem recomendada pela plataforma. O Micrometer define uma API unificada para os quatro tipos de medidores e um `MeterRegistry` que abstrai o backend de destino: o mesmo código de instrumentação funciona com Prometheus, Datadog, InfluxDB ou qualquer outro backend suportado, alterando apenas a dependência Maven e a configuração.

Essa separação entre API de instrumentação e backend de exportação é o que garante que o código de negócio não precise conhecer o destino das métricas — e que uma migração de Prometheus para OTLP não exija alteração em nenhum ponto de instrumentação.

---

#### 19.2. Extensões Maven: Prometheus vs. OTLP

```xml
<!--
    Micrometer core + integração Quarkus + exportação Prometheus.
    Expõe /q/metrics automaticamente no HTTP server principal.
    Use quando o stack já tem Prometheus como coletor de métricas.
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>

<!--
    Alternativa: exportação unificada via OTLP.
    Métricas, traces e logs compartilham o mesmo pipeline de saída.
    Recomendado quando o OTel Collector já está no pipeline —
    elimina o Prometheus como coletor separado.
    Substitui quarkus-micrometer-registry-prometheus.
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-opentelemetry</artifactId>
</dependency>
```

**Critério de escolha:**

| Cenário | Extensão recomendada |
|---|---|
| Stack com Prometheus + Grafana já operacional | `quarkus-micrometer-registry-prometheus` |
| OTel Collector já no pipeline (traces ativos) | `quarkus-micrometer-opentelemetry` |
| Migração progressiva Prometheus → OTLP | Ambas em paralelo — `CompositeMeterRegistry` replica para os dois backends |

---

#### 19.3. Configuração: `application.properties`

```properties
# ─── Prometheus ────────────────────────────────────────────────────────────────

# Habilita endpoint /q/metrics para scrape pelo Prometheus
quarkus.micrometer.export.prometheus.enabled=true

# Ignorar endpoints internos — evita ruído nas métricas HTTP da aplicação
quarkus.micrometer.binder.http-server.ignore-patterns=/q/health.*,/q/metrics

# ─── Tags globais (baixa cardinalidade — aplicadas a todas as métricas) ────────
# quarkus.micrometer.tags.application=${quarkus.application.name}
# quarkus.micrometer.tags.environment=${quarkus.profile}

# ─── OTLP (alternativa ao Prometheus) ─────────────────────────────────────────
# Quando usar quarkus-micrometer-opentelemetry, métricas são enviadas
# pelo mesmo endpoint OTLP configurado para traces — sem configuração adicional.
# quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317
```

---

#### 19.4. `MeterRegistry` via CDI

O `MeterRegistry` é injetável diretamente via CDI — sem fábrica manual, sem configuração adicional. A injeção via construtor é preferida por tornar as dependências explícitas e facilitar testes unitários com `SimpleMeterRegistry`:

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

O Quarkus configura e gerencia o ciclo de vida do `MeterRegistry`. Quando múltiplos backends estão configurados — ex: Prometheus + OTLP — o Quarkus cria um `CompositeMeterRegistry` que replica cada medição para todos os backends registrados, sem alteração no código de instrumentação.

**Em testes unitários**, substituir por `SimpleMeterRegistry`:

```java
var registry = new SimpleMeterRegistry();
var service  = new PedidoService(registry);

service.criar(request);

// Verifica que o counter foi incrementado com as tags corretas
assertThat(registry.counter("pedido.criado", "canal", "checkout").count())
    .isEqualTo(1.0);
```

---

### 20. Métricas Automáticas do Quarkus

Com `quarkus-micrometer-registry-prometheus` instalado, o Quarkus instrumenta automaticamente os seguintes componentes sem nenhuma linha de código adicional:

#### 20.1. Requisições HTTP (RESTEasy Reactive)

```
http_server_requests_seconds_count{method, uri, status, outcome}
http_server_requests_seconds_sum{method, uri, status, outcome}
http_server_requests_seconds_max{method, uri, status, outcome}
# com publishPercentileHistogram habilitado:
http_server_requests_seconds_bucket{method, uri, status, outcome, le}
```

Queries úteis:

```promql
# Taxa de requisições por segundo no endpoint POST /pedidos
rate(http_server_requests_seconds_count{method="POST", uri="/pedidos"}[1m])

# Taxa de erro HTTP (status 5xx)
rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m])

# p99 de latência do endpoint POST /pedidos
histogram_quantile(0.99,
    rate(http_server_requests_seconds_bucket{uri="/pedidos", method="POST"}[5m])
)
```

---

#### 20.2. JVM: Memória, GC, Threads

```
jvm_memory_used_bytes{area}               — heap e non-heap em uso
jvm_memory_max_bytes{area}               — limite configurado
jvm_gc_pause_seconds_count{action, cause} — ocorrências de pausa de GC
jvm_gc_pause_seconds_sum{action, cause}   — tempo total em pausa de GC
jvm_threads_live_threads                  — threads JVM ativas
jvm_threads_daemon_threads               — threads daemon ativas
jvm_classes_loaded_classes               — classes carregadas
```

Queries úteis:

```promql
# Percentual de heap utilizado
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100

# Taxa de pausa de GC nos últimos 5 minutos
rate(jvm_gc_pause_seconds_sum[5m])
```

---

#### 20.3. Pool de Conexões (Agroal)

```
agroal_connection_pool_active_count{data_source}    — conexões em uso
agroal_connection_pool_available_count{data_source} — conexões disponíveis
agroal_connection_pool_waiting_count{data_source}   — threads aguardando conexão
agroal_connection_pool_max_used_count{data_source}  — pico histórico de conexões
```

`agroal_connection_pool_waiting_count > 0` é o sinal mais direto de pool de conexões saturado — indica que threads de aplicação estão bloqueadas aguardando uma conexão disponível.

---

#### 20.4. Netty / Vert.x

```
allocator_memory_used_bytes{allocator_type, memory_type}
allocator_pooled_allocations_total{allocator_type}
netty_eventexecutor_tasks_pending_tasks{name}
```

As métricas Netty são relevantes para diagnóstico de problemas de alocação de memória fora do heap (direct memory) e backpressure no event loop do Vert.x.

---

### 21. Métricas de Método via `@Logged`

#### 21.1. `metodo.execucao` — Timer com Histograma

O `LogInterceptor` registra automaticamente um Timer para cada método anotado com `@Logged`. Nenhuma instrumentação adicional é necessária — a anotação já é suficiente para cobrir latência e frequência do método.

```
metodo_execucao_seconds_count{classe, metodo}       — invocações totais (sucesso + erro)
metodo_execucao_seconds_sum{classe, metodo}         — soma das durações
metodo_execucao_seconds_max{classe, metodo}         — duração máxima na janela decrescente
# com publishPercentileHistogram=true:
metodo_execucao_seconds_bucket{classe, metodo, le}  — histograma para percentis
```

O histograma é o que torna possível calcular percentis no Prometheus a partir de múltiplas instâncias do serviço. Percentis pré-calculados na aplicação (`percentiles = {0.95, 0.99}`) não são reagregáveis — uma média de percentis entre instâncias é matematicamente incorreta. O histograma armazena os buckets e o Prometheus calcula o percentil correto sobre a soma:

```promql
# p99 de latência do PedidoService.criar — correto para múltiplas instâncias
histogram_quantile(0.99,
    sum by (le) (
        rate(metodo_execucao_seconds_bucket{
            classe="PedidoService",
            metodo="criar"
        }[5m])
    )
)

# p95 de latência de todos os métodos do PagamentoService
histogram_quantile(0.95,
    sum by (le, metodo) (
        rate(metodo_execucao_seconds_bucket{
            classe="PagamentoService"
        }[5m])
    )
)
```

---

#### 21.2. `metodo.falha` — Counter por Tipo de Exceção

O `LogInterceptor` incrementa um counter a cada exceção lançada pelo método interceptado. A tag `excecao` carrega o `getSimpleName()` da classe de exceção — tornando possível detectar picos de um tipo específico de exceção sem abrir os logs:

```
metodo_falha_total{classe, metodo, excecao}
```

Exemplos de valores da tag `excecao`:
- `"GatewayException"` — falha de integração com gateway externo
- `"DatabaseException"` — falha de persistência
- `"ValidationException"` — dado inválido recebido
- `"TimeoutException"` — timeout em chamada downstream

```promql
# Taxa de GatewayException por segundo no PagamentoService.processar
rate(metodo_falha_total{
    classe="PagamentoService",
    metodo="processar",
    excecao="GatewayException"
}[2m])

# Comparação entre tipos de exceção no mesmo método — diagrama de pizza no Grafana
sum by (excecao) (
    rate(metodo_falha_total{classe="PagamentoService", metodo="processar"}[10m])
)
```

**Proteção contra falha de infraestrutura:** o `MeterRegistry` pode estar temporariamente indisponível (timeout de exportação, backend reiniciando). A incrementação do counter está em bloco `try-catch` — uma falha de infraestrutura de observabilidade não propaga como exceção de negócio e não impede o relançamento da exceção original:

```java
} catch (Exception excecaoNegocio) {
    try {
        registry.counter("metodo.falha",
            "classe",  classe,
            "metodo",  nomeMetodo,
            "excecao", excecaoNegocio.getClass().getSimpleName()
        ).increment();
    } catch (Exception metricaFalhou) {
        // Falha de infraestrutura de observabilidade — absorvida localmente
        log.warn("Falha ao registrar métrica metodo.falha: {}", metricaFalhou.getMessage());
    }
    throw excecaoNegocio;   // exceção de negócio original sempre é relançada
}
```

---

#### 21.3. PromQL: Taxa de Erro e Percentis por Método

O par `metodo.execucao` + `metodo.falha` permite calcular taxa de erro por método diretamente no Prometheus — sem depender de logs para saber se um método está falhando e com qual frequência:

```promql
# Taxa de erro do PedidoService.criar nos últimos 5 minutos (proporção)
rate(metodo_falha_total{classe="PedidoService", metodo="criar"}[5m])
/
rate(metodo_execucao_seconds_count{classe="PedidoService", metodo="criar"}[5m])

# Taxa de erro em percentual — para alerta com limiar de 5%
(
    rate(metodo_falha_total{classe="PagamentoService", metodo="processar"}[5m])
    /
    rate(metodo_execucao_seconds_count{classe="PagamentoService", metodo="processar"}[5m])
) * 100 > 5
```

**Configuração de alerta — combinando latência e taxa de erro:**

```yaml
# alertmanager rules
groups:
  - name: metodo_slo
    rules:
      - alert: LatenciaP99Alta
        expr: |
          histogram_quantile(0.99,
            rate(metodo_execucao_seconds_bucket{
              classe="PagamentoService", metodo="processar"
            }[5m])
          ) > 2
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "p99 de PagamentoService.processar acima de 2s"

      - alert: TaxaErroAlta
        expr: |
          rate(metodo_falha_total{classe="PagamentoService", metodo="processar"}[5m])
          /
          rate(metodo_execucao_seconds_count{classe="PagamentoService", metodo="processar"}[5m])
          > 0.05
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Taxa de erro de PagamentoService.processar acima de 5%"
```

**Fluxo de correlação durante um alerta:**

```
1. Alerta LatenciaP99Alta dispara (Prometheus/Grafana)
   └─ metodo_execucao_seconds p99 > 2s — PagamentoService.processar

2. Análise de tendência (Grafana)
   └─ histograma: degradação gradual nas últimas 2h
   └─ counter: GatewayException concentra 80% das falhas

3. Localização do span problemático (Jaeger/Grafana Tempo)
   └─ traces com status=ERROR filtrados por serviço e janela
   └─ grafo: GatewayClient.cobrar leva 1,8s dos 2s totais

4. Diagnóstico detalhado (Kibana/Loki)
   └─ filtrar por traceId do trace com maior latência
   └─ logs: userId, pedidoId, código de erro do gateway, stack trace
```

O `traceId` presente nos logs é a chave que navega do alerta de métrica até o contexto exato da falha — sem varredura manual.

---
### 22. Métricas de Negócio Customizadas

#### 22.1. Counter — Eventos de Negócio

O Counter é o instrumento correto para eventos de negócio que ocorrem e são contados. Tags de baixa cardinalidade — canal, região, tipo de produto — são os atributos que tornam o counter multidimensional sem risco de explosão de séries temporais.

```java
@ApplicationScoped
public class PedidoService {

    private final MeterRegistry meterRegistry;

    public PedidoService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Pedido criar(NovoPedidoRequest request) {
        var pedido = repository.salvar(new Pedido(request));

        // Counter com tags de baixa cardinalidade — conjunto fechado de valores
        meterRegistry.counter("pedido.criado",
            "canal",  request.canal(),     // "checkout", "app", "api"
            "regiao", request.regiao()     // "sul", "sudeste", "norte"
        ).increment();

        // Log estruturado — mesmo evento, contexto complementar ao counter
        Log
            .registrando(PedidoEvent.PEDIDO_CRIADO)
            .em(PedidoService.class, "criar")
            .porque("Solicitação do cliente via checkout")
            .comDetalhe("eventType", PedidoEvent.PEDIDO_CRIADO.name())
            .comDetalhe("pedidoId",  pedido.getId())
            .info();

        return pedido;
    }
}
```

Queries PromQL para o counter de negócio:

```promql
# Taxa de pedidos criados por segundo — últimos 5 minutos
rate(pedido_criado_total[5m])

# Distribuição por canal
sum by (canal) (rate(pedido_criado_total[5m]))

# Comparação entre regiões nas últimas 24h
sum by (regiao) (increase(pedido_criado_total[24h]))
```

---

#### 22.2. Timer Manual — Operações sem `@Logged`

Para operações que não usam `@Logged` mas cujo comportamento de latência é crítico para o negócio — integrações externas, processamento de lote, chamadas a APIs parceiras — o Timer manual com `Timer.start()` / `sample.stop()` é o padrão correto.

O `Timer.start()` captura o instante de início antes de qualquer código do fluxo; o `sample.stop()` registra a duração e associa o resultado (sucesso/falha) via tags — permitindo análise separada de latência por resultado:

```java
public NotaFiscal emitirNotaFiscal(Pedido pedido) {
    // Captura o instante de início — antes de qualquer I/O
    var sample = Timer.start(meterRegistry);

    try {
        var nota = apiSefaz.emitir(pedido);

        // Para o timer com tag de resultado — sucesso
        sample.stop(Timer.builder("nota.fiscal.emissao")
            .tag("resultado", "sucesso")
            .publishPercentileHistogram()
            .register(meterRegistry));

        return nota;

    } catch (SefazException e) {
        // Para o timer com tags de resultado e código de erro
        sample.stop(Timer.builder("nota.fiscal.emissao")
            .tag("resultado", "falha")
            .tag("codigo",    e.getCodigo())    // "rejeicao_534", "timeout", "indisponivel"
            .publishPercentileHistogram()
            .register(meterRegistry));
        throw e;
    }
}
```

Queries PromQL:

```promql
# p99 de latência de emissão de NF — apenas requisições bem-sucedidas
histogram_quantile(0.99,
    rate(nota_fiscal_emissao_seconds_bucket{resultado="sucesso"}[5m])
)

# Taxa de falha por código de erro — últimos 10 minutos
sum by (codigo) (rate(nota_fiscal_emissao_seconds_count{resultado="falha"}[10m]))
```

---

#### 22.3. Anotações Declarativas: `@Timed`, `@Counted`

Para casos onde a instrumentação via construtor é verbosa e o método não usa `@Logged`, o Micrometer oferece anotações declarativas ativadas por interceptor CDI. O Quarkus suporta `@Timed` e `@Counted` nativamente com `quarkus-micrometer`:

```java
@ApplicationScoped
public class RelatorioService {

    // Timer automático — equivalente a LogInterceptor.metodo.execucao
    // mas com nome e descrição customizados
    @Timed(
        value       = "relatorio.geracao",
        extraTags   = {"tipo", "vendas"},
        histogram   = true,
        description = "Tempo de geração de relatório de vendas"
    )
    public Relatorio gerarVendas(Periodo periodo) {
        // ...
    }

    // Counter automático — incrementado a cada invocação
    @Counted(
        value     = "relatorio.solicitacoes",
        extraTags = {"tipo", "estoque"}
    )
    public Relatorio gerarEstoque(Periodo periodo) {
        // ...
    }
}
```

**`@Timed` vs. `@Logged`:** quando o método já tem `@Logged`, o Timer `metodo.execucao` já é emitido automaticamente — não usar `@Timed` no mesmo método para evitar duplicação de métricas. Use `@Timed` apenas em métodos sem `@Logged` onde o nome customizado da métrica é importante para dashboards ou alertas existentes.

---

### 23. Padrões de Gauge

O Gauge é o único medidor que *observa* um valor externo em vez de acumulá-lo. O Micrometer *puxa* o valor do objeto observado a cada scrape ou ciclo de exportação — o código de instrumentação não "empurra" valores.

> **Armadilha crítica — referência fraca:** o Micrometer mantém apenas referência **fraca** ao objeto observado. Se nenhuma outra parte do código mantiver uma referência forte ao objeto, o GC pode coletá-lo e o Gauge passa a retornar `NaN` silenciosamente — sem exceção, sem log, sem alerta. **Sempre garanta que o bean CDI ou campo da classe mantenha a referência forte ao objeto instrumentado.**

---

#### 23.1. Padrão 1 — Observador de Coleção (`gaugeCollectionSize`)

Use quando o estado a observar já é uma coleção ou mapa Java gerenciado pelo próprio bean. O `MeterRegistry` oferece atalhos de conveniência que registram o Gauge e retornam a própria coleção instrumentada:

```java
@ApplicationScoped
public class FilaProcessamento {

    // Referência forte mantida pelo bean — necessária contra coleta pelo GC
    private final List<Pedido>           pendentes;
    private final Map<String, Pedido>    emProcessamento;

    public FilaProcessamento(MeterRegistry meterRegistry) {

        // gaugeCollectionSize: registra Gauge que lê Collection::size a cada scrape
        this.pendentes = meterRegistry.gaugeCollectionSize(
            "fila.pedidos.pendentes",
            Tags.of("etapa", "entrada"),
            new ArrayList<>()
        );

        // gaugeMapSize: equivalente para Map
        this.emProcessamento = meterRegistry.gaugeMapSize(
            "fila.pedidos.processando",
            Tags.of("etapa", "execucao"),
            new ConcurrentHashMap<>()
        );
    }

    public void enfileirar(Pedido pedido)          { pendentes.add(pedido); }
    public void iniciarProcessamento(Pedido pedido) {
        pendentes.remove(pedido);
        emProcessamento.put(pedido.getId(), pedido);
    }
    public void concluir(Pedido pedido)            { emProcessamento.remove(pedido.getId()); }
}
```

---

#### 23.2. Padrão 2 — Observador de Objeto (`Gauge.builder` + `ToDoubleFunction`)

Use quando o estado a observar é um objeto de domínio ou de infraestrutura que expõe getters numéricos. Um mesmo objeto pode ser observado por múltiplos Gauges, cada um extraindo uma propriedade diferente:

```java
@ApplicationScoped
public class CacheMetricas {

    // Referência forte ao cache — necessária porque o Gauge usa referência fraca
    private final Cache<String, Produto> cache;

    public CacheMetricas(Cache<String, Produto> cache, MeterRegistry meterRegistry) {
        this.cache = cache;

        Gauge.builder("cache.produtos.tamanho", cache, Cache::estimatedSize)
            .description("Número estimado de entradas no cache de produtos")
            .register(meterRegistry);

        Gauge.builder("cache.produtos.hit.rate", cache, c -> c.stats().hitRate())
            .description("Taxa de acerto do cache (proporção de hits sobre total de acessos)")
            .register(meterRegistry);

        Gauge.builder("cache.produtos.evicoes", cache, c -> c.stats().evictionCount())
            .description("Total de entradas removidas por evicção desde a inicialização")
            .register(meterRegistry);
    }
}
```

---

#### 23.3. Padrão 3 — Imperativo (`AtomicLong`)

Use quando o valor do Gauge é calculado por código de negócio em momentos específicos — não é derivado diretamente de uma estrutura em memória, mas de uma query ao banco ou cálculo periódico. O `AtomicLong` atua como suporte (*backing store*) do Gauge:

```java
@ApplicationScoped
public class PedidoEstadoMetricas {

    // AtomicLong como suporte — referência forte garantida pelo bean @ApplicationScoped
    private final AtomicLong pedidosPendentes  = new AtomicLong(0);
    private final AtomicLong pedidosEmAnalise  = new AtomicLong(0);
    private final AtomicLong pedidosBloqueados = new AtomicLong(0);

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

    // Chamado pelo job @Scheduled ou após eventos de mudança de estado
    public void sincronizarContadores(Map<String, Long> contagensPorEstado) {
        pedidosPendentes.set(contagensPorEstado.getOrDefault("PENDENTE",   0L));
        pedidosEmAnalise.set(contagensPorEstado.getOrDefault("EM_ANALISE", 0L));
        pedidosBloqueados.set(contagensPorEstado.getOrDefault("BLOQUEADO", 0L));
    }
}
```

> **Por que não usar Counter aqui:** contagens de entidades em estado específico *podem diminuir* — pedidos saem de `PENDENTE` quando processados. Counter é monotônico por definição. O Gauge com `AtomicLong` é o instrumento correto para estados que oscilam.

---

#### 23.4. Padrão 4 — Múltiplas Dimensões Dinâmicas (`MultiGauge`)

Use quando o mesmo fenômeno precisa ser reportado para vários conjuntos de tags ao mesmo tempo e esses conjuntos são dinâmicos — surgem ou desaparecem com o tempo. O `MultiGauge` gerencia internamente o conjunto de séries temporais e remove séries cujos estados desapareceram com `overwrite=true`:

```java
@ApplicationScoped
public class PedidoEstadoMultiMetricas {

    private final MultiGauge pedidosPorEstado;

    public PedidoEstadoMultiMetricas(MeterRegistry meterRegistry) {
        this.pedidosPorEstado = MultiGauge.builder("pedidos.por.estado")
            .description("Pedidos agrupados por estado atual")
            .register(meterRegistry);
    }

    // Chamado por @Scheduled com o resultado de:
    // SELECT estado, COUNT(*) FROM pedidos GROUP BY estado
    public void atualizar(Map<String, Long> contagensPorEstado) {
        var rows = contagensPorEstado.entrySet().stream()
            .map(e -> MultiGauge.Row.of(Tags.of("estado", e.getKey()), e.getValue()))
            .toList();

        // overwrite=true: remove séries de estados que sumiram do resultado
        pedidosPorEstado.register(rows, true);
    }
}
```

```promql
# Pedidos em estado PENDENTE agora
pedidos_por_estado{estado="PENDENTE"}

# Distribuição total de backlog por estado
sum by (estado) (pedidos_por_estado)
```

> **`MultiGauge` vs. múltiplos `Gauge.builder`:** prefira `MultiGauge` quando as dimensões são dinâmicas. Para dimensões estáticas e conhecidas em tempo de compilação, múltiplos `Gauge.builder` com `AtomicLong` são mais simples e explícitos.

---

#### 23.5. Padrão 5 — Duração desde Evento (`TimeGauge`)

Use quando o valor a observar é uma duração — tempo desde o último heartbeat, idade da mensagem mais antiga em fila, tempo desde o último backup bem-sucedido. O `TimeGauge` realiza a conversão de unidade automaticamente, tornando o código agnóstico ao backend:

```java
@ApplicationScoped
public class HeartbeatMetricas {

    // Referência forte — necessária porque TimeGauge usa referência fraca
    private final AtomicLong ultimoHeartbeatEpoch;

    public HeartbeatMetricas(MeterRegistry meterRegistry) {
        this.ultimoHeartbeatEpoch = new AtomicLong(System.currentTimeMillis());

        TimeGauge.builder(
                "heartbeat.ultima.ha",
                ultimoHeartbeatEpoch,
                TimeUnit.MILLISECONDS,
                epoch -> System.currentTimeMillis() - epoch.get()
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

> **`TimeGauge` vs. Gauge com milissegundos hard-coded:** use sempre `TimeGauge` para durações. Prometheus espera segundos; um Gauge que exponha milissegundos diretamente produz alertas e dashboards com escala errada.

---

**Resumo dos cinco padrões:**

| Padrão | Mecanismo | Quando usar |
|---|---|---|
| **Observador de coleção** | `gaugeCollectionSize` / `gaugeMapSize` | Coleção/mapa é o estado canônico do bean |
| **Observador de objeto** | `Gauge.builder` + `ToDoubleFunction` | Objeto expõe getter numérico — cache, pool, executor |
| **Imperativo** | `Gauge.builder` + `AtomicLong` | Valor calculado periodicamente por código |
| **Dimensões dinâmicas** | `MultiGauge` + `Row.of(Tags, valor)` | Estados que surgem e somem com o tempo |
| **Duração desde evento** | `TimeGauge` + `ToDoubleFunction` | Tempo decorrido — heartbeat, idade de mensagem |

---

### 24. Padrão Monitor Externo

#### 24.1. White-box vs. Black-box Monitoring

O Monitor Externo responde a uma tensão real em sistemas instrumentados: **objetos de domínio de negócio não deveriam carregar referências ao sistema de observabilidade**.

Um `PedidoService` que injeta `MeterRegistry` apenas para emitir Gauges de estado mistura duas responsabilidades com razões de mudança distintas — a lógica de processamento de pedidos e a estratégia de observabilidade. Se o time de plataforma decide mudar as tags de uma métrica ou adicionar um novo Gauge, o objeto de domínio precisa ser alterado.

A distinção vem do *SRE Book* (Beyer et al., cap. 10):

| Estratégia | O objeto instrumentado... | Acoplamento | Adequado para |
|---|---|---|---|
| **White-box** | emite suas próprias métricas — chama `meterRegistry` diretamente | Alto | Infraestrutura técnica: pool, cache, executor, fila técnica |
| **Black-box (Monitor Externo)** | expõe apenas estado via getters — não sabe que está sendo observado | Zero | Domínio de negócio: `PedidoService`, `PagamentoService` |

**Regra prática:** se o objeto pertence ao domínio de negócio, use Monitor Externo. Se pertence à camada de infraestrutura ou utilitários técnicos, white-box é aceitável.

---

#### 24.2. Estrutura: Domínio Sem Acoplamento a Métricas

```
ObjetoDeDomínio    — lógica de negócio, estado, zero referência a métricas
MonitorExterno     — observabilidade, zero lógica de negócio
MeterRegistry      — infraestrutura, zero conhecimento dos dois acima
```

O objeto de domínio expõe apenas o estado que naturalmente já exporia — getters que fazem sentido para a lógica de negócio, independentemente de qualquer observabilidade:

```java
// Objeto de domínio — zero conhecimento de métricas
@ApplicationScoped
public class PedidoService {

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

---

#### 24.3. Monitor com Estado em Memória

O monitor é um bean separado, sem nenhuma lógica de negócio. Sua única responsabilidade é conectar o estado do objeto de domínio ao `MeterRegistry`:

```java
// Monitor Externo — observabilidade pura, zero lógica de negócio
@ApplicationScoped
public class PedidoServiceMonitor {

    // Referência forte ao objeto monitorado — necessária porque Gauge usa referência fraca
    private final PedidoService pedidoService;

    public PedidoServiceMonitor(PedidoService pedidoService,
                                MeterRegistry meterRegistry) {
        this.pedidoService = pedidoService;

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

**Convenção de nomenclatura:**

| Objeto monitorado | Monitor externo |
|---|---|
| `PedidoService` | `PedidoServiceMonitor` |
| `FilaProcessamento` | `FilaProcessamentoMonitor` |
| `EstoqueService` | `EstoqueServiceMonitor` |

---

#### 24.4. Monitor com Estado Calculado Periodicamente (`@Scheduled`)

Quando o estado a observar não está em memória mas em uma fonte externa — banco de dados, API, cache distribuído — o Monitor Externo combina com um job `@Scheduled`. O monitor mantém `AtomicLong` como suporte e os atualiza a partir da fonte externa:

```java
@ApplicationScoped
public class PedidoEstadoBancoDadosMonitor {

    private final AtomicLong pedidosPendentes  = new AtomicLong(0);
    private final AtomicLong pedidosBloqueados = new AtomicLong(0);

    private final PedidoRepository repository;

    public PedidoEstadoBancoDadosMonitor(PedidoRepository repository,
                                          MeterRegistry meterRegistry) {
        this.repository = repository;

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
            log.warn("Falha ao sincronizar métricas de estado de pedidos: {}", e.getMessage());
        }
    }
}
```

> **Separação de responsabilidades no `@Scheduled`:** o job de sincronização pertence ao monitor, não ao repositório nem ao service. O repositório executa a query; o monitor decide quando executá-la e como mapear o resultado para métricas.

---

#### 24.5. Exemplars: Ligação Direta Métrica → Trace

Exemplars são amostras de observações do Timer ou Histogram que carregam o `traceId` da requisição que gerou aquela amostra. Quando o Grafana exibe um histograma de latência, cada barra pode ter um link direto para o trace que causou aquela latência — sem precisar abrir o Jaeger e filtrar manualmente por janela de tempo.

**Como funcionam:** o Micrometer detecta o span OTel ativo no momento do `sample.stop()` e injeta automaticamente o `traceId` e `spanId` na amostra do histograma. O endpoint `/q/metrics` no formato OpenMetrics expõe esses campos junto com o valor:

```
metodo_execucao_seconds_bucket{
    classe="PagamentoService",
    metodo="processar",
    le="0.5"
} 142 # {trace_id="4bf92f3577b34da6a3ce929d0e0e4736",span_id="a3ce929d0e0e4736"} 0.432
```

O Grafana lê o `trace_id` do exemplar e renderiza um link direto para o Grafana Tempo — navegação métrica → trace em um clique, sem filtro manual por janela de tempo.

**Habilitação:**

```properties
# Habilita formato OpenMetrics no endpoint /q/metrics — necessário para exemplars
quarkus.micrometer.export.prometheus.histogram-flavor=openmetrics_text
```

Os exemplars são gerados automaticamente quando:
- `quarkus-opentelemetry` está ativo — span OTel presente no contexto
- `publishPercentileHistogram()` está habilitado no Timer ou Distribution Summary
- O endpoint Prometheus usa o formato OpenMetrics (`histogram-flavor=openmetrics_text`)

Nenhuma alteração no código de instrumentação é necessária. O `LogInterceptor` já usa `publishPercentileHistogram()` no Timer `metodo.execucao` — Exemplars são habilitados automaticamente com a propriedade acima.

**Fluxo com Exemplars:**

```
Grafana — histograma metodo_execucao_seconds
        │
        │  barra do p99 tem Exemplar com trace_id
        │
        ▼  (clique no Exemplar)
Grafana Tempo — trace completo da requisição com maior latência
        │
        │  span PagamentoService.processar tem status ERROR
        │
        ▼  (link para logs via traceId)
Grafana Loki — logs da requisição com contexto completo
               userId, pedidoId, código de gateway, stack trace
```

Esta é a correlação completa entre os três pilares — de um pico de latência no dashboard até o contexto exato da falha nos logs — sem nenhuma query manual intermediária.

---
## Parte VI — Correlação entre Pilares e Diagnóstico

### 25. Fluxo de Investigação de Incidente

#### 25.1. Alerta → Trace → Log: Navegação entre os Três Pilares

A correlação entre métricas, traces e logs não é apenas uma boa prática de observabilidade — é o que torna possível reduzir o MTTR (*Mean Time to Repair*) de horas para minutos em sistemas distribuídos. A Charity Majors (Observability Engineering, cap. 2) define esse objetivo com precisão: um sistema observável deve permitir que um engenheiro que nunca viu aquele código antes consiga diagnosticar uma falha nova usando apenas os dados já coletados, sem adicionar instrumentação nova em produção.

O `traceId` é a chave que torna essa navegação possível. Presente em cada linha de log e em cada span, ele conecta os três pilares em uma única operação de investigação — sem correlação manual por timestamp, sem varredura de logs de múltiplos serviços, sem depender de quem escreveu o código.

O fluxo canônico de investigação:

```
1. ALERTA DISPARA  (Prometheus / Grafana)
   └─ métrica: rate(metodo_falha_total{
                   classe="PagamentoService",
                   metodo="processar",
                   excecao="GatewayException"
               }[5m]) > 0.05
   └─ interpretação: mais de 5% das invocações de
      PagamentoService.processar falham com GatewayException

2. ANÁLISE DE TENDÊNCIA  (Grafana)
   └─ histograma: p99 de latência subiu de 200ms para 2s nas últimas 2h
   └─ counter por exceção: GatewayException concentra 80% das falhas
   └─ correlação com deploy? com horário? com volume de tráfego?

3. LOCALIZAÇÃO DO COMPONENTE  (Jaeger / Grafana Tempo)
   └─ busca: serviço="pagamentos-service", status=ERROR,
             janela de tempo do alerta
   └─ grafo de spans do trace mais lento:
        POST /pagamentos                     [0ms ──────────────────── 2100ms]
          └─ PagamentoService.processar      [5ms ──────────────── 2090ms]
               └─ GatewayClient.cobrar       [10ms ─────────────── 2080ms]  ← gargalo
   └─ conclusão: GatewayClient.cobrar leva 2070ms dos 2100ms totais

4. DIAGNÓSTICO DETALHADO  (Kibana / Grafana Loki)
   └─ filtro: traceId="4bf92f3577b34da6a3ce929d0e0e4736"
   └─ logs de todos os serviços da requisição, em ordem cronológica:

      14:32:01.001  pedidos-service       INFO   "Pedido recebido"
                    detalhe_pedidoId: "9912", userId: "joao.silva@empresa.com"

      14:32:01.010  pagamentos-service    INFO   "Iniciando processamento de pagamento"
                    detalhe_ordemId: "9912", detalhe_gateway: "Cielo"

      14:32:03.088  pagamentos-service    ERROR  "Falha ao processar pagamento"
                    log_motivo: "Gateway não respondeu dentro do timeout"
                    detalhe_errorCode: "PAG-4022"
                    detalhe_timeoutMs: "2000"
                    stack_trace: "GatewayTimeoutException: connection timeout..."

      14:32:03.090  pedidos-service       ERROR  "Erro ao confirmar pagamento"
                    log_motivo: "Serviço de pagamento retornou falha"
                    detalhe_pedidoId: "9912"

5. AÇÃO DE REMEDIAÇÃO
   └─ errorCode PAG-4022 → consulta KEDB →
      runbook: "Gateway Cielo com timeout elevado — verificar status da integração"
   └─ rollback de configuração de timeout ou escalação para o time de integração
```

Cada etapa desse fluxo é viabilizada por um pilar diferente. A remoção de qualquer um dos três aumenta o tempo e o esforço de investigação proporcionalmente.

---

#### 25.2. `traceId` como Chave de Correlação Universal

O `traceId` opera como chave de correlação universal porque está presente simultaneamente em três lugares:

**Em cada linha de log** — via MDC, injetado automaticamente pelo `GerenciadorContextoLog`:

```json
{
  "timestamp":       "2026-03-11T21:55:00.123Z",
  "level":           "ERROR",
  "message":         "Falha ao processar pagamento",
  "traceId":         "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":          "a3ce929d0e0e4736",
  "userId":          "joao.silva@empresa.com",
  "applicationName": "pagamentos-service",
  "log_motivo":      "Gateway não respondeu dentro do timeout",
  "detalhe_errorCode": "PAG-4022"
}
```

**Em cada span** — como `trace_id` no cabeçalho do protocolo OTel, visível na UI do Jaeger e Grafana Tempo.

**Em exemplars de métricas** — quando `publishPercentileHistogram()` e `histogram-flavor=openmetrics_text` estão ativos, o histograma do Timer carrega o `traceId` da amostra mais recente em cada bucket:

```
metodo_execucao_seconds_bucket{
    classe="PagamentoService", metodo="processar", le="2.5"
} 87 # {trace_id="4bf92f3577b34da6a3ce929d0e0e4736"} 2.073
```

Essa tripla presença é o que torna possível a navegação em qualquer direção:

| Ponto de partida | Ferramenta | Navegação |
|---|---|---|
| Alerta de métrica | Grafana | Exemplar do histograma → `traceId` → Grafana Tempo |
| Trace com status ERROR | Jaeger / Grafana Tempo | `traceId` → filtro em Kibana/Loki |
| Log com `level: ERROR` | Kibana / Grafana Loki | `traceId` → busca no Jaeger/Grafana Tempo |

**`traceId` ausente nos logs** é o sintoma mais direto de que a correlação está quebrada. As causas mais comuns em Quarkus:

| Causa | Sintoma | Solução |
|---|---|---|
| `quarkus-opentelemetry` não instalado | `traceId` sempre vazio | Adicionar dependência Maven |
| `LogContextoFiltro` não ativo | `traceId` ausente em todos os logs | Verificar registro como `@Provider` |
| Contexto reativo sem `context-propagation` | `traceId` presente no início, vazio em continuations | Adicionar `quarkus-smallrye-context-propagation` (seção 7) |
| `traceId` gerado manualmente via UUID | `traceId` presente mas não correlaciona com nenhum span | Remover — ler de `Span.current()` (padrão proibido P6) |

---

#### 25.3. Diagrama de Fluxo: Requisição com `@Logged` + `@Traced`

O diagrama abaixo ilustra o ciclo de vida completo de uma requisição com `@Logged` e `@Traced` aplicados — da entrada HTTP até a geração dos três sinais de telemetria:

```
Requisição HTTP: POST /pagamentos
        │
        ▼
JAX-RS (quarkus-opentelemetry auto-instrumenta)
  ├─ Root Span criado: "POST /pagamentos"
  ├─ traceId: "4bf9..."  spanId: "a1b2..." injetados no MDC
  └─ LogContextoFiltro.filter() — inicializa MDC: userId, applicationName
        │
        ▼
TracingInterceptor (@Priority APPLICATION-10)
  ├─ Child Span criado: "PagamentoService.processar"  spanId: "c3d4..."
  ├─ MDC atualizado: spanId ← "c3d4..."
  └─ Pipeline de enriquecimento executado
        │
        ▼
LogInterceptor (@Priority APPLICATION)
  ├─ MDC: classe="PagamentoService", metodo="processar"
  ├─ Timer iniciado: metodo_execucao_seconds
  └─ método de negócio executa
        │
        ├─ Log emite log de negócio:
        │    JSON: traceId="4bf9...", spanId="c3d4...",
        │          userId, applicationName, log_motivo, detalhe_*
        │
        ├─ [se exceção]
        │    ├─ GerenciadorTracing.marcarErro(): span.setStatus(ERROR)
        │    │   span.recordException(e)   ← evento de span com stack trace
        │    └─ LogInterceptor: counter metodo_falha_total incrementado
        │
        ▼
LogInterceptor.finally()
  ├─ Timer parado: metodo_execucao_seconds registrado com histograma
  │   └─ Exemplar: trace_id="4bf9..." injetado automaticamente no bucket
  └─ MDC: classe e metodo removidos
        │
        ▼
TracingInterceptor.finally()
  ├─ scope.close()  — span pai restaurado como corrente
  ├─ span.end()     — Child Span encerrado, exportado via OTLP
  └─ MDC: spanId restaurado ao valor do Root Span ("a1b2...")
        │
        ▼
LogContextoFiltro.filter() (fase de resposta)
  └─ MDC.clear()  — limpeza garantida, previne context leak

─────────────────────────────────────────────────────────

Sinais gerados por essa única requisição:

LOGS (Kibana / Grafana Loki)
  └─ JSON com traceId + spanId + userId + log_motivo + detalhe_* + stack_trace

TRACE (Jaeger / Grafana Tempo)
  └─ Árvore: "POST /pagamentos" → "PagamentoService.processar"
     └─ Atributos: service.name, code.namespace, code.function, enduser.id
     └─ Evento: exception (type, message, stacktrace)

MÉTRICAS (Prometheus / Grafana)
  └─ metodo_execucao_seconds{classe, metodo} — Timer com histograma + Exemplar
  └─ metodo_falha_total{classe, metodo, excecao} — Counter (se houve exceção)
```

---

### 26. Padrões Relacionados de Microsserviços

#### 26.1. Log Aggregation

**Referência:** Chris Richardson — [Application Logging (microservices.io)](https://microservices.io/patterns/observability/application-logging.html); Iluwatar — [microservices-log-aggregation](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-log-aggregation)

O rastreamento distribuído tem valor limitado quando os logs de cada serviço são inspecionados individualmente. A correlação via `traceId` só é possível quando todos os logs estão em um sistema centralizado, pesquisável com um único filtro.

O padrão Log Aggregation recomenda que cada instância de serviço escreva logs em stdout e que um agente de coleta centralize esses logs em um repositório único. Em OKD, o pipeline padrão é:

```
Aplicação → stdout (JSON)
         → FluentBit (DaemonSet no OKD)
         → Loki / Elasticsearch
         → Grafana / Kibana (query por traceId, userId, applicationName)
```

**Relação com a biblioteca:** a saída JSON via `quarkus.log.console.json=true` torna a aplicação compatível com o padrão Log Aggregation. O destino final do log — stdout, arquivo ou combinação definida pelo projeto consumidor — não é governado pela biblioteca. O `LogContextoFiltro` garante que cada linha de log carregue os campos de correlação que tornam a agregação útil.

**O que o FluentBit adiciona:** campos de infraestrutura enriquecidos automaticamente pelo agente de coleta — ausentes na aplicação por design:

| Campo | Fonte | Uso |
|---|---|---|
| `kubernetes.pod.name` | Metadata da API do Kubernetes | Identificar qual pod processou a requisição |
| `kubernetes.namespace` | Metadata da API do Kubernetes | Separar logs por ambiente (dev/staging/prod) em multi-tenant |
| `container.id` | Runtime do container | Correlacionar com métricas de container do Prometheus |

Esses campos não devem ser emitidos pela aplicação — são responsabilidade da plataforma. A posição da biblioteca é que transformação para convenções de infraestrutura (ECS do Elasticsearch, campos `dd.*` do Datadog) é responsabilidade do coletor, não da aplicação.

---

#### 26.2. API Gateway como Ponto de Criação do Root Span

**Referência:** Cindy Sridharan — *Distributed Systems Observability* (cap. 4)

O API Gateway — ponto de entrada único do sistema para requisições externas — é o local natural para criação do Root Span de cada requisição. Quando instrumentado com OTel, o Gateway injeta o cabeçalho `traceparent` antes de rotear a requisição para os serviços internos. Cada serviço, ao receber o cabeçalho, extrai o `traceId` e cria Child Spans vinculados ao trace existente — sem criar traces órfãos.

```
Cliente externo
      │  HTTP (sem traceparent)
      ▼
API Gateway (OTel instrumentado)
      ├─ Root Span criado: "GET /api/pedidos/{id}"
      ├─ traceId: "4bf9..." gerado aqui
      │  HTTP (com traceparent: 00-4bf9...-a1b2...-01)
      ▼
pedidos-service → pagamentos-service → estoque-service
  (todos criam Child Spans vinculados ao traceId "4bf9...")
```

**Consequência da ausência de instrumentação no Gateway:** sem `traceparent` injetado pelo Gateway, cada serviço que recebe a requisição cria um Root Span próprio com `traceId` diferente. Não há fio condutor entre os serviços — o rastreamento distribuído existe apenas dentro de cada serviço individualmente, não entre serviços.

**Em OKD:** o Red Hat OpenShift Service Mesh (baseado em Istio) pode instrumentar o Gateway automaticamente via sidecar Envoy com OTel, sem alteração no código do Gateway. O `traceId` é gerado no Envoy e propagado para todos os serviços internos via `traceparent`.

---

#### 26.3. Circuit Breaker e Métricas de Estado

**Referência:** Michael Nygard — *Release It!* (Pragmatic Bookshelf); Beyer et al. — *SRE Book*, cap. 22

O Circuit Breaker é um mecanismo de resiliência que interrompe chamadas a um serviço que está falhando consistentemente, retornando imediatamente um fallback em vez de acumular timeouts. Do ponto de vista de observabilidade, o Circuit Breaker introduz dois comportamentos que precisam ser monitorados:

**Estado do disjuntor como Gauge:** o estado do Circuit Breaker — `CLOSED` (operação normal), `OPEN` (chamadas bloqueadas), `HALF_OPEN` (testando recuperação) — deve ser exposto como Gauge. Uma transição para `OPEN` é um evento de negócio crítico que deve disparar alerta imediatamente.

```java
// Exemplo com Resilience4j + Micrometer (integração nativa no Quarkus)
// O TaggedCircuitBreakerMetrics registra automaticamente:
//   resilience4j_circuitbreaker_state{name, state}
//   resilience4j_circuitbreaker_calls_total{name, kind}  — success, failed, not_permitted
//   resilience4j_circuitbreaker_failure_rate{name}
TaggedCircuitBreakerMetrics
    .ofCircuitBreakerRegistry(circuitBreakerRegistry)
    .bindTo(meterRegistry);
```

**Spans com falha em cascata:** quando o Circuit Breaker está `OPEN`, as chamadas bloqueadas não geram spans no serviço downstream — o span do serviço chamador termina imediatamente com erro `CircuitBreakerOpenException`. No Jaeger, isso aparece como um span de duração zero com status `ERROR` — padrão visualmente distinto de um timeout real, facilitando o diagnóstico de falhas em cascata.

```promql
# Alerta quando o Circuit Breaker de qualquer integração está OPEN
resilience4j_circuitbreaker_state{state="open"} == 1
```

---

#### 26.4. Saga e Rastreabilidade de Transações Distribuídas

**Referência:** Chris Richardson — *Microservices Patterns* (Manning, 2018), cap. 4; Richardson — [Saga (microservices.io)](https://microservices.io/patterns/data/saga.html)

O padrão Saga gerencia transações distribuídas de longa duração como sequências de transações locais em cada serviço, com transações de compensação em caso de falha. Do ponto de vista de observabilidade, a Saga introduz um desafio específico: a jornada de uma transação distribui-se por múltiplas requisições HTTP independentes, cada uma com seu próprio `traceId`.

**`sagaId` como chave de correlação de negócio:** enquanto o `traceId` correlaciona spans dentro de uma única requisição HTTP, a Saga exige uma chave de correlação de negócio que persiste ao longo de todas as etapas — incluindo transações de compensação que ocorrem em requisições separadas, possivelmente horas depois.

```java
// Etapa 1 da Saga — CreateOrderSaga
Log
    .registrando(PedidoSagaEvent.SAGA_CRIACAO_INICIADA)
    .em(CreateOrderSaga.class, "iniciar")
    .comDetalhe("eventType", PedidoSagaEvent.SAGA_CRIACAO_INICIADA.name())
    .comDetalhe("sagaId",    saga.getId())      // chave de correlação da Saga
    .comDetalhe("sagaType",  "CreateOrderSaga")
    .comDetalhe("pedidoId",  pedido.getId())
    .info();

// Etapa 2 — ReservationStep (requisição separada, traceId diferente)
Log
    .registrando(PedidoSagaEvent.RESERVA_ESTOQUE_INICIADA)
    .em(ReservationStep.class, "executar")
    .comDetalhe("sagaId",   saga.getId())       // mesmo sagaId — correlação cross-trace
    .comDetalhe("stepName", "ReservationStep")
    .comDetalhe("stepOrder", "2")
    .info();
```

Query no Kibana que reconstrói a história completa da Saga — independente de quantos `traceId` diferentes foram gerados ao longo das etapas:

```
detalhe_sagaId: "saga-8821-2026-03" AND @timestamp:[now-24h TO now]
| sort @timestamp asc
```

**Métricas de duração da Saga:** o tempo total de conclusão de uma Saga — da etapa 1 até a confirmação final ou compensação — é um KPI de negócio relevante. Um `Timer` com `sagaType` como tag permite detectar degradação no tempo de conclusão antes que impacte os usuários:

```promql
# p95 de duração da CreateOrderSaga nos últimos 30 minutos
histogram_quantile(0.95,
    rate(saga_duracao_seconds_bucket{sagaType="CreateOrderSaga"}[30m])
)
```

**Transações de compensação:** quando uma etapa da Saga falha e transações de compensação são disparadas, cada compensação deve incluir nos logs o `sagaId` original e o `stepName` da etapa que foi compensada. Isso permite reconstruir a jornada completa de compensação — o que falhou, em qual etapa, e quais compensações foram aplicadas — usando um único filtro por `sagaId` no agregador de logs.

---
## Parte VII — Governança e Operação

### 27. Registro de Nomes de Campos Canônicos

> Os nomes de campos são reservados e devem ser usados consistentemente em todos os serviços e nos três módulos da biblioteca. Usar sinônimos — `usuarioId` em vez de `userId`, `service` em vez de `applicationName` — viola o princípio de consistência e produz resultados divididos em ferramentas de analytics: uma query por `userId` retorna metade dos eventos; a outra metade está indexada sob `usuarioId`.

---

#### 27.1. Convenção de Nomenclatura

O projeto adota as seguintes convenções, alinhadas ao ecossistema nativo do Quarkus e do JBoss Logging:

- **`camelCase`** para identificadores de correlação, auditoria e contexto — consistente com a nomenclatura nativa do OpenTelemetry SDK e JBoss Logging: `userId`, `traceId`, `spanId`, `actorId`, `entityType`.
- **Prefixo `log_`** para campos declarados pela DSL via `.em()`, `.aqui()`, `.porque()`, `.como()`: `log_classe`, `log_metodo`, `log_motivo`, `log_entrypoint`.
- **Prefixo `detalhe_`** para campos de negócio declarados via `.comDetalhe()` — evita colisão com campos de infraestrutura no índice do Elasticsearch/Loki: `detalhe_pedidoId`, `detalhe_errorCode`, `detalhe_eventType`.

A transformação para convenções de plataformas externas — ECS do Elasticsearch, `dd.trace_id` do Datadog — é responsabilidade do coletor de infraestrutura (OTel Collector, Logstash, FluentBit), não da aplicação.

---

#### 27.2. Campos de Identidade e Correlação

Campos inseridos automaticamente pelo `GerenciadorContextoLog` via MDC. O desenvolvedor não os declara — estão presentes em todo evento de log da requisição.

| Campo | Tipo | Descrição | Fonte |
|---|---|---|---|
| `userId` | `string` | Identificador do usuário autenticado. `"anonimo"` quando não autenticado — nunca `null`, nunca ausente. | Automático — `GerenciadorContextoLog` via `SecurityIdentity` |
| `traceId` | `string` | Identificador do trace distribuído W3C. Presente quando há span OTel ativo. | Automático — OpenTelemetry SDK via `Span.current()` |
| `spanId` | `string` | Identificador do span corrente dentro do trace. Presente quando há span OTel ativo. | Automático — OpenTelemetry SDK via `Span.current()` |
| `applicationName` | `string` | Nome do microsserviço. Lido de `quarkus.application.name`. | Automático — `GerenciadorContextoLog` |

**`traceId` vs. `spanId`:** o `traceId` é constante ao longo de toda a requisição distribuída — é o identificador global que atravessa todos os serviços. O `spanId` identifica a operação individual corrente — é o nó exato da árvore onde ocorreu a falha ou o gargalo. Juntos, são suficientes para diagnóstico completo em todos os níveis, sem identificadores adicionais.

---

#### 27.3. Campos de Localização Técnica

Campos inseridos automaticamente pelo `LogInterceptor` via MDC quando o bean está anotado com `@Logged`.

| Campo | Tipo | Descrição | Exemplo |
|---|---|---|---|
| `classe` | `string` | Nome simples da classe interceptada pelo `@Logged`. | `"PedidoService"` |
| `metodo` | `string` | Nome do método interceptado pelo `@Logged`. | `"criar"` |

**`log_classe`/`log_metodo` vs. `classe`/`metodo`:** os campos sem prefixo são injetados pelo interceptor e refletem o método interceptado. Os campos com prefixo `log_` são declarados pelo desenvolvedor via `.em()` e refletem a localização semântica do evento — que pode diferir quando o log é emitido em um método auxiliar privado chamado pelo método interceptado.

---

#### 27.4. Campos da DSL (`log_`)

Campos declarados explicitamente pelo desenvolvedor na cadeia da DSL. O prefixo `log_` os distingue dos campos de infraestrutura e dos campos de negócio.

| Campo | Tipo | Declarado via | Descrição | Exemplo |
|---|---|---|---|---|
| `timestamp` | `string` | Automático (formatador) | Timestamp UTC, precisão de milissegundos, ISO 8601. | `"2026-03-11T21:55:00.123Z"` |
| `level` | `string` | Automático (formatador) | Nível de severidade do evento. | `"INFO"`, `"ERROR"` |
| `message` | `string` | `.registrando(Event)` | Descrição factual do evento — dimensão *What*. Deve ser estável entre ocorrências do mesmo tipo de evento. | `"Pedido criado"` |
| `log_classe` | `string` | `.em(Classe.class, ...)` ou `.aqui()` | Classe onde o evento ocorreu — dimensão *Where* técnica. | `"PedidoService"` |
| `log_metodo` | `string` | `.em(..., "metodo")` ou `.aqui()` | Método onde o evento ocorreu — dimensão *Where* técnica. | `"criar"` |
| `log_motivo` | `string` | `.porque("motivo")` | Causa ou motivação de negócio — dimensão *Why*. Sempre em linguagem de domínio, nunca técnica. | `"Solicitação do cliente via checkout"` |
| `log_entrypoint` | `string` | `.como(Entrypoint)` | Ponto de entrada do evento — dimensão *How*. | `"API_REST"` |

---

#### 27.5. Campos de Negócio (`detalhe_`)

Campos declarados via `.comDetalhe(chave, valor)`. O prefixo `detalhe_` é aplicado automaticamente pela DSL — o desenvolvedor declara apenas o nome sem o prefixo.

| Declaração no código | Campo no JSON | Tipo no JSON | Notas |
|---|---|---|---|
| `.comDetalhe("pedidoId", 4821)` | `detalhe_pedidoId` | `string` | Inteiros convertidos para string |
| `.comDetalhe("valorTotal", 349.90)` | `detalhe_valorTotal` | `number` | `float`/`double` preservam o tipo |
| `.comDetalhe("errorCode", "PAG-4022")` | `detalhe_errorCode` | `string` | Chave de correlação com a KEDB |
| `.comDetalhe("eventType", PedidoEvent.PEDIDO_CONCLUIDO.name())` | `detalhe_eventType` | `string` | Discriminador de eventos de negócio |
| `.comDetalhe("sagaId", "saga-8821")` | `detalhe_sagaId` | `string` | Correlação cross-trace em Sagas |
| `.comDetalhe("token", tokenValue)` | `detalhe_token` | `string` | Mascarado automaticamente: `"****"` |
| `.comDetalhe("email", emailValue)` | `detalhe_email` | `string` | Mascarado automaticamente: `"[PROTEGIDO]"` |

O prefixo `detalhe_` serve dois propósitos: evitar colisão com campos reservados de infraestrutura no índice do Elasticsearch/Loki, e distinguir visualmente campos de negócio de campos de contexto técnico nas ferramentas de analytics.

---

#### 27.6. Campos de Auditoria

Campos obrigatórios em eventos de auditoria. **Estado atual (v0.2):** declarados via `.comDetalhe()` — aparecem no JSON com prefixo `detalhe_`. O prefixo será removido quando `@Auditable` for implementado na v0.3. Queries construídas agora devem usar `detalhe_actorId`; serão atualizadas na migração para v0.3.

| Campo canônico | Campo no JSON (v0.2) | Tipo | Descrição |
|---|---|---|---|
| `actorId` | `detalhe_actorId` | `string` | Quem executou a ação — pode diferir de `userId` em cenários de impersonação ou backoffice |
| `entityType` | `detalhe_entityType` | `string` | Tipo da entidade afetada: `"Pedido"`, `"Contrato"`, `"Usuario"` |
| `entityId` | `detalhe_entityId` | `string` | Identificador da instância da entidade afetada |
| `stateBefore` | `detalhe_stateBefore` | `string` | Estado da entidade antes da operação — serializado como JSON string |
| `stateAfter` | `detalhe_stateAfter` | `string` | Estado da entidade após a operação |
| `outcome` | `detalhe_outcome` | `string` | Resultado da operação: `"SUCCESS"`, `"FAILURE"`, `"PARTIAL"` |

---

### 28. Padrões Proibidos

Os padrões abaixo são destrutivos para a infraestrutura de observabilidade e devem ser barrados sistematicamente em Pull Requests. Cada item corresponde a um item do Checklist de Code Review (seção 30).

---

#### 28.1. P1 — Saída via Fluxo de Sistema (`System.out`, `printStackTrace`)

`System.out`, `System.err` e `e.printStackTrace()` ignoram o MDC, os níveis de severidade e o formatador JSON. O output vai para stdout sem estrutura, sem campos de correlação e sem possibilidade de indexação.

```java
// PROIBIDO
System.out.println("Processando nota id " + notaId);
System.err.println("Falhou: " + e.getMessage());
e.printStackTrace();

// CORRETO
Log
    .registrando(NotaFiscalEvent.NOTA_PROCESSADA)
    .em(NotaService.class, "processar")
    .comDetalhe("notaId", notaId)
    .info();
```

---

#### 28.2. P2 — Concatenação de Strings e Pseudo-JSON

Strings interpoladas não são indexáveis. Qualquer caractere especial nos valores pode quebrar o parser do coletor. A coluna do campo não tem identidade analítica — não é possível filtrar por `notaId` sem regex frágil:

```java
// PROIBIDO
log.info("Processo da nota " + notaId + " feito por " + userId);
log.info(String.format("{\"notaOrigem\":\"%s\"}", notaId));

// CORRETO
Log
    .registrando(NotaFiscalEvent.NOTA_PROCESSADA)
    .em(NotaService.class, "processar")
    .comDetalhe("notaId", notaId)
    .comDetalhe("userId", userId)
    .info();
```

---

#### 28.3. P3 — Registro Apenas da Mensagem da Exceção

`e.getMessage()` descarta a classe da exceção (necessária para fingerprinting), o stack trace (necessário para localizar o bug) e a cadeia de causas (necessária para entender a raiz). O objeto completo deve sempre ser passado ao terminador `.erro(e)`:

```java
// PROIBIDO — descarta classe, stack trace e cadeia de causas
log.error(e.getMessage());
log.error("Erro: " + e.getMessage());

// CORRETO — objeto de exceção completo
Log
    .registrando(VendaEvent.FALHA_PROCESSAR_VENDA)
    .em(VendaService.class, "processar")
    .porque("Erro inesperado no gateway")
    .comDetalhe("vendaId", vendaId)
    .erro(e);
```

---

#### 28.4. P4 — Mensagens Genéricas sem Identificadores de Entidade

Em sistemas de alto volume, `"Erro ao salvar no banco"` pode corresponder a centenas de ocorrências distintas sem nenhuma pista sobre qual entidade, operação ou contexto. Todo evento deve incluir o identificador da entidade afetada:

```java
// PROIBIDO — sem valor diagnóstico em produção
log.error("Erro ao salvar no banco");
log.warn("Validação falhou");
log.info("Iniciado");

// CORRETO
Log
    .registrando(VendaEvent.FALHA_SALVAR_VENDA)
    .em(VendaService.class, "salvar")
    .porque("Chave duplicada no banco")
    .comDetalhe("vendaId", vendaId)
    .erro(e);
```

---

#### 28.5. P5 — Log-and-Throw sem Contexto Adicional

Registrar a mesma exceção em múltiplas camadas sem agregar informação nova duplica o ruído no agregador. **Regra: logue na fronteira onde a exceção é tratada, com o contexto completo disponível naquela camada.**

```java
// PROIBIDO — camada superior repetirá o mesmo erro sem contexto novo
catch (VendaException e) {
    Log.registrando(VendaEvent.ERRO_GENERICO_VENDA).em(...).erro(e);
    throw e;   // log duplicado na camada acima
}

// CORRETO — loga na fronteira de tratamento, com contexto completo
catch (VendaException e) {
    Log
        .registrando(VendaEvent.FALHA_TRATADA_PROCESSAMENTO)
        .em(VendaController.class, "processar")
        .porque(e.getMotivo())
        .comDetalhe("vendaId",   vendaId)
        .comDetalhe("errorCode", "VND-3001")
        .erro(e);
    return Response.status(500).entity(ErrorResponse.from(e)).build();
}
```

Quando a exceção precisa ser propagada com log, usar `.erroERelanca(e)` — que loga e relança em uma única operação, tornando a intenção explícita.

---

#### 28.6. P6 — `traceId` Gerado Manualmente

`UUID.randomUUID()` como `traceId` cria um identificador que não existe em nenhuma árvore de rastreamento. Não correlaciona com nenhum span no Jaeger, não aparece em nenhum trace no Grafana Tempo e torna o campo completamente inútil para diagnóstico distribuído:

```java
// PROIBIDO — identificador falso, não correlacionável com nenhum span
String traceId = UUID.randomUUID().toString();
MDC.put("traceId", traceId);

// CORRETO — GerenciadorContextoLog extrai de Span.current() automaticamente
// (feito pelo LogContextoFiltro no início de cada requisição HTTP)
```

---

#### 28.7. P7 — MDC sem Limpeza no `finally`

Em pools de threads, a mesma thread atende múltiplas requisições sequencialmente. Sem limpeza, o contexto da requisição anterior contamina silenciosamente a próxima — `userId` errado, `traceId` errado em todos os logs subsequentes:

```java
// PROIBIDO — contexto vaza para a próxima requisição na mesma thread
MDC.put("userId", userId);
processarNegocio();
// sem limpeza

// CORRETO — limpeza garantida independente de exceção
gerenciadorContextoLog.inicializar(userId);
try {
    processarNegocio();
} finally {
    gerenciadorContextoLog.limpar();
}
```

---

#### 28.8. P8 — Computação Custosa sem Guarda de Nível

Serializar um objeto para JSON tem custo de CPU e memória. Se `DEBUG` estiver desabilitado em produção — o que é padrão — esse custo é pago para produzir um log que nunca será emitido:

```java
// PROIBIDO — serializa o pedido mesmo com DEBUG desabilitado
log.debug("Estado do pedido: {}", objectMapper.writeValueAsString(pedido));

// CORRETO — custo pago apenas se o nível estiver habilitado
if (log.isDebugEnabled()) {
    Log
        .registrando(PedidoEvent.ESTADO_INTERMEDIARIO)
        .em(PedidoService.class, "processar")
        .comDetalhe("pedido", objectMapper.writeValueAsString(pedido))
        .debug();
}
```

---

#### 28.9. P9 — Manipulação Direta do MDC fora do `GerenciadorContextoLog`

Chamadas a `MDC.put()` dispersas no código de aplicação criam campos fora do contrato canônico, dificultam o rastreamento de vazamentos de contexto e podem sobrescrever campos gerenciados pela biblioteca:

```java
// PROIBIDO — campo fora do contrato canônico, sem garantia de limpeza
MDC.put("meu_campo_customizado", valor);

// CORRETO — campos de negócio via .comDetalhe() da DSL
Log
    .registrando(EventEnum.EVENTO_GENERICO)
    .em(MinhaClasse.class, "meuMetodo")
    .comDetalhe("meuCampo", valor)
    .info();
```

---

### 29. Gestão de Níveis de Severidade

#### 29.1. Tabela de Níveis e Uso Correto

| Nível | Quando usar | Exemplos | Habilitado em produção? |
|---|---|---|---|
| `TRACE` | Diagnóstico de baixo nível: entradas/saídas de métodos, iterações, valores intermediários detalhados | Dump de payload antes da serialização; valor de cada campo em um loop de validação | Nunca — apenas em desenvolvimento local |
| `DEBUG` | Fluxos internos, decisões condicionais, dados intermediários sem alteração de estado | Branch condicional tomado; resultado de cálculo interno | Não por padrão — ativável dinamicamente por pacote |
| `INFO` | Operações que alteram estado: persistência, autenticação, chamadas externas, eventos de negócio | Pedido criado; login bem-sucedido; nota fiscal emitida | Sempre |
| `WARN` | Situações anômalas recuperáveis: fallbacks ativados, validações rejeitadas, limites se aproximando | Retry ativado; configuração ausente com fallback padrão; fila se aproximando da capacidade | Sempre |
| `ERROR` | Falhas reais: exceção que impede o cumprimento do contrato da operação | Gateway timeout; falha de persistência; erro de integração | Sempre |
| `FATAL` | Falhas que tornam a instância operacionalmente inviável — exigem intervenção imediata | Falha de inicialização do datasource; corrupção de configuração crítica | Sempre |

**Regra de consistência:** o mesmo tipo de evento deve sempre usar o mesmo nível. Um `"Login falhou"` que às vezes é `WARN` (usuário errou a senha) e às vezes é `ERROR` (problema no provedor de identidade) deve ser separado em dois eventos distintos com mensagens e níveis específicos.

**Regra anti-duplicação:** é proibido registrar a mesma exceção em múltiplas camadas sem agregar contexto adicional. Cada camada loga apenas o que sabe a mais sobre o erro.

---

#### 29.2. `FATAL` no JBoss Logging vs. SLF4J

O JBoss Logging — framework nativo do Quarkus — suporta o nível `FATAL` (`org.jboss.logging.Logger.Level.FATAL`). O SLF4J — interface usada em ambientes Jakarta EE genéricos — não define `FATAL`; o nível mais alto disponível é `ERROR`.

| Framework | Nível mais alto | Equivalência |
|---|---|---|
| JBoss Logging (Quarkus) | `FATAL` | `FATAL` nativo |
| SLF4J | `ERROR` | `ERROR` com `detalhe_errorCode` de criticidade máxima e `detalhe_eventType: "FATAL_ERROR"` |

Na implementação Quarkus desta biblioteca, `FATAL` está disponível via terminador dedicado. Na implementação SLF4J, eventos de criticidade equivalente devem usar `ERROR` com campos estruturados que identificam a criticidade — jamais silenciar ou rebaixar a severidade.

---

#### 29.3. Ativação Dinâmica de `DEBUG` em Produção

Em produção, logs `DEBUG` são desabilitados por padrão. Reabilitá-los durante um incidente — sem reinicialização da aplicação — é possível via duas abordagens:

**Quarkus — `quarkus-logging-manager`:**

```bash
# Ativa DEBUG para um pacote específico em tempo de execução via REST API
curl -X POST http://meu-servico/q/logging-manager/loggers/br.com.dominio.pagamentos \
     -H "Content-Type: application/json" \
     -d '{"level": "DEBUG"}'

# Restaura INFO
curl -X POST http://meu-servico/q/logging-manager/loggers/br.com.dominio.pagamentos \
     -H "Content-Type: application/json" \
     -d '{"level": "INFO"}'
```

O endpoint `/q/logging-manager` deve ser protegido por autenticação em produção — expô-lo sem controle de acesso permite que qualquer cliente sature o sistema com logs `DEBUG`.

**OKD / Kubernetes — `ConfigMap` com recarga:** se a aplicação lê a configuração de um `ConfigMap` do OKD com recarga automática habilitada (`quarkus.config.locations`), alterar o `ConfigMap` com o novo nível de log aplica a mudança sem restart do pod.

---

### 30. Checklist de Code Review

Antes de aprovar qualquer Pull Request que toque em código de logging, tracing ou métricas:

**Logging estruturado:**

- [ ] Nenhum `System.out.println`, `System.err.println` ou `e.printStackTrace()`
- [ ] Nenhuma concatenação de string ou `String.format` em mensagens de log
- [ ] Nenhum `log.error(e.getMessage())` — objeto de exceção completo passado ao terminador `.erro(e)`
- [ ] Nenhuma mensagem genérica — identificadores de entidade presentes via `.comDetalhe()`
- [ ] Nenhum log-and-throw sem contexto adicional — log na fronteira de tratamento com `.erro(e)` ou `.erroERelanca(e)`
- [ ] Campos de negócio com nomes canônicos do FIELD_NAMES (seção 27) — nenhum sinônimo
- [ ] Nenhum dado sensível sem mascaramento — campos que exigem redação total omitidos antes de `.comDetalhe()`
- [ ] Eventos críticos incluem `detalhe_errorCode` para correlação com KEDB
- [ ] Computações custosas protegidas por guarda de nível (`isDebugEnabled()`)

**Contexto e MDC:**

- [ ] Nenhum `UUID.randomUUID()` como `traceId` — contexto OTel via `GerenciadorContextoLog`
- [ ] MDC limpo no bloco `finally` via `GerenciadorContextoLog.limpar()` — nunca depende de sobrescrita
- [ ] Nenhum `MDC.put()` direto fora do `GerenciadorContextoLog`
- [ ] Métodos que retornam `Uni<T>` ou `Multi<T>` com `@Traced`: verificar que logs em continuations reativos contêm `spanId` não nulo e não vazio

**Tracing:**

- [ ] `@Traced` e `@Logged` no mesmo bean: verificar que `@Priority` segue a convenção `1000/2000` (`TracingInterceptor` antes de `LogInterceptor`)
- [ ] Enriquecedores de negócio: nenhum dado sensível adicionado como atributo de span
- [ ] Falhas de infraestrutura OTel (`span.end()`, `span.setStatus()`) tratadas com `try-catch` — não propagam como exceção de negócio

**Métricas:**

- [ ] Nenhuma tag de alta cardinalidade (`userId`, `traceId`, `pedidoId`) em counter, timer ou gauge
- [ ] Gauges com `Gauge.builder` ou `gaugeCollectionSize`: referência forte ao objeto observado mantida pelo bean
- [ ] Falhas de `MeterRegistry` tratadas com `try-catch` — não interrompem o fluxo de negócio

**Geral:**

- [ ] Falhas de qualquer backend de observabilidade (tracing, métricas, log exporter) tratadas localmente — nunca relançadas como exceção de negócio
- [ ] `quarkus-smallrye-context-propagation` presente no `pom.xml` quando há métodos reativos instrumentados
---
### 31. Ciclo de Melhoria Contínua

#### 31.1. Retroalimentação Pós-Incidente

O logging, o tracing e as métricas são componentes vivos da arquitetura — não artefatos estáticos configurados uma vez e esquecidos. A qualidade da observabilidade de um sistema é proporcional ao número de incidentes que foram usados para refiná-la.

O ciclo de melhoria pós-incidente é estruturado em quatro etapas:

```
1. INCIDENTE EM PRODUÇÃO
   └─ Diagnóstico realizado com os dados disponíveis

2. REVISÃO PÓS-INCIDENTE (Post-Mortem)
   └─ Quais informações estavam ausentes e atrasaram o diagnóstico?
   └─ Qual pilar foi mais útil? Qual foi insuficiente?
   └─ Onde o traceId estava ausente? Onde o spanId estava incorreto?
   └─ Qual campo teria reduzido o MTTR em mais de 50%?

3. MELHORIA DA INSTRUMENTAÇÃO
   └─ Adicionar o campo ausente via .comDetalhe() ou novo EnriquecedorTracing
   └─ Refinar o .porque() em fluxos onde estava genérico
   └─ Adicionar @Traced em métodos que foram gargalos mas não tinham span
   └─ Criar alerta para o padrão de falha identificado

4. INSTITUCIONALIZAÇÃO
   └─ Atualizar o checklist de Code Review (seção 30)
   └─ Documentar o errorCode novo na KEDB
   └─ Registrar o padrão novo como caso de uso no catálogo de eventType
   └─ Comunicar a melhoria para todos os times que usam a biblioteca
```

**Exemplos de melhorias derivadas de incidentes reais:**

| Lacuna identificada no incidente | Melhoria implementada |
|---|---|
| `userId` ausente em logs de jobs agendados | `LogContextoFiltro` expandido para identificar jobs com `"anonimo"` explícito |
| `log_motivo` genérico em falhas de gateway | `.porque()` refinado com código de retorno do gateway no fluxo de pagamento |
| `traceId` ausente em logs de consumidores Kafka | `quarkus-smallrye-context-propagation` adicionado; `LogContextoFiltro` adaptado para o ciclo de vida do consumer |
| Impossível distinguir erros de negócio de erros de infraestrutura em alertas | `detalhe_errorCode` com prefixo de domínio (`PAG-`, `VND-`) adicionado a todos os fluxos críticos |
| Dois serviços com nomes de campo diferentes para o mesmo conceito | Catálogo de `eventType` e `FIELD_NAMES` centralizados e distribuídos como contrato compartilhado |

> *Cada incidente é uma oportunidade de tornar o próximo diagnóstico mais rápido. Um sistema que nunca melhora sua instrumentação após incidentes tende a repetir os mesmos MTTR indefinidamente.*
> — Charity Majors, Liz Fong-Jones, George Miranda — *Observability Engineering* (O'Reilly, 2022)

---

#### 31.2. Métricas de Qualidade do Pipeline de Telemetria

A observabilidade do próprio pipeline de observabilidade é frequentemente negligenciada. Um `traceId` ausente em logs, spans descartados silenciosamente pelo sampler ou logs não chegando ao Elasticsearch são falhas invisíveis que só se revelam durante um incidente — quando a dependência dos dados é máxima.

O OTel SDK expõe métricas internas do pipeline de telemetria automaticamente quando `quarkus-opentelemetry` está ativo. O Quarkus as disponibiliza via `/q/metrics`:

**Métricas de exportação de spans:**

```promql
# Spans exportados com sucesso para o OTel Collector
otel_exporter_otlp_spans_exported_total{result="success"}

# Spans que falharam na exportação — backend indisponível, timeout, erro de rede
otel_exporter_otlp_spans_exported_total{result="failed"}

# Taxa de falha de exportação — alerta se > 0 por mais de 1 minuto
rate(otel_exporter_otlp_spans_exported_total{result="failed"}[1m]) > 0
```

**Métricas de sampling:**

```promql
# Spans descartados pelo sampler — normal se tail-based sampling ativo no Collector
otel_sampler_spans_dropped_total

# Spans aceitos pelo sampler
otel_sampler_spans_accepted_total

# Taxa de descarte pelo sampler — detecta configuração incorreta
otel_sampler_spans_dropped_total
/ (otel_sampler_spans_dropped_total + otel_sampler_spans_accepted_total)
```

**Métricas de qualidade de logs — verificação de presença de `traceId`:**

Não há métrica nativa para ausência de `traceId` em logs — mas é possível criar uma via query no agregador de logs. Em Grafana Loki:

```logql
# Proporção de logs ERROR sem traceId nas últimas 1h
# Um valor > 0 indica quebra de propagação de contexto
sum(rate({app="pagamentos-service"} | json | level="ERROR" | traceId="" [1h]))
/
sum(rate({app="pagamentos-service"} | json | level="ERROR" [1h]))
```

**Alertas recomendados para o pipeline de telemetria:**

| Alerta | Condição | Severidade | Ação |
|---|---|---|---|
| Exportação de spans falhando | `rate(otel_exporter_..._failed[1m]) > 0` por 2min | Warning | Verificar conectividade com OTel Collector |
| Taxa de descarte de spans anômala | Sampler descartando > 90% em ambiente de baixo volume | Warning | Verificar configuração do tail-based sampler |
| Logs sem `traceId` | > 5% dos logs ERROR sem `traceId` | Critical | Verificar `quarkus-smallrye-context-propagation` e `LogContextoFiltro` |
| `/q/metrics` indisponível | Health check falha no endpoint Prometheus | Warning | Verificar `quarkus-micrometer` e configuração do scrape |

---

### 32. Fora do Escopo

Esta seção documenta explicitamente o que não faz parte da biblioteca — decisões que podem parecer alternativas válidas mas que violam o padrão ou introduzem problemas arquiteturais.

---

#### 32.1. API Fluent Direta do SLF4J 2.x

O método `logger.atInfo().addKeyValue("chave", valor).log("mensagem")` — introduzido no SLF4J 2.x — produz JSON estruturado tecnicamente, mas apresenta três limitações estruturais em relação à DSL `Log`:

- **Sem enforcement do 5W1H em tempo de compilação:** nenhuma das dimensões obrigatórias (*What*, *Where*) é verificada pelo compilador. Logs incompletos são detectados apenas em revisão de código ou em produção.
- **Sem mascaramento automático:** `addKeyValue("token", tokenValue)` emite o valor em claro — o `SanitizadorDados` não é invocado.
- **Sem integração com `GerenciadorContextoLog`:** campos de correlação adicionados via `addKeyValue` não seguem o contrato canônico de campos definido em `FIELD_NAMES`.

**Uso aceitável:** logs internos da própria biblioteca, utilitários de infraestrutura sem contexto de domínio, e diagnóstico temporário em desenvolvimento. Para código de aplicação que deve obedecer ao padrão, `Log` é obrigatório.

---

#### 32.2. `ExceptionReporter` e Backends de Rastreamento de Exceções (v0.3)

A abstração `ExceptionReporter` — CDI bean com integração a Sentry, Rollbar ou webhook customizado — está planejada para a versão 0.3 da biblioteca. Não está disponível nesta versão.

Até então, exceções devem ser registradas via `Log.erro(e)` com contexto completo — que é o pré-requisito para que o `ExceptionReporter` funcione eficazmente quando implementado. Implementações manuais de integração com Sentry ou similares no código de aplicação criarão acoplamento direto que precisará ser removido na migração para v0.3.

---

#### 32.3. `AuditRecord` e `@Auditable` (v0.3)

O `AuditRecord` e o interceptor `@Auditable` são abstrações documentadas conceitualmente e planejadas para a versão 0.3. Não estão disponíveis nesta versão.

Até então, eventos de auditoria devem ser registrados via `Log` com os campos obrigatórios declarados explicitamente via `.comDetalhe()` — conforme documentado na seção 13.4. Quando `@Auditable` for implementado, os campos de auditoria serão emitidos como chaves de primeiro nível no JSON sem o prefixo `detalhe_`. Queries construídas agora com `detalhe_actorId` precisarão ser atualizadas — documentar essa dependência no momento da implementação.

---

#### 32.4. Output GELF/Graylog Nativo na Aplicação

Configurar `quarkus.log.handler.gelf.enabled=true` transmite logs diretamente da aplicação para o Graylog via UDP — acoplando a aplicação à infraestrutura de coleta. Esse padrão viola dois princípios da arquitetura:

**Acoplamento de infraestrutura:** trocar de backend de logs (Graylog → Loki) exige alteração de configuração em cada aplicação. A arquitetura correta emite JSON para stdout; o escoamento é responsabilidade do coletor externo (OTel Collector, FluentBit).

**Inconsistência de formato:** o GELF usa um formato JSON aninhado diferente do formato flat produzido por `quarkus-logging-json`. A coexistência dos dois formatos no mesmo pipeline cria inconsistências nos campos indexados — queries que funcionam para logs via FluentBit falham para logs via GELF.

A integração com Graylog em OKD deve ser feita via FluentBit com output GELF configurado no coletor — sem nenhuma alteração nas aplicações.

---

#### 32.5. Log Rotation e Escrita em Arquivo

A biblioteca não impõe política produtiva para escrita em arquivo ou rotação de logs. Essa decisão pertence à aplicação consumidora e à plataforma onde ela roda.

Em ambientes container-native (OKD, Kubernetes), a recomendação operacional continua sendo emitir logs estruturados para stdout e delegar coleta, armazenamento e retenção ao coletor da plataforma. Ainda assim, a biblioteca não deve bloquear nem sobrescrever configurações como `quarkus.log.file.enable` ou `quarkus.log.file.rotation.*`.

- Se a aplicação habilitar arquivo em produção, isso deve ser uma decisão explícita do projeto consumidor.
- A documentação da aplicação deve explicar como o arquivo será coletado, rotacionado e retido.
- A biblioteca deve permanecer neutra: fornece logging estruturado e MDC canônico, mas não governa o destino final do log.

**Debug local:** em desenvolvimento local sem agregador, escrever em arquivo temporariamente é aceitável quando útil ao fluxo do time.

---

#### 32.6. `@AroundInvoke` Manual pelo Desenvolvedor de Aplicação

O `LogInterceptor` da biblioteca já realiza via `@Logged` a inserção de campos de localização no MDC, a coleta de métricas de latência e falha, e a limpeza de contexto no `finally`. Criar interceptors CDI com `@AroundInvoke` paralelos no código de aplicação introduz três problemas:

**Duplicação de responsabilidade:** se o interceptor da aplicação também registra localização no MDC, os campos `classe` e `metodo` podem ser sobrescritos com valores incorretos — ou a limpeza pode ocorrer em ordem errada, deixando o contexto sujo após o método.

**Risco de vazamento de contexto em ambientes reativos:** o `LogInterceptor` da biblioteca foi projetado para funcionar com `quarkus-smallrye-context-propagation`. Interceptors manuais sem esse cuidado podem escrever no MDC da thread do interceptor sem garantia de propagação para continuations reativos — produzindo `spanId` incorreto nos logs assíncronos.

**Campos fora do contrato canônico:** campos adicionados diretamente via `MDC.put()` em um interceptor manual não passam pelo `SanitizadorDados`, não seguem as convenções de prefixo do `FIELD_NAMES` e não são listados no contrato da biblioteca — tornando-os invisíveis para revisores de código que consultam a documentação.

A extensão correta do comportamento de interceptação é via `EnriquecedorTracing` (seção 17) para atributos de tracing, ou via campos adicionais em `.comDetalhe()` dentro do próprio método de negócio.

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
