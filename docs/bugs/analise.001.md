## Achados críticos

### 1. Falha de enriquecimento aborta o método de negócio e é relançada como erro de negócio

Em `TracingInterceptor.rastrear`:

```java
try {
    contextoSpan = gerenciador.iniciar(nomeSpan, contexto);  // pode lançar
    return contexto.proceed();
} catch (Throwable erro) {
    ...
    relancar(erro);
```

`GerenciadorTracing.iniciar()` executa `enriquecedores.forEach(e -> e.enriquecer(span, contexto))` sem isolamento por enriquecedor. Se qualquer `EnriquecedorTracing` (built-in ou de negócio) lançar, o `iniciar()` faz cleanup e relança. Como isso acontece **antes** de `contexto.proceed()`, o método de negócio nunca executa — e a exceção de infraestrutura OTel é tratada pelo `catch (Throwable erro)` como se fosse erro do método interceptado, sendo relançada ao chamador via `relancar(erro)`.

O mesmo padrão existe em `LogInterceptor`:

```java
try (var escopoMdc = gerenciador.abrirEscopoEnriquecimento(contexto)) {
```

`abrirEscopoEnriquecimento` chama `enriquecer(contexto)` sem isolar falha por enriquecedor; se lançar, a inicialização do try-with-resources falha e `contexto.proceed()` nunca é chamado.

Isso contradiz diretamente a especificação (`docs/especificacao.md` §5 e §6.5: *"Falhas de enriquecimento não devem comprometer o fluxo de negócio"*). Hoje um bug em um `EnriquecedorTracing`/`EnriquecedorContexto` de negócio derruba toda invocação do método anotado.

**Correção:** isolar a criação/enriquecimento do span (e do MDC) com try-catch que absorve a falha, loga WARN e segue para `contexto.proceed()` sem span/enriquecimento — nunca deixar essa exceção competir com o fluxo real de `proceed()`. Também vale isolar cada enriquecedor individualmente dentro do `forEach`, para que um enriquecedor ruim não impeça os demais de rodar.

---

### 2. `traceId`/`spanId` nunca são limpos ao fim da requisição HTTP

`LogContextoFiltro`:

```java
public void filter(ContainerRequestContext requestContext,
                   ContainerResponseContext responseContext) {
    var escopo = requestContext.getProperty(ESCOPO_MDC_REQUISICAO);
    if (escopo instanceof EscopoMdc escopoMdc) {
        escopoMdc.close();          // <- caminho normal
    } else {
        gerenciador.limpar();       // <- só no fallback (raro)
    }
}
```

`abrirEscopoRequisicao` só captura `USER_ID`/`APPLICATION_NAME`:

```java
public EscopoMdc abrirEscopoRequisicao(String userId) {
    return EscopoMdc.aplicar(Map.of(
            CamposMdc.USER_ID.chave(), uid,
            CamposMdc.APPLICATION_NAME.chave(), applicationName));
}
```

`TRACE_ID`/`SPAN_ID` são escritos em `GerenciadorTracing.sincronizarMdcRequisicao()` — método write-only, sem escopo/restauração associado. No caminho normal (que é sempre o caminho, já que `abrirEscopoRequisicao` sempre retorna um `EscopoMdc`), `gerenciador.limpar()` (que remove `TRACE_ID`/`SPAN_ID` e roda `limparEnriquecimento()`) **nunca é chamado**.

Resultado: em pools de thread do Vert.x (o cenário que a própria doc trata extensivamente na seção 7/9.3), `traceId`/`spanId` da requisição anterior podem vazar para logs de threads reaproveitadas antes que uma nova sincronização ocorra — exatamente o Anti-padrão P7 que a doc lista. Contradiz também o critério de aceite explícito: *"MDC é limpo ao final da resposta"* (`especificacao.md` §10).

**Correção:** no branch `escopoMdc.close()`, chamar também `gerenciador.limpar()` (ou incluir `TRACE_ID`/`SPAN_ID` no escopo capturado por `abrirEscopoRequisicao`).

---

## Inconsistências código vs. documentação

### 3. `LogEvento` força `classe` para minúsculas

```java
public LogEvento {
    ...
    classe = normalizarObrigatorio(classe, ValoresPadrao.LOCALIZACAO_DESCONHECIDA, true); // lowerCase=true
    metodo = normalizarObrigatorio(metodo, ValoresPadrao.LOCALIZACAO_DESCONHECIDA, true);
```

O `lowerCase=true` para `metodo` é intencional (documentado no javadoc de `Log.em()`). Para `classe` não é — parece cópia do mesmo flag. Resultado: `log_classe` sai como `"pedidoservice"`, enquanto `classe` (preenchido por `MetadadosEnriquecedorContexto`, sem lowercase) sai como `"PedidoService"`. Todos os exemplos JSON da doc (`observabilidade.md`, várias seções) mostram `classe` e `log_classe` com a mesma capitalização. Divergência real entre implementação e contrato documentado.

**Correção:** `normalizarObrigatorio(classe, ..., false)`.

---

### 4. Valores de `.comDetalhe()` sempre viram String

```java
eventoLog.detalhes().forEach((chave, valor) -> camposEvento.put(
        CamposMdc.PREFIXO_DETALHE + chave, valor != null ? valor.toString() : "null"));
```

A doc (`observabilidade.md` §27.5) afirma explicitamente: *"`.comDetalhe("valorTotal", 349.90)` → `detalhe_valorTotal` — `number` — `float`/`double` preservam o tipo"*. O código sempre chama `.toString()` antes de colocar no MDC, então todo detalhe numérico sai como string no JSON. Isso quebra agregações/analytics descritas na seção 3.7 (dashboards de KPI sobre `valorTotal` etc.).

**Decisão necessária:** ou o código passa a preservar tipo (evitando `.toString()` incondicional), ou a doc é corrigida para refletir que todo detalhe é string. Hoje há contrato divergente da implementação.

---

### 5. `EventEnum` viola a própria convenção de mensagens

`EventEnum.EVENTO_GENERICO("evento_generico")` é exatamente o anti-padrão que `observabilidade.md` §3.2 lista como **PROIBIDO**: *"vago, sem fingerprinting possível: `Log.registrando(EventoGenerico.ERRO_PROCESSAMENTO)`"*. Além disso, `LOGIN("login")`, `LOGOUT("logout")`, `CONTEXT_TRACE("context_trace")` são identificadores em snake_case, não frases factuais no passado (`"Login realizado"`, `"Logout efetuado"`) como a mesma seção exige. O enum padrão fornecido pela biblioteca não segue o próprio guia de estilo que ela impõe aos consumidores.

---

### 6. `LogInterceptor` usa dois `Event` diferentes para o mesmo tipo de falha interna

```java
// registrarFalha:
Log.registrando(EventEnum.EVENT_ERROR)...
// registrarExecucao:
Log.registrando(EventError.EVENT_ERROR)...
```

São enums e mensagens diferentes (`"event_error"` vs `"Evento_ERRO"`) para a mesma categoria semântica (falha ao registrar métrica). Quebra o princípio de fingerprinting estável de mensagem (§2.3) mesmo dentro do próprio código da lib. `TracingInterceptor.registrarFalhaOtel`, em contraste, é consistente (sempre `EventError.EVENT_ERROR`).

---

## Achados menores

- **Sem isolamento por enriquecedor individual**: tanto `GerenciadorContextoLog.enriquecer()` quanto `GerenciadorTracing.iniciar()` fazem `forEach` sem try-catch por item — um enriquecedor de negócio com bug bloqueia os enriquecedores subsequentes (além de disparar o Bug #1).
- **`spanIdPai` capturado duas vezes**: `TracingInterceptor.rastrear` lê `MDC.get(SPAN_ID)` antes de chamar `iniciar()`, que internamente lê o mesmo valor de novo e o guarda em `ContextoSpan.spanIdAnterior()`. `GerenciadorTracing.encerrar` então prioriza `ctx.spanIdAnterior()` sobre o parâmetro externo — redundante, os dois valores são sempre iguais na prática. Simplificar removendo uma das capturas.
- **Visibilidade de campo inconsistente**: `LogContextoFiltro`, `MetadadosEnriquecedorTracing`, `SecurityIdentityEnriquecedorContexto`, `SecurityIdentityEnriquecedorTracing` declaram dependências injetadas por construtor como campo package-private mutável, não `private final` (diferente de `TracingInterceptor`/`GerenciadorTracing`, que usam `private final` corretamente).

---

## Pontos positivos

- `sealed interface LogEtapas permits Log` garante em compilação o mínimo semântico (What/Where) — enforcement real, não só documentado.
- `EscopoMdc` com captura/restore por chave é uma solução limpa para chamadas aninhadas e para o problema de contexto reativo descrito na doc.
- Onde existe isolamento de falha OTel/Micrometer (`marcarErro`, `encerrar`, `registrarFalha`, `registrarExecucao`), o padrão try-catch está correto e não propaga para o negócio — só falha nos dois pontos de entrada do enriquecimento (Bug #1).
- Separação de pacotes (`context/`, `tracing/`, `dsl/`, `interceptor/`) reflete fielmente a arquitetura descrita em `especificacao.md` §8.1.

---

## Prioridade de correção

1. Bug #1 (falha de enriquecimento derruba negócio) — risco de outage em produção.
2. Bug #2 (leak de `traceId`/`spanId`) — corrompe correlação de logs entre requisições.
3. Item #3 (`log_classe` lowercase) — quebra consumo/analytics que dependem de casing consistente.
4. Item #4 (tipo dos detalhes) — decisão de contrato pendente entre doc e código.
5. Itens #5, #6 e os "achados menores" — consistência/governança, sem risco funcional imediato.