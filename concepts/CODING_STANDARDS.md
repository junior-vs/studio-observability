# Padrões de Codificação — Logging Sistemático

> Este documento estabelece as regras operacionais de uso da biblioteca de logging.
> Para os fundamentos conceituais (5W1H, DSL, padrões arquiteturais, auditoria, rastreamento de exceções),
> consulte o documento principal: [Padrão de Logging em Aplicações Java](logging_revisado.md).
>
> Para os nomes canônicos dos campos JSON, consulte: [Registro de Nomes de Campos](FIELD_NAMES.md).

---

## Parte I — Conceitos de Logging Estruturado

### Por que JSON é mandatório

Texto puro exige expressões regulares complexas e frágeis para extrair informação. Uma alteração no formato da mensagem — um espaço a mais, um campo reordenado — quebra silenciosamente o parser e corrompé dashboards e alertas sem nenhum erro visível.

JSON resolve esse problema de forma direta: cada dimensão do evento é um campo nomeado, tipado e indexável. O Elasticsearch, o Loki e o Datadog ingeram JSON nativamente — sem configuração de parser, sem regex, sem manutenção de padrões de texto.

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

---

### O Log como Fluxo Append-Only

O log é uma sequência ordenada de registros imutáveis. Cada evento é acrescentado ao final — nunca modificado ou removido retroativamente. Essa característica tem três implicações diretas sobre como o sistema deve ser projetado:

**Fonte da verdade.** Um banco de dados registra o estado *atual* de uma entidade. O log registra cada *mudança de estado* ao longo do tempo. Um log completo permite reconstruir o estado de qualquer entidade em qualquer ponto do passado sem consultar o banco.

**Imutabilidade como contrato.** Alterar ou deletar um registro de log — mesmo para "corrigir" uma mensagem — viola o contrato append-only e pode comprometer investigações de segurança, disputas técnicas e conformidade regulatória (LGPD, SOC 2).

**Agregação centralizada obrigatória.** Em microsserviços, cada instância gera seu próprio fluxo. Uma única operação distribuída produz eventos em N serviços diferentes. Sem agregação centralizada (ELK, Loki, Datadog), os logs de uma operação ficam espalhados em dezenas de containers — o diagnóstico se torna impraticável independente da qualidade dos logs individuais.

---

### A DSL como Mecanismo de Enforcement do 5W1H

A DSL (`LogSistematico`) não é apenas uma API conveniente — é um **mecanismo de enforcement**. O compilador Java impede que um log seja emitido sem as dimensões obrigatórias *What* (`.registrando()`) e *Where* (`.em()`). Logs incompletos são erros de compilação, não bugs silenciosos em produção.

```
LogSistematico
    .registrando(evento)           // What  — obrigatório: o que aconteceu
    .em(classe, metodo)            // Where — obrigatório: onde no código
    [ .porque(motivo)         ]    // Why   — opcional: causa de negócio
    [ .como(canal)            ]    // How   — opcional: canal de entrada
    [ .comDetalhe(chave, val) ]*   // extra — zero ou mais campos de negócio
    .info() | .debug() | .warn() | .erro(ex) | .erroERelanca(ex)
```

As dimensões *Who* (`userId`, `applicationName`) e *When* (`timestamp`) são injetadas automaticamente pela infraestrutura via MDC — o desenvolvedor não as declara.

---

### Gerenciamento de Contexto via MDC

O MDC (*Mapped Diagnostic Context*) é um mapa thread-local que acrescenta pares chave-valor automaticamente a todo evento de log emitido naquela thread. É o mecanismo que permite que `userId`, `traceId` e `applicationName` apareçam em todos os logs de uma requisição sem que o desenvolvedor os passe explicitamente em cada chamada.

**Regra de propriedade:** o `GerenciadorContextoLog` é o único ponto de escrita do MDC na biblioteca. Chamadas diretas a `MDC.put()` fora dele são não conformidade — criam campos fora do contrato canônico e dificultam o rastreamento de vazamentos de contexto.

**Integração OpenTelemetry:** o `traceId` e o `spanId` são extraídos do span OTel ativo via `Span.current().getSpanContext()`. Na versão Quarkus, o `quarkus-opentelemetry` auto-instrumenta chamadas HTTP e propaga o cabeçalho `traceparent` (W3C TraceContext) automaticamente. Na versão SLF4J, a instrumentação OTel é responsabilidade do agente Java OTel ou da instrumentação manual.

**Contexto reativo (Quarkus):** em pipelines Mutiny e RESTEasy Reactive, a execução pode trocar de thread entre operações assíncronas. O `ThreadLocal` do MDC é silenciosamente perdido nessa troca. O `quarkus-smallrye-context-propagation` (habilitado via `quarkus.arc.context-propagation.mdc=true`) propaga o MDC e o span OTel automaticamente entre as trocas de thread do Vert.x — sem código adicional no desenvolvedor.

**Limpeza garantida:** o MDC deve ser limpo ao final de cada requisição ou invocação. Sem limpeza, o contexto da requisição anterior vaza para a próxima na mesma thread — `userId` errado, `traceId` errado, `applicationName` errado em todos os logs subsequentes. A limpeza ocorre em dois pontos obrigatórios: no bloco `finally` do `LoggingInterceptor` / `LogInterceptor` (campos de localização) e na fase de resposta do filtro HTTP (campos de correlação da requisição).

---

### Eventos de Negócio

Eventos relevantes para o negócio devem ser distinguíveis de eventos técnicos nas ferramentas de observabilidade. O campo `eventType` como detalhe torna o evento identificável como categoria distinta em queries — sem depender de parsear o campo `message`.

```java
LogSistematico
    .registrando("Pedido concluído")
    .em(PedidoService.class, "concluir")
    .porque("Pagamento confirmado pelo gateway")
    .como("API REST — POST /pedidos/{id}/concluir")
    .comDetalhe("eventType", "ORDER_COMPLETED")
    .comDetalhe("pedidoId",   pedido.getId())
    .comDetalhe("valorTotal", pedido.getValorTotal())
    .comDetalhe("currency",   "BRL")
    .info();
```

O campo `detalhe_eventType: "ORDER_COMPLETED"` no JSON habilita dashboards de analytics em tempo real e alertas baseados em volume de evento específico, sem pipeline de analytics separado. Uma API dedicada `businessEvent()` está planejada para versão futura e manterá o mesmo contrato de campos.

---

### Códigos de Erro e KEDB

Eventos críticos de negócio e infraestrutura devem receber códigos únicos e estáveis (ex: `VND-3001`, `PAG-4022`). Esses códigos são a chave de ligação entre o log em produção e a **Base de Conhecimento de Erros Conhecidos (KEDB)** — repositório interno que documenta causa raiz, impacto e procedimento de remediação.

Quando um operador vê `PAG-4022` em um alerta às 3 da manhã, consulta a KEDB e executa o procedimento documentado sem precisar interpretar a mensagem do zero. O código é estável entre versões; a mensagem pode variar.

```java
LogSistematico
    .registrando("Falha ao processar pagamento")
    .em(PagamentoService.class, "processar")
    .porque("Gateway recusou a transação")
    .comDetalhe("errorCode",         "PAG-4022")
    .comDetalhe("pedidoId",          pedidoId)
    .comDetalhe("codigoErroGateway", e.getCodigo())
    .erro(e);
```

---

### Ciclo de Melhoria Contínua

O logging é um componente vivo da arquitetura. Após cada incidente em produção:

1. Revisar os logs gerados durante o incidente.
2. Identificar quais informações estavam ausentes e atrasaram o diagnóstico.
3. Atualizar a biblioteca ou o padrão para que a lacuna seja preenchida automaticamente no futuro.
4. Incorporar a melhoria como novo padrão organizacional e atualizar o checklist de Code Review.

Um incidente em que `userId` estava ausente leva a adicionar `@Logged` na camada de serviço. Um incidente em que `motivo` estava genérico leva a refinar o `.porque()` naquele fluxo. Cada incidente é uma oportunidade de tornar o próximo diagnóstico mais rápido.

---

## Parte II — Referência de Implementação

### Linguagem e Plataforma Base

| Requisito | Especificação |
|---|---|
| Versão Java | Java 21 (mínimo) |
| Framework base | Quarkus 3.27 (lib Quarkus e extension) / Jakarta EE (lib SLF4J) |
| Objetos de valor | `record` Java 21 para `LogEvento`, `LogContexto`, `AuditRecord` |
| Injeção de dependências | CDI nativo (`@ApplicationScoped`, `@Inject`) — sem anotações Spring |
| Interfaces da DSL | `sealed interface` Java 21 (`LogEtapas`) — impede extensão acidental |
| Concorrência reativa | SmallRye Context Propagation (Quarkus) para pipelines Mutiny/Vert.x |

O projeto usa funcionalidades do Java 21 de forma intencional: `sealed interfaces` prendem o contrato da DSL exclusivamente a `LogSistematico` via `permits`; `records` eliminam boilerplate e garantem imutabilidade thread-safe sem sincronização; pattern matching com `switch` substitui cadeias de `if-instanceof` no `SanitizadorDados`.

---

### Configuração de Saída JSON

**Quarkus 3.27:**

```properties
# application.properties
quarkus.log.console.json=true
```

Todos os campos do MDC aparecem automaticamente como chaves de primeiro nível no JSON. Nenhuma configuração adicional é necessária para os campos da biblioteca.

**SLF4J + Log4j2 (Jakarta EE):**

```xml
<!-- src/main/resources/log4j2.xml -->
<Configuration status="WARN">
    <Appenders>
        <Console name="JsonConsole" target="SYSTEM_OUT">
            <JsonTemplateLayout
                eventTemplateUri="classpath:LogstashJsonEventLayoutV1.json"/>
        </Console>
    </Appenders>
    <Loggers>
        <Root level="INFO">
            <AppenderRef ref="JsonConsole"/>
        </Root>
    </Loggers>
</Configuration>
```

O `JsonTemplateLayout` serializa todos os campos do MDC como chaves de primeiro nível — o mesmo resultado da configuração Quarkus.

---

### Padrões Proibidos

Os cenários abaixo são destrutivos para a infraestrutura de observabilidade e devem ser barrados sistematicamente em Pull Requests.

#### P1. Saída via fluxo de sistema padrão

`System.out`, `System.err` e `e.printStackTrace()` ignoram o MDC, os níveis de severidade e o formatador JSON. O output vai para stdout sem estrutura, sem campos de correlação e sem possibilidade de indexação.

```java
// PROIBIDO
System.out.println("Processando nota id " + notaId);
System.err.println("Falhou: " + e.getMessage());
e.printStackTrace();

// CORRETO
LogSistematico
    .registrando("Nota processada")
    .em(NotaService.class, "processar")
    .comDetalhe("notaId", notaId)
    .info();
```

#### P2. Concatenação de strings e pseudo-JSON

Strings interpoladas não são indexáveis. Qualquer caractere especial nos valores pode quebrar o parser do coletor. A coluna do campo não tem identidade analítica — não é possível filtrar por `notaId` sem regex.

```java
// PROIBIDO
log.info("Processo da nota " + notaId + " feito por " + userId);
log.info(String.format("{\"notaOrigem\":\"%s\"}", notaId));

// CORRETO
LogSistematico
    .registrando("Nota processada")
    .em(NotaService.class, "processar")
    .comDetalhe("notaId", notaId)
    .comDetalhe("userId", userId)
    .info();
```

#### P3. Registro apenas da mensagem da exceção

`e.getMessage()` descarta a classe da exceção (necessária para fingerprinting futuro), o stack trace (necessário para localizar o bug) e a cadeia de causas (necessária para entender a raiz). O objeto completo deve sempre ser passado ao terminador `.erro(e)`.

```java
// PROIBIDO — descarta classe, stack trace e cadeia de causas
log.error(e.getMessage());
log.error("Erro: " + e.getMessage());

// CORRETO — objeto de exceção completo como último argumento
LogSistematico
    .registrando("Falha ao processar venda")
    .em(VendaService.class, "processar")
    .porque("Erro inesperado no gateway")
    .comDetalhe("vendaId", vendaId)
    .erro(e);
```

#### P4. Mensagens genéricas sem identificadores de entidade

Logs vagos forçam varreduras difusas em produção. Em sistemas de alto volume, `"Erro ao salvar no banco"` pode corresponder a centenas de ocorrências distintas sem nenhuma pista sobre qual entidade, operação ou contexto. Todo evento deve incluir o identificador da entidade afetada.

```java
// PROIBIDO — sem valor diagnóstico em produção
log.error("Erro ao salvar no banco");
log.warn("Validação falhou");
log.info("Iniciado");

// CORRETO
LogSistematico
    .registrando("Falha ao salvar venda")
    .em(VendaService.class, "salvar")
    .porque("Chave duplicada no banco")
    .comDetalhe("vendaId", vendaId)
    .erro(e);
```

#### P5. Log-and-throw sem contexto adicional

Registrar a mesma exceção em múltiplas camadas sem agregar informação nova duplica o ruído no agregador. Cada camada loga apenas o que sabe a mais sobre o erro. **Regra: logue na fronteira onde a exceção é tratada, com o contexto completo disponível naquela camada.**

```java
// PROIBIDO — a camada superior repetirá o mesmo erro sem contexto novo
catch (VendaException e) {
    LogSistematico.registrando("Erro na venda").em(...).erro(e);
    throw e;
}

// CORRETO — loga na fronteira de tratamento, com contexto completo
catch (VendaException e) {
    LogSistematico
        .registrando("Falha tratada no processamento de venda")
        .em(VendaController.class, "processar")
        .porque(e.getMotivo())
        .comDetalhe("vendaId",   vendaId)
        .comDetalhe("errorCode", "VND-3001")
        .erro(e);
    return Response.status(500).entity(ErrorResponse.from(e)).build();
}
```

#### P6. `traceId` gerado manualmente

`UUID.randomUUID()` como `traceId` cria um identificador que não existe em nenhum sistema de tracing. Não correlaciona com nenhum span no Jaeger, não aparece em nenhum trace no Zipkin e torna queries de `traceId` no agregador completamente inúteis. O `traceId` deve sempre ser extraído do contexto OTel ativo pelo `GerenciadorContextoLog`.

```java
// PROIBIDO — identificador falso, não correlacionável
String traceId = UUID.randomUUID().toString();
MDC.put("traceId", traceId);

// CORRETO — GerenciadorContextoLog extrai do span OTel ativo
// (feito automaticamente pelo filtro HTTP e pelo @Logged)
```

#### P7. MDC sem limpeza no `finally`

O MDC usa `ThreadLocal`. Em pools de threads, a mesma thread atende múltiplas requisições sequencialmente. Sem limpeza, o contexto da requisição anterior contamina a próxima.

```java
// PROIBIDO — contexto vaza para a próxima requisição na mesma thread
MDC.put("userId", userId);
processarNegocio();

// CORRETO — limpeza garantida independente de exceção
gerenciadorContextoLog.inicializar(userId, applicationName);
try {
    processarNegocio();
} finally {
    gerenciadorContextoLog.limpar();
}
```

#### P8. Computação custosa sem guarda de nível

Serializar um objeto para JSON tem custo de CPU e memória. Se `DEBUG` estiver desabilitado em produção — o que é padrão — esse custo é pago para produzir um log que nunca será emitido.

```java
// PROIBIDO — serializa o pedido mesmo com DEBUG desabilitado
log.debug("Estado do pedido: {}", objectMapper.writeValueAsString(pedido));

// CORRETO — custo pago apenas se o nível estiver habilitado
if (log.isDebugEnabled()) {
    log.debug("Estado do pedido: {}", objectMapper.writeValueAsString(pedido));
}
```

#### P9. Manipulação direta do MDC fora do `GerenciadorContextoLog`

Chamadas a `MDC.put()` dispersas no código de aplicação criam campos fora do contrato canônico, dificultam o rastreamento de vazamentos e podem sobrescrever campos gerenciados pela biblioteca.

```java
// PROIBIDO — campo fora do contrato canônico
MDC.put("meu_campo_customizado", valor);

// CORRETO — campos de negócio via comDetalhe() da DSL
LogSistematico
    .registrando("Evento")
    .em(MinhaClasse.class, "meuMetodo")
    .comDetalhe("meuCampo", valor)
    .info();
```

---

### Padrões Obrigatórios

**Mascaramento automático de dados sensíveis:** o `SanitizadorDados` intercepta valores sensíveis pelo nome da chave antes de qualquer registro, aplicando dois graus de proteção:

| Categoria | Chaves interceptadas | Valor no JSON |
|---|---|---|
| Credenciais | `password`, `senha`, `token`, `accesstoken`, `refreshtoken`, `authorization`, `apikey`, `cvv`, `secret` | `"****"` |
| Dados pessoais | `cpf`, `rg`, `email`, `celular`, `cardnumber`, `numerocartao` | `"[PROTEGIDO]"` |
| Demais | qualquer outra chave | valor original |

O sanitizador é a última linha de defesa — não a única. Campos que exigem **redação completa** (omissão total do campo) devem ser excluídos antes de chamar `.comDetalhe()`. A redação automática não está implementada nesta versão.

**Falhas de infraestrutura de observabilidade não interrompem o negócio:** se o backend de tracing, o exportador OTel ou o `MeterRegistry` estiverem indisponíveis, a falha deve ser registrada localmente e a execução deve continuar. A observabilidade nunca é justificativa para falhar uma operação de negócio.

```java
try {
    trackingBackend.report(excecaoRecord);
} catch (Exception falhaBackend) {
    log.warn("Backend de rastreamento indisponível: {}", falhaBackend.getMessage());
    // Nunca relançar
}
```

**Proteção em trânsito (SSL/TLS):** logs transmitidos em texto claro entre o container e o coletor podem expor campos de contexto não mascarados. O transporte deve usar **SSL/TLS** em todos os segmentos do pipeline.

---

### Gestão de Níveis de Severidade

| Nível | Quando usar | Habilitado em produção? |
|---|---|---|
| `TRACE` | Diagnóstico de baixo nível: entradas/saídas de métodos, iterações, valores intermediários detalhados | Nunca — apenas em desenvolvimento local |
| `DEBUG` | Fluxos internos, decisões condicionais, dados intermediários sem alteração de estado | Não por padrão — ativável dinamicamente por pacote |
| `INFO` | Operações que alteram estado: persistência, autenticação, chamadas externas | Sempre |
| `WARN` | Situações anômalas recuperáveis: tentativas de acesso indevido, fallbacks ativados, validações rejeitadas | Sempre |
| `ERROR` | Falhas reais: exceção que impede o cumprimento do contrato da operação | Sempre |
| `FATAL` | Falhas que tornam a aplicação incapaz de continuar — exigem intervenção imediata | Sempre |

**Sobre `FATAL`:** não deve ser usado para falhas de validação, exceções de negócio ou erros de integração recuperáveis. Reservado exclusivamente para condições que tornam a instância operacionalmente inviável.

**Regra anti-duplicação:** é proibido registrar a mesma exceção em múltiplas camadas sem agregar contexto adicional. Cada camada loga apenas o que sabe a mais.

**Ativação dinâmica de `DEBUG`:** em produção, ativável por pacote sem reinicialização — via `quarkus-logging-manager` (Quarkus) ou reconfiguração dinâmica do Log4j2 (SLF4J).

---

### Checklist de Code Review

Antes de aprovar qualquer Pull Request que toque em código de logging ou observabilidade:

- [ ] Nenhum `System.out.println`, `System.err.println` ou `e.printStackTrace()`
- [ ] Nenhuma concatenação de string ou `String.format` em mensagens de log
- [ ] Nenhum `log.error(e.getMessage())` — objeto de exceção completo passado ao terminador `.erro(e)`
- [ ] Nenhuma mensagem genérica — identificadores de entidade presentes na mensagem ou em `comDetalhe()`
- [ ] Nenhum log-and-throw sem contexto adicional — loga na fronteira de tratamento
- [ ] Nenhum `UUID.randomUUID()` como `traceId` — contexto OTel usado via `GerenciadorContextoLog`
- [ ] MDC limpo no bloco `finally` — via `GerenciadorContextoLog.limpar()`
- [ ] Nenhum `MDC.put()` direto fora do `GerenciadorContextoLog`
- [ ] Computações custosas protegidas por guarda de nível (`isDebugEnabled()`)
- [ ] Nomes de campos canônicos do [Registro de Nomes de Campos](FIELD_NAMES.md) usados
- [ ] Nenhum dado sensível sem mascaramento via `SanitizadorDados`
- [ ] Campos que exigem redação total omitidos antes de `.comDetalhe()`
- [ ] Eventos críticos incluem `errorCode` para correlação com KEDB
- [ ] Falhas de backend de observabilidade tratadas localmente — não relançadas como exceção de negócio
- [ ] `beans.xml` declara `LoggingInterceptor` — **somente na lib SLF4J** (Quarkus/ArC descobre automaticamente via `@Interceptor`)

---

## Parte III — Fora do Escopo

Esta seção documenta explicitamente o que não faz parte da biblioteca — decisões que podem parecer alternativas válidas mas que violam o padrão ou introduzem problemas arquiteturais.

### API fluent direta do SLF4J 2.x como substituto da DSL

O método `logger.atInfo().addKeyValue("chave", valor).log("mensagem")` produz JSON estruturado tecnicamente, mas **não** valida a sequência 5W1H em tempo de compilação, não aplica mascaramento automático via `SanitizadorDados` e não integra com o `GerenciadorContextoLog`. Para logs internos da própria biblioteca ou utilitários sem contexto de domínio, é aceitável. Para código de aplicação que deve obedecer ao padrão, `LogSistematico` é obrigatório.

### `ExceptionReporter` e backends de rastreamento de exceções

A abstração `ExceptionReporter` — CDI bean com integração a Sentry, Rollbar ou webhook customizado — é um entregável planejado para a versão 0.3 da biblioteca. Não está disponível ainda. Até então, exceções devem ser registradas via `LogSistematico.erro(e)` com contexto completo — que é o pré-requisito para que o `ExceptionReporter` funcione eficazmente quando implementado.

### `AuditRecord` e `@Auditable`

O `AuditRecord` e o interceptor `@Auditable` são abstrações documentadas conceitualmente em [logging_revisado.md](logging_revisado.md) e planejados para a versão 0.3. Não estão disponíveis ainda. Até então, eventos de auditoria devem ser registrados via `LogSistematico` com os campos obrigatórios do padrão (`actor_id`, `action`, `entity_type`, `entity_id`, `state_before`, `state_after`, `outcome`) declarados explicitamente via `.comDetalhe()`.

### Output GELF / Graylog nativo na aplicação

Configurar `quarkus.log.handler.gelf.enabled=true` transmite logs diretamente da aplicação para o Graylog via UDP — acoplando a aplicação à infraestrutura de coleta. A arquitetura correta emite JSON para `stdout`; o escoamento é responsabilidade do coletor externo (OTel Collector, FluentBit). Além disso, o GELF usa um formato JSON aninhado diferente do formato flat produzido por `quarkus-logging-json`, criando inconsistências nos campos indexados.

### Log rotation e escrita em arquivo

Lógicas de rotação de arquivo e controle de escrita em disco contradizem o modelo container-native (Kubernetes, Docker). Containers descartáveis não devem gerenciar arquivos — devem emitir para `stdout`. A governança de armazenamento e retenção é responsabilidade da plataforma de orquestração e do coletor.

### `@AroundInvoke` manual pelo desenvolvedor de aplicação

O `LoggingInterceptor` / `LogInterceptor` da biblioteca já realiza via `@Logged` a inserção de campos de localização no MDC. Criar interceptores CDI com `@AroundInvoke` paralelos no código de aplicação duplica responsabilidade, cria risco de vazamento de contexto em ambientes reativos e produz campos fora do contrato canônico.

---

## Ver Também

- [Padrão de Logging em Aplicações Java](logging_revisado.md) — fundamentos conceituais, 5W1H, padrões arquiteturais
- [Implementação SLF4J + Log4j2](implementacao_slf4j.md) — código-fonte da biblioteca portável
- [Implementação Quarkus 3.27](biblioteca_quarkus.md) — código-fonte da biblioteca nativa Quarkus
- [Registro de Nomes de Campos](FIELD_NAMES.md) — nomes canônicos dos campos JSON