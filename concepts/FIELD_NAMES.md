# Registro de Nomes de Campos

> Este documento formaliza as chaves canônicas de saída JSON para todas as implementações da biblioteca de Logging Sistemático. Os nomes de campos são reservados e devem ser usados consistentemente em todos os serviços e nos três módulos da biblioteca. Usar sinônimos (ex: `usuarioId` em vez de `userId`, ou `service` em vez de `applicationName`) viola o princípio de consistência e produz resultados divididos em ferramentas de analytics.
>
> **Documentos relacionados:**
> - [Padrão de Logging em Aplicações Java](logging_revisado.md) — fundamentos, 5W1H, padrões arquiteturais
> - [Padrões de Codificação](CODING_STANDARDS.md) — padrões proibidos, obrigatórios e checklist

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
| `applicationName` | `string` | Nome do microsserviço ou aplicação. Lido de `quarkus.application.name` ou equivalente. | Automático — `GerenciadorContextoLog` |

**`traceId` vs `spanId`:** os dois identificadores são complementares e operam em granularidades diferentes dentro da mesma árvore de execução. O `traceId` é constante ao longo de toda a requisição distribuída — é o identificador global que atravessa todos os serviços. O `spanId` identifica a operação individual atual dentro do trace — é o identificador do nó exato da árvore onde ocorreu a falha ou o gargalo. Juntos, são suficientes para diagnóstico completo em todos os níveis, sem necessidade de identificadores adicionais.

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
| `.comDetalhe("eventType", "ORDER_COMPLETED")` | `"detalhe_eventType": "ORDER_COMPLETED"` | Identificador técnico de tipo de evento |
| `.comDetalhe("token", tokenValue)` | `"detalhe_token": "****"` | Mascarado automaticamente pelo `SanitizadorDados` |
| `.comDetalhe("email", emailValue)` | `"detalhe_email": "[PROTEGIDO]"` | Mascarado automaticamente pelo `SanitizadorDados` |

O prefixo `detalhe_` serve dois propósitos: evitar colisão com campos reservados de infraestrutura no índice do Elasticsearch/Loki, e distinguir visualmente campos de negócio de campos de contexto técnico nas ferramentas de analytics.