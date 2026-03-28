# Padrão de Logging em Aplicações Java

**Resumo:** O registro de logs em aplicações de software frequentemente carece de sistematização, sendo tratado de forma artesanal e subjetiva pelos desenvolvedores. Este documento sintetiza uma revisão bibliográfica e mercadológica sobre heurísticas de logging, propondo diretrizes fundamentadas em estudos empíricos e literatura especializada para padronizar o que, como e quando registrar. O resultado é um padrão arquitetural que combina o framework 5W1H, *Fluent Interface*, *Domain Specific Language* e logging estruturado em JSON — tratando o log não como mecanismo de debug, mas como componente estrutural da engenharia de software e base para observabilidade.

> **Documentos relacionados**
> - [Implementação SLF4J + Log4j2](implementacao_slf4j.md) — código-fonte da biblioteca portável (Jakarta EE)
> - [Implementação Quarkus 3.27](biblioteca_quarkus.md) — código-fonte da biblioteca nativa Quarkus

---

## 1. Introdução

Historicamente, a prática de logging assemelha-se aos estágios iniciais dos testes automatizados: é altamente baseada na opinião individual do desenvolvedor. Equipes frequentemente divergem sobre quais linhas merecem registro e qual o nível de severidade adequado — `INFO`, `DEBUG` ou `ERROR` —, resultando em logs inconsistentes e de difícil utilização para *troubleshooting*.

O objetivo desta revisão é afastar o logging do empirismo e fundamentá-lo em dados acadêmicos e práticas validadas pela indústria. Mais do que isso: elevar o log à categoria de **componente arquitetural**, com o mesmo rigor exigido de segurança, persistência e contratos de API. Aplicações sem logging sistemático apresentam baixa rastreabilidade, alto MTTR (*Mean Time to Recovery*) e risco de falhas não diagnosticáveis em produção.

---

## 2. Fundamentação Teórica

A formulação de uma heurística de logging robusta exige a triangulação de fontes de mercado e pesquisas acadêmicas. A fundamentação deste padrão baseia-se nos seguintes pilares:

- **O Princípio dos 5Ws:** Emprestado do jornalismo e da análise de causa raiz (*Root Cause Analysis*), estrutura cada evento de log em dimensões investigativas.
- **Estudos Empíricos (Microsoft, 2010):** Análise quantitativa e qualitativa de bases de código massivas — de 2,5 a 10,4 milhões de linhas — para identificar padrões reais de onde e como desenvolvedores inserem logs.
- **Literatura Especializada:** Trabalhos de Anton Chuvakin, autoridade em segurança e gerenciamento de logs, referentes à categorização de eventos operacionais críticos.
- **Padrões de Microsserviços (Chris Richardson — microservices.io):** Catálogo de padrões de observabilidade para arquiteturas distribuídas, incluindo *Application Logging*, *Distributed Tracing*, *Exception Tracking* e *Audit Logging* — cada um com definição de responsabilidade, fronteiras e casos de uso distintos.
- **Padrões de Design de Software (Iluwatar — java-design-patterns):** Implementações de referência dos padrões *microservices-log-aggregation* e *microservices-distributed-tracing*, que formalizam o log como fluxo de dados estruturado e tratam a agregação centralizada como componente arquitetural de primeira classe.

**Princípio editorial:** mensagens de log devem usar linguagem direta, neutra e sem ambiguidade. Termos técnicos precisam ser usados com consistência; jargão informal, piadas ou abreviações que possam confundir outras equipes ou ferramentas de análise são proibidos. Logs são lidos em incidentes críticos, frequentemente por pessoas que não escreveram o código — a clareza não é opcional.

---

## 3. Taxonomia: O Que Registrar?

A ausência de um algoritmo determinístico para o logging exige o estabelecimento de heurísticas. A revisão identifica duas dimensões complementares de análise.

### 3.1. O Log como Fluxo de Dados (*Append-Only Stream*)

Antes de definir *o que* registrar, é necessário compreender a natureza estrutural do log. O padrão *microservices-log-aggregation* (Iluwatar) define o log como uma **sequência ordenada de registros do tipo *append-only***: cada evento é acrescentado ao final do fluxo, nunca modificado ou removido retroativamente.

Essa característica tem três implicações diretas para o design do sistema de logging:

- **Fonte da verdade:** em sistemas distribuídos, o fluxo de logs é a representação mais confiável do que realmente aconteceu. Um banco de dados registra o estado *atual*; o log registra cada *mudança de estado* ao longo do tempo. Um log completo permite reconstruir o estado de qualquer entidade em qualquer ponto do passado.
- **Agregação centralizada obrigatória:** em arquiteturas de microsserviços, cada instância gera seu próprio fluxo. Sem um ponto de agregação centralizado (ex.: ELK Stack, Loki, Datadog), os logs de uma única operação distribuída ficam espalhados em dezenas de arquivos em servidores diferentes, tornando o diagnóstico impraticável.
- **Imutabilidade como contrato:** alterar ou deletar um registro de log — mesmo para "corrigir" uma mensagem — viola o contrato do padrão e pode comprometer investigações de segurança, disputas técnicas e conformidade regulatória.

### 3.2. Categorias de Inserção de Log

O estudo da Microsoft identificou cinco cenários primários onde desenvolvedores inserem logs no código:

1. **Asserções (*Assertion Logging*):** Verificações de pré-condições, pós-condições e invariantes — ligadas ao conceito de *Design by Contract* e *Self-Testing Code*.
2. **Verificação de Retorno (*Return Value Checking*):** Validação de retornos de funções e integrações externas.
3. **Exceções (*Exception Logging*):** O registro de falhas sistêmicas com contexto suficiente para diagnóstico.
4. **Pontos de Execução (*Execution Point Logging*):** Logs observacionais para confirmar que o fluxo entrou em um bloco condicional específico.
5. **Rastreamento (*Trace Logging*):** Acompanhamento do caminho de execução ao longo de múltiplos componentes.

### 3.3. Eventos Críticos

Baseando-se em Anton Chuvakin, as seguintes classes de eventos devem **obrigatoriamente** gerar registros, independente da heurística do desenvolvedor:

- **Autenticação, Autorização e Acesso:** Sucessos, falhas e acessos remotos a componentes sensíveis.
- **Mudanças de Estado:** Persistência (create/update/delete), instalações e remoção de dados. Mudanças são os maiores vetores de falhas críticas.
- **Chamadas Externas:** Toda comunicação com APIs de terceiros, incluindo o *payload* enviado e a resposta recebida.
- **Disponibilidade:** Problemas de inicialização ou inatividade de componentes e dependências.
- **Exaustão de Recursos:** Falta de conexões no *pool* de banco de dados, estouros de memória ou indisponibilidade de rede.
- **Entradas Inválidas e Situações Inesperadas:** Recebimento de *payloads* maliciosos ou acesso a rotas não autorizadas.

---

## 4. O Framework 5W1H

Todo evento de log deve, obrigatoriamente, responder às seis dimensões do framework 5W1H para fornecer contexto investigativo completo:

| Dimensão | Pergunta | Exemplos práticos |
|---|---|---|
| **Who** (Quem) | Quem é o ator da ação? | `userId`, IP de origem, `visitorToken`, identidade anônima de sessão |
| **What** (O quê) | Qual é o evento? | "Pedido criado", "Login falhou", nível de severidade |
| **When** (Quando) | Quando ocorreu? | Timestamp ISO 8601 em **UTC** com milissegundos |
| **Where** (Onde) | Onde no sistema? | Serviço, classe, método, endpoint, `requestId`, nome do host, ID do container |
| **Why** (Por quê) | Qual o motivo de negócio? | "Saldo insuficiente", "Sessão expirada" |
| **How** (Como) | Por qual canal chegou? | "API REST", "Fila assíncrona", "Job agendado" |

As dimensões *When* (timestamp) e parte do *Where* (classe, método, host) são automáticas e preenchidas pela infraestrutura de logging. As demais exigem declaração explícita pelo desenvolvedor — e é exatamente aí que a DSL atua, guiando esse preenchimento.

**Observação:** Classe e método são metadados técnicos úteis, mas não substituem rastreabilidade funcional. Um log com apenas `PedidoService.criar` sem o `pedidoId`, o usuário e o motivo não permite diagnóstico eficiente.

### 4.1. Requisitos da Dimensão *When* em Sistemas Distribuídos

Em sistemas de instância única, o timestamp local é suficiente. Em arquiteturas distribuídas — múltiplos serviços, múltiplos servidores — um desvio de poucos segundos entre relógios de máquinas diferentes torna a ordenação cronológica dos eventos impossível, inviabilizando a reconstrução de qualquer sequência de causa e efeito.

**Requisito obrigatório:** todos os servidores que geram logs devem estar sincronizados com o **UTC** via **NTP** (*Network Time Protocol*). Sem sincronização de tempo, logs de serviços diferentes não podem ser intercalados em ordem confiável, mesmo que todos estejam em JSON e usando o mesmo formato de timestamp.

### 4.2. A Dimensão *Where*: `requestId` vs. `traceId`

A dimensão *Where* possui dois identificadores complementares que respondem a perguntas diferentes e devem **ambos estar presentes** em todo evento de log:

| Identificador | Escopo | Gerado por | Pergunta que responde |
|---|---|---|---|
| `requestId` | Uma única requisição HTTP em um único serviço | Filtro JAX-RS / servlet filter | "Todos os logs desta requisição neste serviço" |
| `traceId` | Toda a operação distribuída, através de todos os serviços | OpenTelemetry SDK | "Todos os logs de todos os serviços para esta operação do usuário" |

O `requestId` é útil para isolar o ciclo de vida de uma requisição dentro de um serviço — inclusive para correlacionar logs antes e depois de uma exceção no mesmo processo. O `traceId` é indispensável para cruzar fronteiras de serviço e reconstruir a jornada completa de uma operação em uma arquitetura de microsserviços.

Os dois coexistem e se complementam: o `requestId` é o identificador local; o `traceId` é o identificador global distribuído.

---

## 5. Princípios de Design da API

A biblioteca de logging é construída sobre quatro princípios de design que se reforçam mutuamente.

### 5.1. Domain Specific Language (DSL)

Uma DSL de domínio é uma linguagem de programação projetada para um problema específico. No contexto de logging, o objetivo é tornar a chamada ao logger tão expressiva quanto uma descrição em linguagem natural do evento ocorrido.

Em vez de:

```java
// PROIBIDO — concatenação de string sem estrutura nem guia
log.info("Order " + orderId + " saved by user " + userId);
```

A DSL produz:

```java
// CORRETO — legível como uma frase, estruturado, validado pelo compilador
LogSistematico
    .registrando("Pedido salvo")
    .em(PedidoService.class, "criar")
    .porque("Solicitação do cliente via checkout")
    .como("API REST — POST /pedidos")
    .comDetalhe("orderId", orderId)
    .comDetalhe("userId",  userId)
    .info();
```

Os nomes dos métodos são deliberadamente em português e no vocabulário do domínio operacional — `registrando`, `porque`, `como`, `comDetalhe` — em vez de termos técnicos genéricos como `set`, `add` ou `with`. Isso reduz a fricção cognitiva e torna o log legível como uma sentença.

### 5.2. Fluent Interface

A *Fluent Interface* é o mecanismo técnico que viabiliza a DSL. Cada método retorna a próxima interface na cadeia, criando um fluxo de chamadas encadeado e auto-documentado. A sequência de preenchimento é validada em tempo de compilação: não é possível chamar `.info()` sem ter passado antes por `.registrando()` e `.em()`.

```
LogSistematico
    .registrando(evento)      ← Ponto de entrada — retorna EtapaOnde
    .em(classe, metodo)       ← Obrigatório     — retorna EtapaOpcional
    [ .porque(motivo)    ]    ← Opcional
    [ .como(canal)       ]    ← Opcional
    [ .comDetalhe(k, v)  ]*   ← Zero ou mais
    .info() | .debug() | .warn() | .erro(ex)
```

Essa progressão guiada elimina a principal causa de logs incompletos: o desenvolvedor simplesmente não sabe o que deveria preencher. A Fluent Interface torna o preenchimento correto o caminho de menor resistência.

### 5.3. Contexto Automático via MDC

O MDC (*Mapped Diagnostic Context*) usa `ThreadLocal` para propagar dados ao longo de toda a cadeia de chamadas de uma requisição, sem que cada método precise receber o usuário como parâmetro. Um CDI Interceptor popula o MDC no início de cada invocação e garante sua limpeza no bloco `finally`, evitando vazamento entre *threads*.

Esse mecanismo resolve a dimensão *Who* do 5W1H automaticamente: o `userId`, o `traceId` e o `spanId` estão presentes em todos os logs de uma requisição sem que o desenvolvedor precise declará-los manualmente.

### 5.4. Imutabilidade dos Objetos de Valor

Todos os objetos de valor da biblioteca — `LogEvento`, `AuditRecord`, `LogContexto` — são *Records* Java 21 imutáveis. Imutabilidade garante *thread-safety* estrutural sem sincronização e elimina erros de estado compartilhado em ambientes concorrentes. Nenhum estado mutável deve ser adicionado a esses objetos.

```java
// Definido pela biblioteca — não adicionar estado mutável
public record AuditRecord(
    String  actorId,
    String  action,
    String  entityType,
    String  entityId,
    Object  stateBefore,
    Object  stateAfter,
    Instant timestamp,
    String  traceId
) {}
```

---

## 6. Logs Estruturados em JSON

O formato JSON é mandatório. Logs em texto puro são legíveis para humanos no terminal, mas são um obstáculo para máquinas: qualquer extração de informação exige expressões regulares complexas e frágeis. Com JSON, cada dimensão do 5W1H torna-se um campo pesquisável, tipado e indexável.

**Comparação:**

```
# Texto puro — não estruturado, difícil de consultar
[ORDER-123] [USER-45] Payment failed at 2026-03-11

# JSON — cada campo é uma chave pesquisável de primeiro nível
{
  "timestamp":  "2026-03-11T21:55:00.123Z",
  "level":      "ERROR",
  "message":    "Falha ao processar pagamento",
  "userId":     "joao.silva",
  "traceId":    "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":     "a3ce929d0e0e4736",
  "log_classe": "PagamentoService",
  "log_metodo": "processar",
  "log_motivo": "Gateway recusou a transação",
  "detalhe_pedidoId":          "4821",
  "detalhe_codigoErroGateway": "INSUFFICIENT_FUNDS"
}
```

Com logs em JSON, o sistema de observabilidade deixa de ser uma ferramenta de busca de texto e passa a ser um banco de dados consultável:

```
# Query real em Elasticsearch / Kibana
level: ERROR AND log_motivo: "Gateway*" AND @timestamp:[now-1h TO now]
```

**Regras obrigatórias:**

- É proibido montar pseudo-JSON via `String.format` ou concatenação.
- É proibido usar `System.out.println` ou `System.err.println`.
- Nomes de campos devem ser consistentes em toda a aplicação. Consulte o [Registro de Nomes de Campos](FIELD_NAMES.md).
- Dados sensíveis não devem ser registrados — mascaramento automático via `SanitizadorDados`.
- Serialização JSON deve usar `ObjectMapper` pré-compilado com módulos registrados — não reflexão por evento.
- O transporte de logs entre nós da rede deve usar **SSL/TLS**, garantindo confidencialidade e autenticidade do fluxo de dados de observabilidade em trânsito.

---

## 7. Padrões Proibidos

Os padrões abaixo são estritamente proibidos. *Code Review* deve rejeitar qualquer *pull request* que os introduza.

### 7.1. `System.out` e Concatenação de Strings

```java
// PROIBIDO
System.out.println("Order saved: " + orderId);
System.err.println("Error: " + e.getMessage());
log.info("Order " + orderId + " saved by user " + userId);
log.info(String.format("{\"order_id\":\"%s\"}", orderId));

// CORRETO — campos estruturados via key-value nativo (SLF4J 2.x)
logger.atInfo()
    .addKeyValue("orderId", orderId)
    .addKeyValue("userId",  userId)
    .log("Order saved");
```

### 7.2. Registro Apenas da Mensagem da Exceção

```java
// PROIBIDO — descarta classe, stack trace e cadeia de causas
log.error(e.getMessage());
log.error("Error: " + e.getMessage());

// CORRETO — objeto de exceção completo como último argumento
log.error("Falha ao processar Order#{}", orderId, e);
```

### 7.3. Mensagens Genéricas sem Identificadores de Entidade

```java
// PROIBIDO — sem valor diagnóstico
log.error("Error saving");
log.warn("Validation failed");
log.info("Started");

// CORRETO — inclui contexto específico e identificadores
log.error("Falha ao salvar Order#{}: {}", orderId, e.getMessage(), e);
log.warn("Validação falhou para Order#{}: campo '{}' obrigatório", orderId, fieldName);
log.info("Processamento iniciado: Order#{} por User#{}", orderId, userId);
```

### 7.4. Log-and-Throw (Duplicação de Exceção)

A mesma exceção registrada em múltiplas camadas sem contexto adicional gera duplicação de ruído que prejudica o diagnóstico.

```java
// PROIBIDO — será logada novamente pela camada superior
catch (OrderException e) {
    log.error("Order processing failed", e);
    throw e;
}

// CORRETO — loga na fronteira onde a exceção é tratada, com contexto completo
catch (OrderException e) {
    log.error("Order processing failed: Order#{}", orderId, e);
    return Response.serverError().entity(ErrorResponse.from(e)).build();
}
```

### 7.5. Registro de Dados Sensíveis

```java
// PROIBIDO
log.info("Payment initiated for card: {}", creditCardNumber);
log.debug("User authenticated: password={}", password);
log.info("User data: {}", userObject); // se userObject contiver PAN, CPF, etc.

// CORRETO — apenas identificadores parciais ou mascarados
log.info("Payment initiated for card ending in: {}", last4Digits);
log.info("User #{} authenticated successfully", userId);
```

### 7.6. TraceId Gerado Manualmente

```java
// PROIBIDO — identificador falso, não correlacionável com tracing distribuído
String traceId = UUID.randomUUID().toString();
MDC.put("traceId", traceId);

// CORRETO — traceId real extraído do span OpenTelemetry ativo
String traceId = TraceContextExtractor.currentTraceId();
if (traceId != null) {
    MDC.put("traceId", traceId);
}
```

### 7.7. MDC sem Limpeza no `finally`

```java
// PROIBIDO — contexto vaza para requisições subsequentes na mesma thread
MDC.put("userId", userId);
processRequest();

// CORRETO — limpeza garantida independente de exceção
MDC.put("userId", userId);
try {
    processRequest();
} finally {
    MDC.clear();
}
```

### 7.8. Guarda de Nível Ausente para Computações Custosas

```java
// PROIBIDO — serializa order mesmo com DEBUG desabilitado em produção
log.debug("Order state: {}", objectMapper.writeValueAsString(order));

// CORRETO — custo de serialização pago apenas se o nível estiver habilitado
if (log.isDebugEnabled()) {
    log.debug("Order state: {}", objectMapper.writeValueAsString(order));
}
```

---

## 8. Padrões Obrigatórios

### 8.1. Logging Estruturado via Key-Value (SLF4J 2.x)

```java
logger.atInfo()
    .addKeyValue("action",   "processarPedido")
    .addKeyValue("orderId", orderId)
    .addKeyValue("userId",  userId)
    .log("Order processing started");
```

Ou via MDC para contexto que se aplica a todas as linhas de log em um escopo:

```java
MDC.put("orderId", orderId.toString());
log.info("Processing started");
// ... múltiplos logs carregam orderId automaticamente
MDC.remove("orderId");
```

### 8.2. Tratamento de Exceções com Contexto Completo

```java
try {
    orderService.process(order);
} catch (Exception e) {
    log.error("Order processing failed: Order#{}", order.getId(), e);
    exceptionReporter.report(e, Map.of("orderId", order.getId()));
    throw new ServiceException("Order processing failed", e);
}
```

### 8.3. Eventos de Negócio

Eventos relevantes para o negócio devem ser registrados com a DSL dedicada — não com `log.info()` genérico — para serem identificáveis como categoria distinta nos sistemas de observabilidade:

```java
LogSistematico
    .registrando("Pedido concluído")
    .em(PedidoService.class, "concluir")
    .comDetalhe("eventType", "ORDER_COMPLETED")
    .comDetalhe("orderId", order.getId())
    .comDetalhe("orderValue", order.getTotal())
    .comDetalhe("currency", "BRL")
    .comDetalhe("itemsCount", order.getItems().size())
    .info();
```

---

## 9. Gestão de Níveis de Severidade

A escolha do nível de log deve ser determinística, não subjetiva. A regra é baseada no impacto sobre o estado do sistema:

| Nível | Quando usar | Habilitado em produção? |
|---|---|---|
| `TRACE` | Diagnóstico de baixo nível: entradas e saídas de métodos, iterações de loop, valores intermediários detalhados | Nunca — apenas em desenvolvimento local |
| `DEBUG` | Fluxos internos, decisões condicionais, dados intermediários sem alteração de estado | Não por padrão — ativável dinamicamente por pacote |
| `INFO` | Operações que alteram estado: persistência, autenticação, chamadas externas, mudanças relevantes | Sempre |
| `WARN` | Situações anômalas recuperáveis: tentativas de acesso indevido, *fallbacks* ativados, validações rejeitadas | Sempre |
| `ERROR` | Falhas reais: exceção que impede o cumprimento do contrato da operação | Sempre |
| `FATAL` | Falhas que tornam a aplicação incapaz de continuar operando — exigem intervenção imediata (ex: corrupção de estado crítico, falha de inicialização irrecuperável) | Sempre |

**Regra anti-duplicação:** é proibido registrar a mesma exceção em múltiplas camadas sem agregar contexto adicional. Cada camada loga apenas o que sabe a mais sobre o erro.

**Regra de exceção completa:** é proibido registrar apenas `e.getMessage()`. O objeto de exceção completo deve sempre ser passado ao logger, preservando classe, *stack trace* e cadeia de causas.

**Códigos de erro únicos:** eventos críticos de negócio e de infraestrutura devem receber códigos únicos e estáveis (ex: `APP-1001`, `PAG-4022`). Esses códigos são a chave de ligação entre o log em produção e a **Base de Conhecimento de Erros Conhecidos (KEDB)** — um repositório interno que documenta causa raiz, impacto e procedimento de remediação para cada código. Quando um operador vê `APP-1001` em um alerta às 3 da manhã, ele consulta a KEDB e executa o procedimento documentado, sem precisar interpretar a mensagem de log do zero.

```json
{
  "level":      "ERROR",
    "errorCode": "PAG-4022",
  "message":    "Falha ao processar pagamento",
  "log_motivo": "Gateway recusou a transação — código INSUFFICIENT_FUNDS"
}
```

---

## 10. Segurança e Governança

O logging deve seguir o princípio de *data minimization*: registrar apenas o que é necessário para diagnóstico e auditoria, nunca mais.

**São proibidos nos logs:**

- Senhas e hashes de senha
- Tokens de autenticação e autorização (JWT, API keys)
- Números de cartão de crédito (PAN) e CVV
- CPF, RG e dados pessoais sensíveis conforme LGPD
- Dados que identifiquem menores de idade

Quando um campo de negócio contém dado sensível, a sanitização ocorre automaticamente via `SanitizadorDados`, que intercepta os valores pelos nomes das chaves. Dois modos de proteção são aplicados:

- **Mascaramento:** o valor é substituído por uma representação reduzida que confirma presença sem expor o conteúdo — ex: `"****"` para senhas, `"**** **** **** 4242"` para cartões. Preserva a evidência de que o campo foi fornecido.
- **Redação:** o valor é completamente removido do registro — útil quando nem a confirmação de presença pode ser registrada (ex: dados de menores, informações sob sigilo legal). O campo simplesmente não aparece no JSON.

A escolha entre mascaramento e redação depende do requisito regulatório aplicável. Em caso de dúvida, prefira a redação.

**Proteção em trânsito:** o transporte de logs entre nós da rede deve usar **SSL/TLS**. Um log mascarado na aplicação pode ter seu conteúdo original interceptado em trânsito se o canal não estiver criptografado — a proteção de dados sensíveis deve ser aplicada em todas as camadas do pipeline.

---

## 11. Falhas Silenciosas na Infraestrutura de Observabilidade

Falhas na infraestrutura de observabilidade **nunca devem propagar como exceções de negócio**. Se o backend de rastreamento, o exportador OpenTelemetry ou o pipeline de métricas estiver indisponível, o sistema registra a falha localmente e continua operando.

```java
// CORRETO — falha de observabilidade não interrompe o fluxo de negócio
try {
    trackingBackend.report(exceptionRecord);
} catch (Exception backendException) {
    fallbackLogger.warn("Exception tracking backend unavailable: {}",
            backendException.getMessage());
    // NÃO relançar — lógica de negócio não falha por causa de observabilidade
}
```

Essa regra se aplica a: backends de rastreamento de exceções, exportadores OpenTelemetry, pipelines de métricas Micrometer e qualquer integração de observabilidade externa. O código de negócio não deve conhecer nem depender da disponibilidade dessas integrações.

---

## 12. Observabilidade e Operação

### 12.1. Os Três Pilares da Observabilidade

| Pilar | Tecnologia | O que responde |
|---|---|---|
| **Logs** | JSON estruturado + ELK / Datadog / Loki | "O que aconteceu e em qual contexto?" |
| **Métricas** | Micrometer + Prometheus + Grafana | "Com que frequência e volume?" |
| **Tracing** | OpenTelemetry + Jaeger / Zipkin | "Qual o caminho completo da requisição?" |

O `traceId` e o `spanId` gerados pelo OpenTelemetry são a chave que une os três pilares. O `traceId` identifica toda a operação distribuída de ponta a ponta — o mesmo valor em todos os serviços envolvidos. O `spanId` identifica uma etapa específica dentro desse trace: cada serviço, cada chamada de banco, cada operação relevante gera seu próprio `spanId`. Filtrar por `traceId` reconstrói a história completa; filtrar por `spanId` isola exatamente o componente que falhou.

Por isso, **ambos os identificadores nunca devem ser gerados manualmente** — devem sempre ser extraídos do contexto OTel ativo.

> ⚠️ **Escopo deste documento:** os pilares de métricas e tracing são mencionados para contextualizar o papel dos logs no ecossistema de observabilidade. A implementação desses pilares é tratada separadamente e está fora do escopo deste padrão de logging.

### 12.2. Correlação por Identificadores: `requestId` e `traceId`

Conforme detalhado na seção 4.2, dois identificadores de correlação devem estar presentes em todo evento de log:

```json
{
    "requestId": "a3f9c2d1-7b44-4e2a-9c2d-1a3b9c2d17b4",
    "traceId":   "7d2c8e4f1a3b9c2d4bf92f3577b34da6"
}
```

O `requestId` é gerado pelo filtro JAX-RS da aplicação e permanece estável durante toda a requisição dentro de um único serviço. O `traceId` é propagado pelo OpenTelemetry através dos cabeçalhos HTTP (`traceparent` — padrão W3C TraceContext) e é o mesmo em todos os serviços que participam da mesma operação distribuída.

**Ambos são necessários.** Em uma arquitetura de microsserviços, o `traceId` é o identificador que permite reconstruir a história completa de uma operação — sem ele, logs de serviços diferentes são ilhas de informação desconectadas, mesmo que estejam no mesmo sistema de log aggregation.

### 12.3. Ambientes Reativos e Virtual Threads

Em aplicações com RESTEasy Reactive, Mutiny ou Vert.x, o MDC isolado não é suficiente: a execução pode trocar de *thread* entre operações assíncronas e o `ThreadLocal` é silenciosamente perdido. Nesses ambientes é obrigatório o uso de **SmallRye Context Propagation** (Quarkus) ou equivalente.

Para cenários de alta concorrência, *virtual threads* (Project Loom, Java 21) oferecem propagação de contexto não-bloqueante sem a complexidade de pipelines reativos explícitos — e são a abordagem preferida em novas implementações.

### 12.4. Alteração Dinâmica de Nível

Aplicações em produção devem permitir a ativação do nível `DEBUG` por pacote específico em tempo de execução, sem reinicialização, para investigar incidentes ativos sem elevar o volume global de logs.

### 12.5. Logs como Base de Alertas e Analytics

Logs estruturados habilitam uso operacional e de negócio além do debugging. A configuração de alertas automáticos sobre eventos de log é uma das formas mais eficientes de detecção proativa de problemas:

- **Analytics em tempo real:** eventos como `ORDER_COMPLETED` alimentam dashboards sem onerar o banco de dados principal.
- **Alertas automáticos baseados em padrões:** pico de `LOGIN_FAILED` do mesmo IP em curto intervalo dispara alerta de força bruta; queda de 80% em `ORDER_CREATED` sinaliza falha silenciosa no frontend; qualquer log com `error_code: PAG-4022` acima de um limiar dispara notificação no canal de plantão.
- **Integração com canais de operação:** ferramentas como Fluentd permitem rotear eventos de log específicos diretamente para Slack, PagerDuty ou scripts de remediação automática — transformando o log em gatilho operacional, não apenas arquivo histórico.
- **Auditoria e prova técnica:** *payload* e resposta de APIs externas criam evidências imutáveis para encerrar disputas técnicas.
- **Otimização de performance:** duração de operações registrada permite identificar gargalos com dados reais de produção.

---

## 13. Log de Auditoria

> Padrão de referência: Chris Richardson — [Audit Logging (microservices.io)](https://microservices.io/patterns/observability/audit-logging.html)

### 13.1. Definição e Distinção

Log de auditoria é o registro permanente e consultável de **ações de usuários sobre entidades de negócio**. É um padrão distinto do log de aplicação — os dois são complementares e nenhum substitui o outro.

| Dimensão | Log de Aplicação | Log de Auditoria |
|---|---|---|
| **Propósito** | Diagnóstico técnico, resposta a incidentes | Conformidade, responsabilização, resolução de disputas |
| **Consumidor** | Engenheiros, SRE | Jurídico, segurança, suporte ao cliente, reguladores |
| **Retenção** | Dias a semanas | Meses a anos (exigências regulatórias) |
| **Mutabilidade** | *Append-only* | Deve ser imutável e à prova de adulteração |
| **Granularidade** | Eventos técnicos (erros, latência, fluxo) | Ações de negócio (quem alterou o quê, de qual valor para qual valor) |

A confusão entre os dois tipos leva a um dos seguintes problemas: logs de aplicação sobrecarregados com campos de auditoria que não têm relação com diagnóstico técnico, ou ausência de trilha de auditoria real porque se assumiu que o log de aplicação a cobriria.

### 13.2. Campos Obrigatórios de um Registro de Auditoria

Todo registro de auditoria deve responder às seguintes dimensões:

| Campo | Descrição |
|---|---|
| `actorId` | Quem executou a ação (`userId` ou identidade de sistema) |
| `actorIp` | Endereço IP de origem da requisição |
| `sessionId` | Identificador de sessão (vincula ao evento de autenticação) |
| `action` | Tipo de ação: `CREATE`, `UPDATE`, `DELETE`, `READ` (para dados sensíveis), `LOGIN`, `LOGOUT` |
| `entityType` | Tipo da entidade afetada (ex: `UserProfile`, `Order`, `PaymentMethod`) |
| `entityId` | Identificador da entidade afetada |
| `stateBefore` | Snapshot do estado relevante da entidade **antes** da ação |
| `stateAfter` | Snapshot do estado relevante da entidade **depois** da ação |
| `@timestamp` | Timestamp UTC com precisão de milissegundos |
| `traceId` | Correlação com o trace distribuído da requisição |
| `outcome` | `SUCCESS` ou `FAILURE` (com motivo em caso de falha) |

### 13.3. O Que Deve Ser Auditado

As seguintes ações devem **sempre** gerar um registro de auditoria — independente de já gerarem um log de aplicação:

- Eventos de autenticação: `LOGIN`, `LOGIN_FAILED`, `LOGOUT`, `PASSWORD_CHANGED`
- Decisões de autorização: `ACCESS_DENIED` em recursos sensíveis
- Mutações de dados em entidades sensíveis: `CREATE`, `UPDATE`, `DELETE` em Usuário, Pedido, Pagamento, Conta
- Ações administrativas: alterações de papel, atualizações de configuração, operações em lote
- Exportações de dados: qualquer ação que faça dados pessoais sair do sistema

### 13.4. Casos de Uso

**Conformidade (LGPD):** demonstrar quais ações foram tomadas sobre dados pessoais, por quem e quando — evidência exigível a qualquer momento por autoridade regulatória.

**Suporte ao cliente:** quando um cliente contesta uma alteração em sua conta, o registro de auditoria fornece a resposta factual: *"Seu endereço de e-mail foi alterado de a@x.com para b@x.com pelo usuário USR-445, via IP 200.x.x.x, em 2026-03-09T14:32:01Z."*

**Investigação de segurança:** se um incidente é suspeito, o log de auditoria responde quais contas foram acessadas, quais dados foram lidos e de quais endereços IP — mesmo que os logs de aplicação já tenham sido rotacionados.

**Resolução de disputas com terceiros:** quando uma API externa (gateway de pagamento, operadora logística) alega que seu serviço enviou dados incorretos, o registro da requisição enviada — com *payload*, timestamp e resposta — é evidência técnica irrefutável.

> ⚠️ **Implementação futura:** a implementação do mecanismo de auditoria automática (interceptor `@Auditable`, `AuditWriter`, pipeline de persistência) está planejada para uma versão futura da biblioteca. Os campos e casos de uso descritos acima definem o contrato que essa implementação deverá satisfazer.

---

## 14. Rastreamento de Exceções

> Padrão de referência: Chris Richardson — [Exception Tracking (microservices.io)](https://microservices.io/patterns/observability/exception-tracking.html)

### 14.1. O Problema da Degradação Silenciosa

Simplesmente registrar uma exceção em log não é suficiente em sistemas de produção com alto volume. O cenário a seguir é comum quando não existe rastreamento centralizado de exceções:

1. Uma alteração de código introduz um bug que lança `NullPointerException` em 3% das requisições.
2. A exceção é registrada no log — mas é uma linha entre milhares, sem nenhum alerta.
3. O volume de erros cresce silenciosamente por horas.
4. Usuários começam a reclamar; a equipe inicia a investigação.
5. A investigação exige *grep* manual em logs de múltiplas instâncias para identificar padrão.

Com rastreamento centralizado de exceções, a mesma situação seria:

1. A `NullPointerException` é reportada na primeira ocorrência.
2. A equipe recebe uma notificação: *"Nova exceção: NullPointerException em OrderService.processOrder() — 142 ocorrências nos últimos 5 minutos."*
3. A investigação começa imediatamente, com link direto para o stack trace agrupado e o contexto da requisição.

### 14.2. Rastreamento de Exceções vs. Agregação de Logs

Os dois padrões são **complementares**, não concorrentes. Uma exceção deve ser simultaneamente:

1. **Registrada** — no fluxo de log estruturado, com o contexto 5W1H completo, para correlação com a linha do tempo da requisição.
2. **Reportada** — ao serviço de rastreamento centralizado, para de-duplicação, atribuição de responsabilidade e acompanhamento de resolução.

| Preocupação | Agregação de Logs | Rastreamento de Exceções |
|---|---|---|
| Armazenamento | Série temporal *append-only* | Agrupado por *fingerprint* |
| De-duplicação | Nenhuma — cada ocorrência é um registro separado | Exceções idênticas são agrupadas |
| Notificação | Requer configuração manual de alerta | Automática em novos tipos de exceção |
| Acompanhamento de resolução | Não | Sim — estados aberto/resolvido/ignorado |
| Volume coberto | Todos os eventos | Apenas exceções |

### 14.3. Fingerprinting e De-duplicação

Um *fingerprint* é um identificador estável para uma **classe** de exceções — de forma que a 1.000ª ocorrência do mesmo bug seja reconhecida como o mesmo bug, e não como 1.000 problemas distintos.

O *fingerprint* é calculado a partir de:
- Nome da classe da exceção
- Os N primeiros *stack frames* do código próprio da aplicação (ignorando frames de frameworks e bibliotecas)
- Opcionalmente, a mensagem da exceção — apenas quando contém identificadores estáveis, não dados dinâmicos

### 14.4. A Importância de Passar o Objeto de Exceção Completo

Este padrão reforça diretamente a seção 7.2 (Padrões Proibidos): a qualidade do rastreamento de exceções depende inteiramente de receber o objeto de exceção completo, não apenas sua mensagem.

```java
// PROIBIDO — descarta classe, stack trace e cadeia de causas
//            inviabiliza fingerprinting e agrupamento
logger.error(e.getMessage());

// CORRETO — preserva todas as informações necessárias para rastreamento
logger.error("Order processing failed: Order#{}", orderId, e);
```

O objeto de exceção contém: `getClass().getName()` (para *fingerprinting* e agrupamento), `getStackTrace()` (para localizar o bug), `getCause()` (para entender causas raiz em exceções encadeadas) e `getMessage()` (para contexto legível). Descartar o objeto descarta tudo isso.

> ⚠️ **Implementação futura:** a implementação do `ExceptionReporter` — CDI bean com integração a backends como Sentry, Rollbar ou webhook customizado — está planejada para uma versão futura da biblioteca. As boas práticas de logging descritas acima (objeto completo, sem log-and-throw) são os pré-requisitos que tornarão essa integração eficaz quando for implementada.

---

## 15. Volume: Excesso vs. Omissão

Em casos de dúvida sobre a relevância de uma informação, a literatura favorece o excesso. Deixar de registrar uma informação crítica é significativamente mais prejudicial do que registrar dados supérfluos. Um log desnecessário pode ser filtrado; um log ausente no momento de um incidente crítico é irrecuperável.

---

## 16. Performance

A infraestrutura de logging não deve introduzir latência observável no caminho crítico:

- Injeção de contexto deve ser O(1) — sem consultas a banco de dados ou chamadas de rede síncronas no caminho do MDC.
- Serialização JSON deve usar `ObjectMapper` pré-compilado com módulos registrados (Jackson) — não reflexão por evento.
- Operações MDC são inserções em mapa `ThreadLocal` — não criar objetos complexos nesse caminho.
- Computações custosas protegidas por guarda de nível (ver seção 7.8).

---

## 17. Implementação da Biblioteca

O código-fonte está documentado em arquivos separados, organizados por plataforma:

| Implementação | Arquivo | Quando usar |
|---|---|---|
| **SLF4J + Log4j2 + CDI** | [implementacao_slf4j.md](implementacao_slf4j.md) | Wildfly, TomEE, Payara, OpenLiberty — qualquer container Jakarta EE |
| **Quarkus 3.27 nativo** | [biblioteca_quarkus.md](biblioteca_quarkus.md) | Aplicações Quarkus — JVM ou native image GraalVM |

Ambas as implementações expõem a mesma API pública (`LogSistematico`, `@Logged`) e produzem o mesmo JSON estruturado. O que difere é a infraestrutura interna: logger, MDC, configuração JSON, ativação do interceptor e propagação de contexto reativo.

---

## 18. Exemplos de Uso

### Caso 1 — Persistência (evento obrigatório)

```java
@ApplicationScoped
@Logged  // Injeta userId, traceId, spanId e métricas automaticamente
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    public Pedido criar(NovoPedidoRequest request) {
        Pedido pedido = new Pedido(request);
        repository.salvar(pedido);

        LogSistematico
            .registrando("Pedido criado")
            .em(PedidoService.class, "criar")
            .porque("Solicitação do cliente via checkout")
            .como("API REST — POST /pedidos")
            .comDetalhe("pedidoId",   pedido.getId())
            .comDetalhe("valorTotal", pedido.getValorTotal())
            .info();

        return pedido;
    }
}
```

**JSON gerado:**

```json
{
  "timestamp":          "2026-03-11T21:55:00.123Z",
  "level":              "INFO",
  "message":            "Pedido criado",
  "userId":             "joao.silva@empresa.com",
  "traceId":            "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":             "a3ce929d0e0e4736",
  "log_classe":         "PedidoService",
  "log_metodo":         "criar",
  "log_motivo":         "Solicitação do cliente via checkout",
  "log_canal":          "API REST — POST /pedidos",
  "detalhe_pedidoId":   "4821",
  "detalhe_valorTotal": "349.90"
}
```

---

### Caso 2 — Validação sem alteração de estado (DEBUG)

```java
public List<Pedido> buscarPorCliente(Long clienteId) {
    if (clienteId == null) {
        LogSistematico
            .registrando("Busca por cliente ignorada")
            .em(PedidoService.class, "buscarPorCliente")
            .porque("clienteId ausente na requisição")
            .debug();

        return Collections.emptyList();
    }
    return repository.findByCliente(clienteId);
}
```

---

### Caso 3 — Erro com exceção e contexto de gateway

```java
public void processar(Long pedidoId) {
    try {
        gateway.cobrar(pedidoId);
    } catch (GatewayException e) {
        LogSistematico
            .registrando("Falha ao processar pagamento")
            .em(PedidoService.class, "processar")
            .porque("Gateway recusou a transação")
            .comDetalhe("pedidoId",          pedidoId)
            .comDetalhe("codigoErroGateway", e.getCodigo())
            .erro(e);

        throw new PagamentoException("Pagamento não processado", e);
    }
}
```

---

### Caso 4 — Dado sensível mascarado automaticamente

```java
// "password" → "****"  |  "email" → "[PROTEGIDO]"  |  "ipOrigem" → valor original
LogSistematico
    .registrando("Tentativa de autenticação")
    .em(AutenticacaoService.class, "autenticar")
    .comDetalhe("email",    request.email())    // ← "[PROTEGIDO]"
    .comDetalhe("password", request.senha())    // ← "****"
    .comDetalhe("ipOrigem", request.ip())
    .warn();
```

---

### Caso 5 — Evento de negócio

```java
LogSistematico
    .registrando("Pedido concluído")
    .em(PedidoService.class, "concluir")
    .comDetalhe("eventType", "ORDER_COMPLETED")
    .comDetalhe("orderId", pedido.getId())
    .comDetalhe("orderValue", pedido.getValorTotal())
    .comDetalhe("currency", "BRL")
    .comDetalhe("itemsCount", pedido.getItens().size())
    .info();
```

---

## 19. Não Conformidades

São consideradas não conformidades graves, a serem bloqueadas em *Code Review*:

| Não conformidade | Impacto |
|---|---|
| `System.out.println` ou `e.printStackTrace()` | Sem estrutura, sem nível, sem MDC |
| Concatenação de strings ou pseudo-JSON | Campo não indexável, frágil a caracteres especiais |
| `log.error(e.getMessage())` sem objeto completo | Descarta stack trace e cadeia de causas |
| Mensagens genéricas sem identificadores de entidade | Inúteis para diagnóstico em produção |
| Log-and-throw sem contexto adicional | Duplicação de erro sem valor analítico |
| Dados sensíveis sem mascaramento | Violação de LGPD e políticas de segurança |
| `traceId` gerado como `UUID.randomUUID()` | Impossibilita correlação com tracing distribuído |
| MDC sem limpeza no `finally` | Vazamento de contexto entre threads em produção |
| Computação custosa sem guarda de nível | Overhead de serialização mesmo com nível desabilitado |
| Eventos de negócio via `log.info()` genérico | Não identificáveis como categoria em observabilidade |
| Falha de observabilidade relançada como exceção de negócio | Interrompe o fluxo de negócio por falha de infraestrutura |

---

## 20. Checklist de Code Review

Antes de aprovar qualquer *pull request* que toque em código de observabilidade:

- [ ] Nenhum `System.out.println` ou `System.err.println`
- [ ] Nenhuma concatenação de string ou `String.format` em mensagens de log
- [ ] Nenhum `log.error(e.getMessage())` — objeto de exceção completo passado
- [ ] Nenhuma mensagem genérica — identificadores de entidade presentes
- [ ] Nenhum log-and-throw sem contexto adicional
- [ ] Nenhum dado sensível (senhas, tokens, PAN, CPF) nos campos de log
- [ ] Nenhum `UUID.randomUUID()` como `traceId` — contexto OpenTelemetry usado
- [ ] MDC limpo no bloco `finally`
- [ ] Computações custosas protegidas por guarda de nível
- [ ] Nomes de campos canônicos do [Registro de Nomes de Campos](FIELD_NAMES.md) usados
- [ ] Eventos de negócio usam `LogSistematico` com `eventType` — não `log.info()` genérico
- [ ] Falhas de backend de observabilidade tratadas localmente — não relançadas

---

## 21. Ciclo de Melhoria Contínua

Logging é um componente vivo da arquitetura. Após cada incidente em produção:

1. Revisar os logs gerados durante o incidente.
2. Identificar quais informações estavam ausentes e atrasaram o diagnóstico.
3. Atualizar a biblioteca ou o padrão para que a lacuna seja preenchida automaticamente no futuro.
4. Incorporar a melhoria como novo padrão organizacional e atualizar o checklist de *Code Review*.

---

## 22. Conclusão

O logging sistemático deixa de ser uma prática subjetiva e passa a ser um **componente arquitetural com contratos claros**: o framework 5W1H define o que deve estar presente em cada evento; a DSL e a Fluent Interface tornam o preenchimento correto o caminho natural; o CDI Interceptor elimina o trabalho repetitivo de propagar contexto; e o JSON estruturado transforma cada log em um dado pesquisável.

O resultado é uma camada de observabilidade que serve simultaneamente ao desenvolvedor depurando um erro às 3 da manhã, ao time de operações monitorando alertas em produção, ao time de negócio acompanhando métricas de conversão e ao time jurídico auditando acessos para conformidade com a LGPD.

Logs sistemáticos não são overhead — são a memória do sistema.

---

## Referências

**Implementações da biblioteca:**
- [Implementação SLF4J + Log4j2](implementacao_slf4j.md) — código-fonte completo da biblioteca portável
- [Implementação Quarkus 3.27](biblioteca_quarkus.md) — código-fonte completo da biblioteca nativa Quarkus
- [Registro de Nomes de Campos](FIELD_NAMES.md) — nomes canônicos dos campos JSON

**Fundamentos deste padrão:**
- Microsoft Research (2010) — *Characterizing Logging Practices in Open-Source Software* — estudo empírico sobre padrões de inserção de logs
- Anton Chuvakin — *Security Information and Event Management* — categorização de eventos críticos de segurança
- Chris Richardson — [Application Logging](https://microservices.io/patterns/observability/application-logging.html) — microservices.io
- Chris Richardson — [Distributed Tracing](https://microservices.io/patterns/observability/distributed-tracing.html) — microservices.io
- Chris Richardson — [Exception Tracking](https://microservices.io/patterns/observability/exception-tracking.html) — microservices.io
- Chris Richardson — [Audit Logging](https://microservices.io/patterns/observability/audit-logging.html) — microservices.io
- Iluwatar — [java-design-patterns: microservices-log-aggregation](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-log-aggregation)
- Iluwatar — [java-design-patterns: microservices-distributed-tracing](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-distributed-tracing)

**Observabilidade e SRE:**
- [OpenTelemetry Specification](https://opentelemetry.io/docs/specs/)
- [W3C TraceContext Recommendation](https://www.w3.org/TR/trace-context/)
- [Quarkus 3.x — OpenTelemetry Guide](https://quarkus.io/guides/opentelemetry)
- [Elasticsearch Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/)
- [Google SRE Book — Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/) — capítulo fundacional sobre alertas baseados em sintomas
- [Dapper — Large-Scale Distributed Systems Tracing](https://research.google/pubs/pub36356/) — paper seminal do Google sobre tracing distribuído
- Cindy Sridharan — [Monitoring and Observability](https://copyconstruct.medium.com/monitoring-and-observability-8417d1952e1c) — distinção entre monitoramento e observabilidade

**Livros:**
- Charity Majors, Liz Fong-Jones, George Miranda — *Observability Engineering* (O'Reilly, 2022) — observabilidade moderna e debugging por alta cardinalidade
- Betsy Beyer, Chris Jones et al. — *Site Reliability Engineering* (Google, 2016) — práticas SRE, especialmente os capítulos de monitoramento e alertas
- Cindy Sridharan — *Distributed Systems Observability* (O'Reilly, 2018) — guia conciso dos três pilares
- James Turnbull — *The Art of Monitoring* (O'Reilly, 2018) — guia prático de infraestrutura de monitoramento

**Ferramentas:**
- [Prometheus](https://prometheus.io/) — coleta de métricas e alertas
- [Grafana](https://grafana.com/) — dashboards e visualização
- [Grafana Loki](https://grafana.com/oss/loki/) — armazenamento e consulta de logs
- [Elasticsearch + Kibana (ELK)](https://www.elastic.co/) — indexação e busca de logs estruturados
- [Jaeger](https://www.jaegertracing.io/) — backend de tracing distribuído open-source
- [Grafana Tempo](https://grafana.com/oss/tempo/) — armazenamento de traces escalável e de baixo custo
- [OpenTelemetry Collector](https://opentelemetry.io/docs/collector/) — pipeline de telemetria agnóstico de vendor
- [Datadog](https://www.datadoghq.com/) — plataforma de observabilidade gerenciada