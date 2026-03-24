# Padrões de Codificação e Boas Práticas

> Diretrizes para consumo consistente da biblioteca de logging nas implementações SLF4J e Quarkus, incluindo padrões proibidos, padrões obrigatórios e checklist de Code Review.

---

## 1. Linguagem e Plataforma Base

| Requisito | Especificação |
|---|---|
| Versão Java | Java 21 (mínimo) |
| Framework base | Quarkus 3.27 (lib Quarkus e extension) / Jakarta EE (lib SLF4J) |
| Objetos de valor | `record` Java 21 para `LogEvento`, `LogContexto`, `AuditRecord` |
| Injeção de dependências | CDI nativo (`@ApplicationScoped`, `@Inject`) — sem anotações Spring |
| Interfaces da DSL | `sealed interface` Java 21 (`LogEtapas`) — impede extensão acidental |
| Concorrência reativa | SmallRye Context Propagation (Quarkus) para pipelines Mutiny/Vert.x |

O projeto apoia-se nas funcionalidades do Java 21 de forma intencional: `sealed interfaces` prendem o contrato de preenchimento da DSL exclusivamente a `LogSistematico` via `permits`; `records` eliminam boilerplate e garantem imutabilidade thread-safe sem sincronização; pattern matching com `switch` substitui cadeias de `if-instanceof` no `SanitizadorDados`.

---

## 2. Padrões Proibidos

Os cenários abaixo são destrutivos para a infraestrutura de observabilidade e devem ser barrados sistematicamente em Pull Requests.

### 2.1 Saída via fluxo de sistema padrão

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

### 2.2 Concatenação de strings e pseudo-JSON

Strings interpoladas não são indexáveis pelo Elasticsearch ou Loki. Qualquer caractere especial nos valores (aspas, chaves, barras) pode quebrar o parser do coletor. A coluna do campo não tem identidade analítica — não é possível filtrar por `notaId` sem regex.

```java
// PROIBIDO
log.info("Processo da nota " + notaId + " feito por " + userId);
log.info(String.format("{\"notaOrigem\":\"%s\"}", notaId));

// CORRETO
LogSistematico
    .registrando("Nota processada")
    .em(NotaService.class, "processar")
    .comDetalhe("notaId",  notaId)
    .comDetalhe("userId",  userId)
    .info();
```

### 2.3 Registro apenas da mensagem da exceção

`e.getMessage()` descarta a classe da exceção (necessária para fingerprinting), o stack trace (necessário para localizar o bug) e a cadeia de causas (necessária para entender a raiz do problema). Sem essas informações, `ExceptionReporter` não consegue de-duplicar por fingerprint e o diagnóstico depende de adivinhação.

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

### 2.4 Mensagens genéricas sem identificadores de entidade

Logs vagos forçam varreduras difusas em produção. Em sistemas de alto volume, `"Erro ao salvar no banco"` pode corresponder a centenas de ocorrências distintas sem nenhuma pista sobre qual entidade, qual operação ou qual contexto. Todo evento deve incluir o identificador da entidade afetada — via mensagem ou via `comDetalhe()`.

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

### 2.5 Log-and-throw sem contexto adicional

Registrar a mesma exceção em múltiplas camadas sem agregar informação nova duplica o ruído no agregador de logs sem acrescentar valor diagnóstico. Cada camada deve logar apenas o que sabe a mais sobre o erro. A regra: **logue na fronteira onde a exceção é tratada, com o contexto completo disponível naquela camada**.

```java
// PROIBIDO — a camada superior repetirá o mesmo erro sem contexto novo
catch (VendaException e) {
    LogSistematico.registrando("Erro na venda").em(...).erro(e);
    throw e;
}

// CORRETO — loga na fronteira de tratamento, com contexto completo, e não relança
catch (VendaException e) {
    LogSistematico
        .registrando("Falha tratada no processamento de venda")
        .em(VendaController.class, "processar")
        .porque(e.getMotivo())
        .comDetalhe("vendaId",    vendaId)
        .comDetalhe("errorCode",  "VND-3001")
        .erro(e);
    return Response.status(500).entity(ErrorResponse.from(e)).build();
}
```

### 2.6 `traceId` gerado manualmente

`UUID.randomUUID()` como `traceId` cria um identificador que não existe em nenhum sistema de tracing. Ele não correlaciona com nenhum span no Jaeger, não aparece em nenhum trace no Zipkin e torna as queries de `traceId` no agregador de logs completamente inúteis. O `traceId` deve sempre ser extraído do contexto OTel ativo pelo `GerenciadorContextoLog`.

```java
// PROIBIDO — identificador falso, não correlacionável
String traceId = UUID.randomUUID().toString();
MDC.put("traceId", traceId);

// CORRETO — GerenciadorContextoLog extrai do span OTel ativo
// (feito automaticamente pelo filtro HTTP e pelo @Logged)
```

### 2.7 MDC sem limpeza no `finally`

O MDC usa `ThreadLocal`. Em pools de threads (servidor de aplicação, Vert.x), a mesma thread atende múltiplas requisições sequencialmente. Se o MDC não for limpo ao final de cada requisição, o contexto da requisição anterior contamina a próxima — `userId` errado, `traceId` errado, `servico` errado.

```java
// PROIBIDO — contexto vaza para a próxima requisição na mesma thread
MDC.put("userId", userId);
processarNegocio();

// CORRETO — limpeza garantida independente de exceção
gerenciadorContextoLog.inicializar(userId, servico);
try {
    processarNegocio();
} finally {
    gerenciadorContextoLog.limpar();
}
```

### 2.8 Computação custosa sem guarda de nível

Serializar um objeto inteiro para JSON tem custo de CPU e memória. Se o nível `DEBUG` estiver desabilitado em produção — o que é padrão — esse custo é pago para produzir um log que nunca será emitido.

```java
// PROIBIDO — serializa o pedido mesmo com DEBUG desabilitado
log.debug("Estado do pedido: {}", objectMapper.writeValueAsString(pedido));

// CORRETO — custo pago apenas se o nível estiver habilitado
if (log.isDebugEnabled()) {
    log.debug("Estado do pedido: {}", objectMapper.writeValueAsString(pedido));
}
```

### 2.9 Manipulação direta do MDC fora do `GerenciadorContextoLog`

Chamadas a `MDC.put()` dispersas no código de aplicação criam campos fora do contrato canônico, dificultam o rastreamento de vazamentos de contexto e podem sobrescrever campos gerenciados pela biblioteca.

```java
// PROIBIDO — campo não canônico, fora do contrato
MDC.put("meu_campo_customizado", valor);

// CORRETO — campos de negócio via comDetalhe() da DSL
LogSistematico
    .registrando("Evento")
    .em(MinhaClasse.class, "meuMetodo")
    .comDetalhe("meuCampo", valor)
    .info();
```

---

## 3. Gestão de Níveis de Severidade

A escolha do nível deve ser determinística, baseada no impacto sobre o estado do sistema — não em julgamento subjetivo.

| Nível | Quando usar | Habilitado em produção? |
|---|---|---|
| `TRACE` | Diagnóstico de baixo nível: entradas/saídas de métodos, iterações, valores intermediários detalhados | Nunca — apenas em desenvolvimento local |
| `DEBUG` | Fluxos internos, decisões condicionais, dados intermediários sem alteração de estado | Não por padrão — ativável dinamicamente por pacote |
| `INFO` | Operações que alteram estado: persistência, autenticação, chamadas externas | Sempre |
| `WARN` | Situações anômalas recuperáveis: tentativas de acesso indevido, fallbacks ativados, validações rejeitadas | Sempre |
| `ERROR` | Falhas reais: exceção que impede o cumprimento do contrato da operação | Sempre |
| `FATAL` | Falhas que tornam a aplicação incapaz de continuar — exigem intervenção imediata (ex: corrupção de estado crítico, falha de inicialização irrecuperável) | Sempre |

**Sobre `FATAL`:** não deve ser usado para falhas de validação, exceções de negócio ou erros de integração recuperáveis. Reservado exclusivamente para condições que tornam a instância operacionalmente inviável.

**Ativação dinâmica de `DEBUG`:** em produção, o nível `DEBUG` pode ser ativado por pacote específico em tempo de execução sem reinicialização (via `quarkus-logging-manager` no Quarkus, ou reconfiguração dinâmica do Log4j2 na versão SLF4J). Isso permite investigar incidentes ativos sem elevar o volume global de logs.

---

## 4. Padrões Obrigatórios

### Imutabilidade dos objetos de valor

`LogEvento`, `LogContexto` e `AuditRecord` são `records` Java 21 — imutáveis por definição. Nenhum estado mutável deve ser adicionado a esses objetos. A imutabilidade garante thread-safety estrutural sem sincronização e elimina erros de estado compartilhado em ambientes concorrentes.

### Mascaramento automático de dados sensíveis

O `SanitizadorDados` intercepta automaticamente valores sensíveis pelo nome da chave antes de qualquer registro, aplicando dois graus de proteção:

- **Credenciais** (`password`, `token`, `cvv`, etc.) → `"****"`
- **Dados pessoais** (`cpf`, `email`, `cardnumber`, etc.) → `"[PROTEGIDO]"`

O sanitizador é a última linha de defesa — não a única. O desenvolvedor é responsável por conhecer o que está passando em `.comDetalhe()`. Campos que exigem redação completa (omissão total do campo) devem ser excluídos antes de chamar `.comDetalhe()`, pois a redação automática não está implementada nesta versão.

### Falhas de infraestrutura de observabilidade não interrompem o negócio

Se o backend de tracing, o exportador OTel ou o `MeterRegistry` estiverem indisponíveis, a falha deve ser registrada localmente e a execução deve continuar. A observabilidade nunca é justificativa para falhar uma operação de negócio.

```java
try {
    trackingBackend.report(excecaoRecord);
} catch (Exception falhaBackend) {
    // Registra localmente — nunca relança
    log.warn("Backend de rastreamento indisponível: {}", falhaBackend.getMessage());
}
```

### Códigos de erro únicos para integração com KEDB

Eventos críticos de negócio e infraestrutura devem receber códigos únicos e estáveis (ex: `VND-3001`, `PAG-4022`). Esses códigos são a chave de ligação entre o log em produção e a Base de Conhecimento de Erros Conhecidos (KEDB) — repositório interno que documenta causa raiz, impacto e procedimento de remediação. Quando um operador vê `PAG-4022` em um alerta às 3 da manhã, consulta a KEDB e executa o procedimento documentado sem precisar interpretar a mensagem do zero.

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

## 5. Eventos de Negócio

Eventos relevantes para o negócio devem ser registrados com tipo identificável (`eventType`) para serem distinguíveis de eventos técnicos nas ferramentas de observabilidade. Isso habilita dashboards de analytics em tempo real e alertas baseados em volume de eventos específicos.

```java
// Evento de negócio com tipo identificável
LogSistematico
    .registrando("Pedido concluído")
    .em(PedidoService.class, "concluir")
    .porque("Pagamento confirmado pelo gateway")
    .como("API REST — POST /pedidos/{id}/concluir")
    .comDetalhe("eventType",  "ORDER_COMPLETED")
    .comDetalhe("pedidoId",   pedido.getId())
    .comDetalhe("valorTotal", pedido.getValorTotal())
    .comDetalhe("currency",   "BRL")
    .info();
```

O campo `eventType` como detalhe (prefixado `detalhe_eventType` no JSON) é a forma atual de distinguir eventos de negócio. Uma API dedicada `businessEvent()` está planejada para versão futura e manterá o mesmo contrato de campos.

---

## 6. Checklist de Code Review

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
- [ ] Nenhum dado sensível (senhas, tokens, PAN, CPF) sem mascaramento via `SanitizadorDados`
- [ ] Campos que exigem redação total omitidos antes de `.comDetalhe()`
- [ ] Eventos críticos incluem `errorCode` para correlação com KEDB
- [ ] Falhas de backend de observabilidade tratadas localmente — não relançadas como exceção de negócio
- [ ] `beans.xml` declara `LoggingInterceptor` (somente para lib SLF4J — Quarkus não precisa)

---

## Fora do Escopo

### API fluent direta do SLF4J 2.x como substituto da DSL

O método `logger.atInfo().addKeyValue("chave", valor).log("mensagem")` do SLF4J 2.x produz JSON estruturado mas **não** valida a sequência 5W1H em tempo de compilação, não aplica mascaramento automático e não integra com o `GerenciadorContextoLog`. Para uso avulso de logging não crítico, é aceitável; para código de produção que deve obedecer ao padrão, `LogSistematico` é obrigatório.

### `ExceptionReporter` e backends de rastreamento de exceções

A abstração `ExceptionReporter` — CDI bean com integração a Sentry, Rollbar ou webhook customizado — é um entregável planejado para a versão 0.3 da biblioteca. Não está disponível ainda. Até então, exceções devem ser registradas via `LogSistematico.erro(e)` com contexto completo, que é o pré-requisito para que o `ExceptionReporter` funcione eficazmente quando for implementado.

### `AuditRecord` e `@Auditable`

O `AuditRecord` e o interceptor `@Auditable` são abstrações documentadas no padrão conceitual e planejadas para a versão 0.3. Não estão disponíveis ainda. Até então, eventos de auditoria devem ser registrados via `LogSistematico` com os campos obrigatórios do padrão de auditoria (`actor_id`, `action`, `entity_type`, `entity_id`, `state_before`, `state_after`, `outcome`) como detalhes explícitos.