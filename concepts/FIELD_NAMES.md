# Registro de Nomes de Campos

> Este documento formaliza as chaves canônicas de saída JSON para todas as implementações da biblioteca de Logging Sistemático. Os nomes de campos são reservados e devem ser usados consistentemente em todos os serviços e nos três módulos da biblioteca. Usar sinônimos (ex: `usuarioId` em vez de `userId`, ou `service` em vez de `servico`) viola o princípio de consistência e produz resultados divididos em ferramentas de analytics.

---

## Convenção de Nomenclatura

O projeto adota as seguintes convenções, alinhadas ao ecossistema nativo do Quarkus e do JBoss Logging:

- **camelCase** para identificadores de correlação, auditoria e contexto (`userId`, `traceId`, `spanId`, `actorId`, `entityType`) — consistente com a nomenclatura nativa do OpenTelemetry SDK e JBoss Logging.
- **Prefixo `log_`** para campos declarados explicitamente na DSL (`.em()`, `.porque()`, `.como()`).
- **Prefixo `detalhe_`** para campos de negócio declarados via `.comDetalhe()` — evita colisão com campos de infraestrutura no índice do Elasticsearch.
- **camelCase** para campos de auditoria (`actorId`, `entityType`, `stateBefore`) — mantendo consistência com o contrato principal da biblioteca.

A transformação para convenções de plataformas externas (ECS do Elasticsearch, `dd.trace_id` do Datadog) é responsabilidade do coletor de infraestrutura (OTel Collector, Logstash, FluentBit) — não da aplicação.

---

## 1. Identidade e Correlação

Campos inseridos automaticamente pelo `GerenciadorContextoLog` via MDC. O desenvolvedor não os declara manualmente — estão presentes em todo evento de log da requisição.

| Campo | Tipo | Descrição | Fonte |
|---|---|---|---|
| `userId` | `string` | Identificador do usuário autenticado. `"anonimo"` quando não autenticado. | Automático — `GerenciadorContextoLog` via SecurityContext |
| `traceId` | `string` | Identificador do trace distribuído W3C. Presente apenas quando há span OTel ativo. | Automático — OpenTelemetry SDK |
| `spanId` | `string` | Identificador do span atual dentro do trace. Presente apenas quando há span OTel ativo. | Automático — OpenTelemetry SDK |
| `requestId` | `string` | Identificador único da requisição HTTP neste serviço. Escopo: um único serviço. | Automático — Filtro JAX-RS *(implementação futura — v0.2)* |
| `servico` | `string` | Nome do microsserviço ou aplicação. Lido de `quarkus.application.name` ou equivalente. | Automático — `GerenciadorContextoLog` |

**`traceId` vs `requestId`:** os dois identificadores são complementares e respondem a perguntas diferentes. O `traceId` atravessa todos os serviços de uma operação distribuída — é o identificador global. O `requestId` isola o ciclo de vida de uma requisição dentro de um único serviço — é o identificador local. Ambos devem estar presentes em todo evento de log quando disponíveis.

---

## 2. Localização Técnica

Campos inseridos automaticamente pelo `LoggingInterceptor` / `LogInterceptor` via MDC quando o bean está anotado com `@Logged`.

| Campo | Tipo | Descrição | Exemplo |
|---|---|---|---|
| `classe` | `string` | Nome simples da classe interceptada pelo `@Logged`. | `"PedidoService"` |
| `metodo` | `string` | Nome do método interceptado pelo `@Logged`. | `"criar"` |

---

## 3. Campos da DSL (Evento Estruturado)

Campos declarados explicitamente pelo desenvolvedor na cadeia da DSL. O prefixo `log_` os distingue dos campos de infraestrutura e dos campos de negócio.

| Campo | Tipo | Declarado via | Descrição | Exemplo |
|---|---|---|---|---|
| `timestamp` | `string` | Automático (formatador) | Timestamp UTC com precisão de milissegundos. Formato ISO 8601. | `"2026-03-11T21:55:00.123Z"` |
| `level` | `string` | Automático (formatador) | Nível de severidade do evento. | `"INFO"`, `"ERROR"` |
| `message` | `string` | `.registrando("texto")` | Descrição do evento — dimensão *What*. | `"Pedido criado"` |
| `log_classe` | `string` | `.em(Classe.class, ...)` | Nome da classe onde o evento ocorreu — dimensão *Where* técnica. | `"PedidoService"` |
| `log_metodo` | `string` | `.em(..., "metodo")` | Nome do método onde o evento ocorreu — dimensão *Where* técnica. | `"criar"` |
| `log_motivo` | `string` | `.porque("motivo")` | Causa ou motivação de negócio — dimensão *Why*. | `"Solicitação do cliente via checkout"` |
| `log_canal` | `string` | `.como("canal")` | Canal ou mecanismo pelo qual o evento chegou — dimensão *How*. | `"API REST — POST /pedidos"` |

**Nota sobre `log_classe` / `log_metodo` vs `classe` / `metodo`:** os campos sem prefixo (`classe`, `metodo`) são injetados pelo interceptor `@Logged` e refletem a localização do método interceptado. Os campos com prefixo `log_` são declarados pelo desenvolvedor via `.em()` e refletem a localização semântica do evento — que pode ser diferente da localização do interceptor quando o log é emitido em um método auxiliar privado chamado pelo método interceptado.

---

## 4. Campos de Negócio

Campos declarados via `.comDetalhe(chave, valor)`. O prefixo `detalhe_` é aplicado automaticamente pela DSL — o desenvolvedor declara apenas o nome do campo sem o prefixo.

| Declaração no código | Campo no JSON | Notas |
|---|---|---|
| `.comDetalhe("pedidoId", 4821)` | `"detalhe_pedidoId": "4821"` | Valores numéricos são convertidos para string |
| `.comDetalhe("valorTotal", 349.90)` | `"detalhe_valorTotal": 349.90` | Valores `float`/`double` preservam o tipo |
| `.comDetalhe("errorCode", "PAG-4022")` | `"detalhe_errorCode": "PAG-4022"` | Código de erro para correlação com KEDB |
| `.comDetalhe("eventType", "ORDER_COMPLETED")` | `"detalhe_eventType": "ORDER_COMPLETED"` | Identificador de evento de negócio |
| `.comDetalhe("token", tokenValue)` | `"detalhe_token": "****"` | Mascarado automaticamente pelo `SanitizadorDados` |
| `.comDetalhe("email", emailValue)` | `"detalhe_email": "[PROTEGIDO]"` | Mascarado automaticamente pelo `SanitizadorDados` |

O prefixo `detalhe_` serve dois propósitos: evitar colisão com campos reservados de infraestrutura no índice do Elasticsearch/Loki, e distinguir visualmente campos de negócio de campos de contexto técnico nas ferramentas de analytics.

---

## 5. Campos de Auditoria

Campos obrigatórios em registros de `AuditRecord`, gravados via `AuditWriter`. Esses campos constituem o contrato do padrão de Audit Logging e devem estar presentes em todo registro de auditoria.

> ⚠️ **Implementação futura:** o interceptor `@Auditable` e o `AuditWriter` estão planejados para v0.3. Os campos abaixo definem o contrato que essa implementação deverá satisfazer.

| Campo | Tipo | Descrição |
|---|---|---|
| `actorId` | `string` | Identificador de quem executou a ação (`userId` ou identidade de sistema). |
| `actorIp` | `string` | Endereço IP de origem da requisição. |
| `sessionId` | `string` | Identificador de sessão — vincula ao evento de autenticação correspondente. |
| `action` | `string` | Tipo de operação: `CREATE`, `UPDATE`, `DELETE`, `READ` (dados sensíveis), `LOGIN`, `LOGOUT`. |
| `entityType` | `string` | Tipo da entidade afetada. Ex: `"UserProfile"`, `"Order"`, `"PaymentMethod"`. |
| `entityId` | `string` | Identificador da entidade afetada. |
| `stateBefore` | `object` | Snapshot do estado relevante da entidade **antes** da ação. |
| `stateAfter` | `object` | Snapshot do estado relevante da entidade **depois** da ação. |
| `outcome` | `string` | Resultado da operação: `"SUCCESS"` ou `"FAILURE"` com motivo em caso de falha. |
| `traceId` | `string` | Correlação com o trace distribuído da requisição. |

---

## 6. JSON Completo de Referência

Exemplo de saída JSON para um evento `INFO` completo com todos os campos preenchidos:

```json
{
  "timestamp":              "2026-03-11T21:55:00.123Z",
  "level":                  "INFO",
  "message":                "Pedido criado",
  "traceId":                "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":                 "a3ce929d0e0e4736",
  "userId":                 "joao.silva@empresa.com",
  "servico":                "pedidos-service",
  "classe":                 "PedidoService",
  "metodo":                 "criar",
  "log_classe":             "PedidoService",
  "log_metodo":             "criar",
  "log_motivo":             "Solicitação do cliente via checkout",
  "log_canal":              "API REST — POST /pedidos",
  "detalhe_pedidoId":       "4821",
  "detalhe_valorTotal":     349.90,
  "detalhe_eventType":      "ORDER_COMPLETED",
  "detalhe_errorCode":      null
}
```

---

## Fora do Escopo

### Padronização forçada em snake_case universal

A orientação de usar exclusivamente snake_case para todos os campos não se aplica a esta biblioteca. O projeto adota camelCase para identificadores OTel, auditoria e contexto (`userId`, `traceId`, `actorId`) por consistência com o ecossistema nativo JBoss Logging e OpenTelemetry SDK Java. A escolha é deliberada e documentada — não uma inconsistência.

### Adaptação dinâmica de campos para plataformas externas

Remapear `traceId` para `dd.trace_id` (Datadog) ou para a Elasticsearch Common Schema (ECS) no código da aplicação acopla indevidamente a aplicação às ferramentas de coleta. Essas transformações pertencem ao coletor de infraestrutura (OTel Collector, Logstash, FluentBit) — não ao SDK. A aplicação produz JSON canônico puro; o coletor transforma para o destino.

### `@timestamp` e `severity` como nomes canônicos

Documentos anteriores ditavam `@timestamp` (convenção do Elasticsearch) e `severity` (convenção do GCP Logging) como nomes obrigatórios. A implementação atual usa `timestamp` e `level`, gerados nativamente pelo `quarkus-logging-json` e pelo `JsonTemplateLayout` do Log4j2. A adaptação para `@timestamp` ou `severity` é feita pelo coletor, se necessário.

### Objetos de exceção aninhados obrigatórios

A premissa anterior exigia construção manual de subnós JSON para exceções (`exception.class`, `exception.stack_trace`). A serialização do stack trace e da cadeia de causas é delegada ao formatador nativo (`quarkus-logging-json` ou `JsonTemplateLayout`) — não é responsabilidade da aplicação construir essa estrutura manualmente.