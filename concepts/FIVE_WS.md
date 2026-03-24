# O Framework 5W1H

> Quando o framework 5W1H é aplicado a cada evento de log, o arquivo de log deixa de ser uma parede de texto e passa a ser um banco de dados consultável. Em vez de `grep` no terminal, o engenheiro executa queries como: _"Mostre todos os erros (What) do usuário USR-445 (Who) nos últimos 10 minutos (When) no serviço de pagamentos (Where)."_

---

## Visão Geral

O framework 5W1H — emprestado do jornalismo e da Análise de Causa Raiz (RCA) — é o modelo fundacional deste projeto. Ele estabelece que todo evento de log deve responder a seis dimensões investigativas para ter valor diagnóstico real em produção.

| Dimensão | Pergunta | Implementado via | Campos resultantes no JSON |
|---|---|---|---|
| **Who** (Quem) | Quem desencadeou a ação? | Injetado automaticamente | `userId` |
| **What** (O quê) | O que exatamente ocorreu? | `.registrando("evento")` | `message`, `level` |
| **When** (Quando) | Quando ocorreu? | Injetado automaticamente | `timestamp` (UTC, ms) |
| **Where** (Onde) | Em que serviço, classe e fluxo? | `.em(Classe.class, "metodo")` + automático | `servico`, `log_classe`, `log_metodo`, `traceId`, `spanId`, `requestId` |
| **Why** (Por quê) | Qual a motivação ou causa de negócio? | `.porque("motivo")` | `log_motivo` |
| **How** (Como) | Por qual canal chegou? | `.como("canal")` | `log_canal` |

As dimensões *When* e *Who* são preenchidas automaticamente pela infraestrutura. A dimensão *Where* é parcialmente automática (identificadores de correlação via OTel e filtro HTTP) e parcialmente declarada (`.em()`). As dimensões *Why* e *How* exigem declaração explícita pelo desenvolvedor — e é exatamente aí que a DSL atua, guiando esse preenchimento.

Um log com apenas `PedidoService.criar` sem o `pedidoId`, o usuário e o motivo não permite diagnóstico eficiente em produção. Classe e método são metadados técnicos úteis, mas não substituem rastreabilidade funcional.

---

## 1. Who — Identidade

A dimensão *Who* responde: **quem está envolvido neste evento?**

O preenchimento adequado do *Who* distingue uma falha sistêmica que afeta todos os usuários de um erro pontual isolado a um único cliente. Em uma investigação pós-incidente, a ausência do `userId` transforma horas de diagnóstico em varredura cega.

### Identidade do usuário

Capturado automaticamente pelo `GerenciadorContextoLog` a partir do `SecurityContext` CDI, sem necessidade de repasse explícito em cada camada de negócio:

```json
{
  "userId": "joao.silva@empresa.com"
}
```

Quando não há usuário autenticado (chamadas de sistema, jobs agendados), o campo recebe o valor `"anonimo"` — nunca é omitido nem preenchido com `null`. Isso garante que queries como `userId: "anonimo"` isolem operações de sistema de operações de usuário.

### Identidade do serviço

Em arquiteturas de microsserviços, o *Who* inclui também o serviço que gerou o evento. O campo `servico` é preenchido automaticamente a partir de `quarkus.application.name` (Quarkus) ou da configuração equivalente no container Jakarta EE:

```json
{
  "servico": "pedidos-service"
}
```

Esse campo é essencial para queries que cruzam logs de múltiplos serviços em um único agregador. Sem ele, não é possível distinguir qual instância de qual serviço gerou um determinado evento.

---

## 2. What — Descrição do Evento

A dimensão *What* responde: **o que exatamente aconteceu?**

É a dimensão mais visível do log — é o `message` que aparece em primeiro lugar no Kibana ou no Grafana Loki. Mensagens vagas são os maiores inimigos do MTTR: `"Erro no processamento"` pode corresponder a centenas de eventos distintos sem nenhuma pista sobre o que falhou.

### Regras para mensagens

- **Usar linguagem direta e neutra.** Descrever o evento como uma frase factual, no passado: "Pedido criado", "Login falhou", "Pagamento recusado". Evitar linguagem informal, jargão interno e abreviações ambíguas.
- **Incluir o identificador da entidade quando possível.** "Falha ao salvar Order#4821" é diagnosticável; "Falha ao salvar" não é.
- **Não duplicar informações já em campos estruturados.** Se `pedidoId` está em `comDetalhe()`, não é necessário repetir na mensagem.

```java
// PROIBIDO — vago, sem contexto de entidade
LogSistematico.registrando("Erro no processamento")...

// CORRETO — específico, incluindo o identificador
LogSistematico
    .registrando("Falha ao processar pagamento")
    .em(PagamentoService.class, "processar")
    .comDetalhe("pedidoId", pedidoId)
    .erro(e);
```

### Eventos técnicos vs. eventos de negócio

O *What* abrange dois tipos de eventos:

- **Eventos técnicos:** falhas de integração, exceções, estados de fluxo interno. Destinados a engenheiros e SRE.
- **Eventos de negócio:** `ORDER_COMPLETED`, `CHECKOUT_STARTED`, `PAYMENT_FAILED`. Destinados também a times de negócio e analytics. Devem incluir o campo `eventType` via `comDetalhe()` para serem identificáveis como categoria distinta nas ferramentas de observabilidade.

---

## 3. When — A Linha do Tempo

A dimensão *When* responde: **exatamente quando este evento ocorreu?**

Em sistemas distribuídos, a dimensão *When* é mais complexa do que parece. Um timestamp sozinho não é suficiente — ele precisa ser comparável com timestamps de outros serviços para que a reconstrução da sequência de causa e efeito funcione.

### Requisitos obrigatórios

**Precisão de milissegundos.** Eventos distribuídos concorrentes não podem ser enfileirados com precisão de segundos. Dois eventos no mesmo segundo em serviços diferentes não têm ordem determinável sem milissegundos.

**UTC obrigatório.** Timestamps em fuso horário local distorcem investigações em sistemas multi-regionais ou em equipes distribuídas. O formato `2026-03-11T21:55:00.123Z` é inequívoco; `2026-03-11T18:55:00.123-03:00` exige conversão mental a cada comparação.

**Sincronização NTP.** O `quarkus-logging-json` e o `JsonTemplateLayout` do Log4j2 emitem timestamps em UTC — mas isso é necessário, não suficiente. Se os relógios das máquinas que executam os serviços estiverem dessincronizados, os timestamps em UTC serão incorretos mesmo que bem formatados. Todos os servidores e containers devem estar sincronizados via NTP.

O campo `timestamp` é preenchido automaticamente pelo formatador no momento da emissão — o desenvolvedor não declara este campo.

---

## 4. Where — Topologia e Localização

A dimensão *Where* revela: **onde na cadeia lógica do código e na malha da infraestrutura este evento surgiu?**

Essa resposta opera em três escalas complementares:

### Localização técnica no código

Sustentado via `.em(MinhaClasse.class, "meuMetodo")`, registra a origem precisa do evento no código. Isso evita a necessidade de interpretar stack traces completos para encontrar a linha relevante em eventos não excepcionais.

```java
LogSistematico
    .registrando("Pedido criado")
    .em(PedidoService.class, "criar")  // ← Where técnico
    ...
```

Quando há uma exceção, o stack trace completo é serializado automaticamente pelo formatador — fornecendo o *Where* com máxima granularidade.

### Identificadores de correlação

Em sistemas que processam múltiplas requisições concorrentemente, os identificadores de correlação são o que separa os eventos de uma requisição específica dos eventos de todas as outras:

```json
{
  "requestId": "a3f9c2d1-7b44-4e2a-9c2d-1a3b9c2d17b4",
  "traceId":   "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":    "a3ce929d0e0e4736"
}
```

O `requestId` isola uma única requisição HTTP dentro de um serviço. O `traceId` — gerado pelo OpenTelemetry e propagado via cabeçalho `traceparent` (W3C TraceContext) — une os eventos de todos os serviços que participaram da mesma operação distribuída. Filtrar por `traceId` em um agregador de logs reconstrói a jornada completa do usuário através de N serviços, em ordem cronológica, com um único filtro.

### Identidade do serviço

O campo `servico` informa em qual microsserviço o evento ocorreu — essencial em um agregador que consolida logs de dezenas de serviços. Junto com o `traceId`, permite navegar de um evento em `pedidos-service` para o evento correlacionado em `pagamentos-service` sem ambiguidade.

---

## 5. Why — Motivação de Negócio

A dimensão *Why* responde: **qual a causa ou motivação de negócio deste evento?**

Essa dimensão diferencia uma arquitetura de logging madura de uma superficial. O *Why* é o que permite que um operador de plantão entenda o contexto de um evento sem precisar ler o código-fonte ou consultar o desenvolvedor que o escreveu.

```java
LogSistematico
    .registrando("Pagamento recusado")
    .em(PagamentoService.class, "processar")
    .porque("Gateway recusou a transação — saldo insuficiente")  // ← Why
    ...
```

O `.porque()` deve expressar a causa em termos de domínio de negócio — não em termos técnicos. "Gateway recusou a transação — saldo insuficiente" é um *Why* útil. "IOException ao chamar o gateway" é um *What* disfarçado de *Why*.

---

## 6. How — Canal de Origem

A dimensão *How* responde: **por qual canal ou mecanismo este evento chegou ao sistema?**

O *How* é o contexto arquitetural do evento — informa se a ação foi disparada por uma requisição HTTP síncrona, uma mensagem de fila assíncrona, um job agendado ou uma chamada interna. Essa informação é valiosa para distinguir comportamentos esperados de comportamentos anômalos: um `LOGIN_FAILED` via API REST pode ser tentativa de força bruta; o mesmo evento via job de migração é esperado.

```java
LogSistematico
    .registrando("Nota fiscal processada")
    .em(NotaFiscalService.class, "processar")
    .como("Job assíncrono — scheduler diário 02:00 UTC")  // ← How
    ...
```

---

## 7. Além do Debugging

Um registro com conformidade rigorosa ao 5W1H destranca casos de uso que vão muito além do diagnóstico de erros:

### Analytics em tempo real

Eventos como `ORDER_COMPLETED` com `valorTotal`, `currency` e `userId` alimentam dashboards de KPIs diretamente no Kibana ou Grafana, sem a necessidade de um banco de dados de analytics separado ou um SDK de terceiros. O log estruturado é o pipeline de analytics.

### Conformidade regulatória (LGPD)

A trilha de auditoria construída sobre eventos 5W1H responde exatamente às perguntas exigidas por uma investigação regulatória: quem acessou (Who), quais dados (What), quando (When), de qual sistema (Where), por qual justificativa (Why) e por qual canal (How). Isso transforma o log de ferramenta de diagnóstico em evidência probatória.

### Detecção proativa de anomalias

A consistência estrutural do 5W1H habilita alertas baseados em padrão: um pico de `LOGIN_FAILED` do mesmo `userId` em curto intervalo sinaliza tentativa de força bruta; uma queda de 80% em `ORDER_COMPLETED` em relação à baseline sinaliza falha silenciosa no frontend; qualquer evento com `errorCode: "PAG-4022"` acima de um threshold dispara notificação no canal de plantão. Sem estrutura consistente, esses alertas não são possíveis.

### Resolução de disputas técnicas

O payload e a resposta de uma chamada a um gateway externo, registrados como eventos 5W1H com `traceId` e timestamp, constituem evidência técnica irrefutável para encerrar disputas sobre o que foi enviado, quando foi enviado e qual foi a resposta — sem depender de logs do sistema do parceiro.

---

## Fora do Escopo

### `hostname` e `pid` como campos do *Who*

Documentos anteriores incluíam `hostname` e `pid` como campos obrigatórios da dimensão *Who*. Em arquiteturas container-native (Kubernetes, Docker), o `hostname` é o nome do pod — um identificador efêmero que muda a cada restart e não tem valor diagnóstico estável. O `servico` (nome da aplicação) é o identificador de identidade relevante; a infra-estrutura de orquestração gerencia o mapeamento pod-serviço.

### Bean `@RequestScoped` dedicado para contexto

Documentação anterior sugeria injetar um bean `@RequestScoped` dedicado para carregar as dimensões *Who* e *Where*. O projeto usa `GerenciadorContextoLog` (`@ApplicationScoped`) + MDC como mecanismo de propagação. O MDC é o padrão do ecossistema SLF4J/JBoss Logging para propagação thread-local de contexto de diagnóstico — criar um bean de escopo de requisição paralelo seria redundante e criaria dois mecanismos de propagação para a mesma informação.

### Campos fixos obrigatórios para jobs por `queueName` ou `workerClass`

Documentação anterior forçava campos como `queueName` e `workerClass` como obrigatórios no JSON de jobs assíncronos. O projeto não pré-define campos específicos para tipos de executor. O contexto de jobs é declarado via `.como("canal")` e `.comDetalhe()` da DSL, sem acoplar o core da biblioteca a metadados de schedulers específicos.