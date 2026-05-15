# Especificacao da Biblioteca de Observabilidade Quarkus

> Projeto: `lib-logging-quarkus`  
> Referencia conceitual: `docs/observabilidade.md`  
> Objetivo: fornecer uma biblioteca Quarkus reutilizavel para padronizar logging estruturado, tracing distribuido, metricas e correlacao entre pilares de observabilidade.

## 1. Visao do Produto

A biblioteca deve permitir que aplicacoes Quarkus adotem um padrao unico de observabilidade com baixo atrito para o desenvolvedor de negocio.

O consumidor da biblioteca nao deve precisar recriar filtros, interceptadores, nomes de campos, sanitizacao, correlacao de `traceId`/`spanId` ou metricas basicas por metodo. O contrato publico deve guiar o uso correto por compilacao, configuracao padrao e extensoes CDI.

O artefato sera uma biblioteca comum para uso em aplicacoes Quarkus. Nao faz parte do escopo construir uma extensao Quarkus.

## 2. Objetivos

- Padronizar logs estruturados em JSON com campos canonicos.
- Forcar, por DSL, o minimo semantico do evento de log: `What` e `Where`.
- Injetar automaticamente contexto comum: `userId`, `applicationName`, `traceId` e `spanId`.
- Integrar logs, traces e metricas por chaves de correlacao consistentes.
- Reduzir codigo repetido nas aplicacoes consumidoras.
- Proteger dados sensiveis por sanitizacao automatica baseada no nome da chave.
- Permitir extensao por CDI sem alterar o nucleo da biblioteca.
- Manter falhas de observabilidade isoladas do fluxo de negocio.

## 3. Fora do Escopo Inicial

- Persistencia ou consulta de logs.
- Configuracao direta de backends como Loki, Elasticsearch, Graylog, Jaeger ou Grafana Tempo dentro da aplicacao.
- Output GELF nativo pela aplicacao.
- Geracao manual de `traceId`.
- Interceptadores customizados de aplicacao substituindo `@Logged` ou `@Traced`.
- API de auditoria dedicada com `@Auditable` e `AuditRecord`.
- Integracao com Sentry, Rollbar ou outros backends de exception tracking.
- Empacotar exemplos de uso dentro do artefato principal da biblioteca.

## 4. Publico-Alvo

- Times que desenvolvem microsservicos Quarkus.
- Revisores de codigo responsaveis por governanca de observabilidade.
- SREs e pessoas de plataforma que dependem de logs, traces e metricas correlacionaveis.
- Arquitetura, que precisa distribuir um padrao reutilizavel entre sistemas.

## 5. Principios de Projeto

- **Contrato antes de convencao informal:** nomes de campos e APIs devem ser centralizados.
- **Uso correto guiado pelo compilador:** a DSL deve impedir logs incompletos.
- **Observabilidade nao quebra negocio:** falha em tracing ou metricas nao deve derrubar operacoes de dominio.
- **Extensibilidade controlada:** enriquecimento deve ocorrer por interfaces CDI ordenadas por prioridade.
- **Baixa cardinalidade em metricas:** tags como `userId`, `traceId` e identificadores de entidade nao devem ser usadas em metricas.

## 6. Modulos Funcionais

### 6.1 Logging Estruturado

A biblioteca deve expor uma DSL publica para registro de eventos estruturados.

Contrato minimo esperado:

```java
Log
    .registrando(EventEnum.PEDIDO_CRIADO)
    .em(PedidoService.class, "criar")
    .info();
```

Contrato minimo com localizacao automatica:

```java
Log
    .registrando(EventEnum.PEDIDO_CRIADO)
    .aqui()
    .info();
```

Contrato enriquecido esperado:

```java
Log
    .registrando(EventError.PAGAMENTO_RECUSADO)
    .em(PagamentoService.class, "processar")
    .porque("Gateway recusou a autorizacao")
    .como(EntrypointEnum.API_REST)
    .comDetalhe("pedidoId", pedidoId)
    .comDetalhe("valor", valor)
    .erro(excecao);
```

Requisitos:

- `registrando(Event)` deve representar o `What`.
- `registrando(...)` deve aceitar apenas implementacoes de `Event`; texto livre via `String` nao faz parte do contrato publico.
- `Event` deve ser uma interface publica.
- A biblioteca deve fornecer pelo menos um enum padrao que implemente `Event`.
- Aplicacoes consumidoras devem poder criar seus proprios enums implementando `Event`.
- O `Where` tecnico deve poder ser informado de duas formas:
  - `em(Class<?> classe, String metodo)`: declaracao explicita da classe e do metodo.
  - `aqui()`: captura automatica e transparente da classe e do metodo do ponto de chamada.
- A etapa de `Where` deve ser obrigatoria antes de qualquer terminador, seja por `em(...)` ou por `aqui()`.
- `aqui()` deve ignorar frames internos da propria biblioteca para capturar a classe e o metodo reais do consumidor.
- Quando a captura automatica falhar, a biblioteca deve usar valores padrao conhecidos, sem interromper o fluxo de negocio.
- `.porque(...)`, `.como(...)` e `.comDetalhe(...)` devem ser opcionais.
- `como(Entrypoint)` deve representar o `How`.
- `como(...)` deve aceitar apenas implementacoes de `Entrypoint`; texto livre via `String` nao faz parte do contrato publico.
- `Entrypoint` deve ser uma interface publica.
- A biblioteca deve fornecer pelo menos um enum padrao que implemente `Entrypoint`.
- Aplicacoes consumidoras devem poder criar seus proprios enums implementando `Entrypoint`.
- `.comDetalhe(chave, valor)` deve publicar campos com prefixo `detalhe_`.
- Chaves e valores sensiveis devem passar por `SanitizadorDados`.
- Terminadores minimos: `debug()`, `info()`, `warn()`, `erro(Throwable)` e `erroERelanca(Throwable)`.
- A mensagem principal deve permanecer estavel; dados variaveis devem ir para campos estruturados.

### 6.2 Contexto de Log

A biblioteca deve inicializar e limpar MDC por requisicao HTTP.

Requisitos:

- `LogContextoFiltro` deve popular `userId` e `applicationName`.
- `userId` ausente deve ser representado por valor canonico, como `anonimo`.
- `applicationName` deve vir de `quarkus.application.name`.
- `traceId` e `spanId` devem ser sincronizados a partir do span OpenTelemetry ativo.
- O MDC deve ser limpo ao final da resposta HTTP.
- Escritas diretas no MDC fora dos gerenciadores da biblioteca devem ser consideradas proibidas.

### 6.3 Interceptor `@Logged`

A anotacao `@Logged` deve ativar instrumentacao automatica de metodo.

Requisitos:

- Enriquecer MDC com localizacao tecnica do metodo.
- Registrar metrica de duracao: `<application>.metodo.execucao`.
- Registrar metrica de falha: `<application>.metodo.falha`.
- Usar tags de baixa cardinalidade: classe, metodo e tipo de excecao.
- Limpar apenas os campos adicionados pelo enriquecimento do metodo.
- Isolar falhas do `MeterRegistry`.

### 6.4 Tracing Distribuido

A anotacao `@Traced` deve criar spans filhos para metodos relevantes.

Requisitos:

- Criar child span a partir do contexto OpenTelemetry corrente.
- Usar nome de span no formato `Classe.metodo`.
- Atualizar `spanId` no MDC durante a execucao do metodo.
- Restaurar `spanId` anterior ao encerrar o span.
- Marcar span como `ERROR` e registrar excecao em caso de falha.
- Executar antes de `@Logged` quando ambas as anotacoes forem usadas.
- Isolar falhas de encerramento ou enriquecimento de span.

### 6.5 Enriquecimento por CDI

A biblioteca deve permitir enriquecimento sem alterar classes centrais.

Interfaces publicas:

- `EnriquecedorContexto`: adiciona campos ao MDC durante `@Logged`.
- `EnriquecedorTracing`: adiciona atributos ao span durante `@Traced`.
- `Priorizavel`: define ordenacao por `prioridade()`.

Requisitos:

- Beans devem ser descobertos por CDI.
- Execucao deve respeitar prioridade crescente.
- Enriquecedores devem declarar as chaves que adicionam ao MDC quando aplicavel.
- Falhas de enriquecimento nao devem comprometer o fluxo de negocio.

### 6.6 Sanitizacao de Dados

`SanitizadorDados` deve proteger informacoes sensiveis declaradas em detalhes de log.

Requisitos:

- Credenciais devem ser mascaradas como `****`.
- Dados pessoais devem ser mascarados como `[PROTEGIDO]`.
- A decisao de mascaramento deve considerar o nome da chave.
- Dados que exigem redacao total nao devem ser enviados para a DSL; isso e responsabilidade do chamador.

### 6.7 Metricas

A biblioteca deve integrar Micrometer de forma opcional.

Requisitos:

- Metricas devem estar desligadas por padrao quando isso for a decisao do artefato.
- Quando habilitadas, `@Logged` deve publicar timer de execucao e counter de falha.
- A biblioteca deve documentar exemplos de gauges, counters e timers customizados.
- Tags de alta cardinalidade devem ser proibidas em metricas.
- Falhas de metrica devem gerar no maximo log `WARN`, nunca excecao de negocio.

### 6.8 Configuracao Padrao

A biblioteca deve fornecer configuracao compativel com Quarkus e OpenTelemetry.

Requisitos:

- Logging JSON deve ser o formato padrao.
- Propagacao W3C `tracecontext` deve estar habilitada.
- `quarkus-smallrye-context-propagation` deve estar presente para fluxos reativos.
- Exportacao OTLP deve ser configuravel por ambiente.
- O consumidor deve conseguir sobrescrever endpoints, sampler e habilitacao de metricas.

## 7. Contratos Publicos

APIs esperadas como superficie publica da biblioteca:

| Tipo | Nome | Responsabilidade |
|---|---|---|
| DSL | `Log` | Entrada para eventos estruturados |
| DSL | `LogEtapas` | Etapas compile-time da cadeia fluente |
| DSL | `Event` | Contrato para eventos canonicos |
| DSL | `EventEnum` | Enum padrao de eventos fornecido pela biblioteca |
| DSL | `Entrypoint` | Contrato para pontos de entrada canonicos de origem |
| DSL | `EntrypointEnum` | Enum padrao de pontos de entrada fornecido pela biblioteca |
| Anotacao | `@Logged` | Ativa logging contextual e metricas de metodo |
| Anotacao | `@Traced` | Ativa span customizado para metodo |
| Enum | `CamposMdc` | Catalogo de chaves canonicas |
| Contexto | `GerenciadorContextoLog` | Inicializacao, enriquecimento e limpeza de MDC |
| Tracing | `GerenciadorTracing` | Ciclo de vida de spans |
| Extensao | `EnriquecedorContexto` | Extensao de campos de log |
| Extensao | `EnriquecedorTracing` | Extensao de atributos de span |
| Seguranca | `SanitizadorDados` | Mascaramento automatico |

## 8. Campos Canonicos

Campos reservados:

| Campo | Origem | Obrigatoriedade |
|---|---|---|
| `userId` | Filtro de contexto | Obrigatorio em requisicoes HTTP |
| `applicationName` | Configuracao Quarkus | Obrigatorio |
| `traceId` | OpenTelemetry | Obrigatorio quando houver span valido |
| `spanId` | OpenTelemetry | Obrigatorio quando houver span valido |
| `classe` | `@Logged` | Obrigatorio em metodos anotados |
| `metodo` | `@Logged` | Obrigatorio em metodos anotados |
| `log_classe` | DSL `.em(...)` | Obrigatorio em eventos DSL |
| `log_metodo` | DSL `.em(...)` | Obrigatorio em eventos DSL |
| `log_motivo` | DSL `.porque(...)` | Opcional |
| `log_entrypoint` | DSL `.como(...)` | Opcional |
| `detalhe_*` | DSL `.comDetalhe(...)` | Opcional |

## 9. Requisitos Nao Funcionais

- Java alvo: 21.
- Quarkus alvo: 3.20+.
- Compatibilidade com native image deve ser preservada.
- Overhead dos interceptadores deve ser baixo e previsivel.
- A biblioteca deve ser thread-safe no uso normal por requisicao.
- O MDC nao pode vazar entre requisicoes.
- APIs publicas devem evitar strings soltas para campos reservados.
- A documentacao deve separar claramente referencia conceitual, guia de uso e especificacao.

## 10. Criterios de Aceite

### Logging

- Um evento DSL minimo gera JSON com `message`, `log_classe` e `log_metodo`.
- Um evento DSL usando `.em(Classe.class, "metodo")` gera `log_classe` e `log_metodo` com os valores informados.
- Um evento DSL usando `.aqui()` gera `log_classe` e `log_metodo` a partir da classe e do metodo do ponto de chamada.
- Um evento DSL usando `.como(EntrypointEnum.API_REST)` gera `log_entrypoint` com o valor canonico do ponto de entrada.
- Um evento com `.comDetalhe("pedidoId", 123)` gera `detalhe_pedidoId`.
- Um evento com chave sensivel, como `token`, gera valor mascarado.
- Campos adicionados por um evento nao aparecem no proximo evento.

### Contexto

- Requisicao autenticada popula `userId` com o principal.
- Requisicao anonima popula `userId=anonimo`.
- `applicationName` reflete `quarkus.application.name`.
- MDC e limpo ao final da resposta.

### Tracing

- Metodo com `@Traced` cria child span.
- Logs emitidos dentro do metodo usam `spanId` do child span.
- Ao final, `spanId` pai e restaurado.
- Excecao marca o span como erro.

### Metricas

- Metodo com `@Logged` registra timer de execucao quando Micrometer esta ativo.
- Excecao em metodo com `@Logged` incrementa counter de falha.
- Falha no registro de metrica nao altera o resultado do metodo de negocio.

### Extensibilidade

- Dois enriquecedores CDI sao executados em ordem de prioridade.
- Chaves declaradas por enriquecedores de contexto sao removidas no `finally`.

## 11. Marcos Sugeridos

### v0.1 - Logging Base

- DSL `Log`.
- Interface `Event` e enum padrao de eventos.
- Interface `Entrypoint` e enum padrao de pontos de entrada.
- `CamposMdc`.
- `SanitizadorDados`.
- `GerenciadorContextoLog`.
- `LogContextoFiltro`.
- Testes de MDC, DSL e sanitizacao.
- Projeto ou modulo separado com exemplos de uso.

### v0.2 - Tracing e Metricas

- `@Traced`.
- `GerenciadorTracing`.
- Enriquecedores de span.
- `@Logged` com timer e counter.
- Testes de prioridade entre interceptadores.
- Perfil de teste com Micrometer habilitado.

### v0.3 - Governanca e Auditoria

- Catalogo formal de eventos.
- `@Auditable` e `AuditRecord`, se confirmados.
- `ExceptionReporter`, se confirmado.
- Validadores ou testes arquiteturais para padroes proibidos.

## 12. Decisoes Registradas

| Decisao | Resultado |
|---|---|
| Tipo de artefato | Biblioteca comum para uso com Quarkus; nao sera extensao Quarkus |
| Java alvo | Java 21 |
| Exemplos | Exemplos devem existir, mas fora do artefato principal da biblioteca |
| Entrada da DSL | `Log.registrando(...)` aceita apenas `Event` |
| Declaracao de `Where` | A DSL suporta `em(Class<?> classe, String metodo)` e `aqui()` |
| Extensibilidade de eventos | `Event` e interface; a biblioteca fornece enum padrao e consumidores podem criar seus proprios enums |
| Entrada de entrypoint | `Log.como(...)` aceita apenas `Entrypoint` |
| Extensibilidade de entrypoints | `Entrypoint` e interface; a biblioteca fornece enum padrao e consumidores podem criar seus proprios enums |
| Politica produtiva de log em arquivo | Nao sera uma politica imposta pela especificacao da biblioteca |
| Nomenclatura publica | Usar nomenclatura `Log` e `Tracing`, nao `LogSistematico` nem `Rastreamento` |

## 13. Riscos

- Publicar configuracoes de exemplo dentro do artefato pode impor comportamento indevido aos consumidores.
- Usar tags de alta cardinalidade em metricas pode degradar Prometheus e dashboards.
- Sanitizacao baseada apenas em chave reduz risco, mas nao substitui revisao de dados sensiveis pelo chamador.
- Enquanto a documentacao conceitual ainda usar nomes antigos, como `LogSistematico`, pode haver confusao na adocao por outros times.
