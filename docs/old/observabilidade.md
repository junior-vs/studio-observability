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

#### 4.2. Trace, Span e Hierarquia de Execução

#### 4.3. Propagação de Contexto e W3C TraceContext

#### 4.4. `traceId` vs. `spanId` — Granularidades Complementares

#### 4.5. Correlação com Logs: o Elo entre os Pilares

#### 4.6. Trade-offs e Gerenciamento de Volume

---

### 5. Métricas — Conceitos

#### 5.1. O que Logs e Traces não Respondem

#### 5.2. O que é uma Métrica e sua Natureza de Série Temporal

#### 5.3. Tipos Fundamentais: Counter, Gauge, Timer, Distribution Summary

#### 5.4. Dimensões (Tags) e o Risco de Explosão de Cardinalidade

#### 5.5. Correlação com Logs e Traces

#### 5.6. Trade-offs

---

## Parte II — OpenTelemetry e Pipeline de Telemetria

### 6. OpenTelemetry — O Padrão CNCF

#### 6.1. Instrumentação Automática e Manual

#### 6.2. Protocolo OTLP e Pipeline de Coleta

#### 6.3. Backends de Visualização

#### 6.4. Exportação Unificada via OTLP (`quarkus-micrometer-opentelemetry`)

---

### 7. Contexto Reativo — MDC, Vert.x e Mutiny

#### 7.1. O Problema do `ThreadLocal` em Pipelines Assíncronos

#### 7.2. `quarkus-smallrye-context-propagation`

#### 7.3. Comportamento Correto em Métodos que Retornam `Uni`/`Multi`

#### 7.4. Leitura de `traceId`/`spanId` no Momento da Emissão

---

## Parte III — Implementação: Logging

### 8. Arquitetura da Biblioteca de Logging

#### 8.1. Estrutura de Pacotes

#### 8.2. Dependências e Configuração de Saída JSON

#### 8.3. Plataforma Base: Java 21 e Quarkus 3.20+

---

### 9. Gerenciamento de Contexto via MDC

#### 9.1. `GerenciadorContextoLog` — Responsabilidade Única de Escrita

#### 9.2. `LogContextoFiltro` — Inicialização e Limpeza por Requisição

#### 9.3. Limpeza Garantida e Prevenção de Context Leak

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