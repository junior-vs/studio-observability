# Rastreamento Distribuído (Distributed Tracing)

> Padrão estrutural para correlação de execuções através do ecossistema de microsserviços. O rastreamento unifica logs dispersos em uma narrativa única de transação de usuário.
>
> *Conceitualmente aderente ao padrão homônimo catalogado no repositório [iluwatar/java-design-patterns](https://github.com/iluwatar/java-design-patterns).*

---

## 1. Visão Geral

Em uma arquitetura de microsserviços, uma única operação do usuário (como fechar um pedido) pode percorrer dezenas de serviços distintos. Cada serviço registra os próprios eventos, mas esses rastros ocorrem simultaneamente a milhares de outras requisições. Sem um identificador global, reconstruir a jornada de um problema torna-se virtualmente impossível.

O Rastreamento Distribuído resolve essa questão atribuindo um **Trace ID** no ponto de entrada e propagando-o por cada salto de rede, agrupando *Spans* (blocos de execução) e *Logs* (eventos granulares) sob o mesmo guarda-chuva.

---

## 2. Quando Utilizar (When to Use)

Baseado nos preceitos arquiteturais de design de microsserviços, este padrão é imperativo quando:
- Múltiplos serviços formam um único caminho de requisição de usuário e o diagnóstico de falhas exige visibilidade além das fronteiras do serviço.
- Monitorar e descobrir gargalos de performance (*bottlenecks*) em um ambiente altamente distribuído é crítico para o negócio.
- A correlação de *Logs* e *Métricas* de serviços independentes é o único meio prático de atestar a saúde geral do sistema perante os usuários.

---

## 3. Integração Nativa no Quarkus

O projeto de observabilidade moderna utiliza a extensão `quarkus-opentelemetry` como provedora primária do rastreamento. O esforço braçal desaparece, já que a plataforma injeta os agrupadores automaticamente.

Com a dependência instalada, o Quarkus:
- Cria o *Root Span* nos endpoints HTTP de entrada (Controllers / JAX-RS).
- Propaga a especificação de cabeçalho **W3C TraceContext** (`traceparent: 00-[traceId]-[parentSpanId]-01`) nas chamadas do REST Client para outros serviços.
- Injeta automaticamente o `traceId` e `spanId` nativos no MDC para que o provedor JSON do JBoss Logging empacote na saída do Console (`logging-quarkus`).

### A Arquitetura do `logging-quarkus` (LogSistematico)
A nossa biblioteca captura tais metadados passivamente a cada `.info()` ou `.erro()`. Assim, um erro no Serviço A e um processamento no Serviço B carregam o mesmo conjunto chave-valor em seus respectivos `stdout`, unificando as visualizações em aglutinadores (Kibana, Loki).

---

## 4. Extração Segura de Trace (vs Falsificação)

É anti-padrão gerar `UUID.randomUUID()` aleatoriamente e chamar de `traceId`. Metadados inventados pela aplicação não criam grafos inter-serviços nas ferramentas modernas. O dado deve vir da árvore legítima do rastreador subjacente.

Ao acionar blocos passivos assíncronos que perdem o contexto nativo da requisição HTTP, deve-se usar os componentes de contexto da biblioteca:

```java
// O GerenciadorContextoLog se encarrega de descobrir se existe uma transação OTel ativa 
// e transportá-la para o ecossistema do Log sem que o dev precise manusear IDs.
try {
    gerenciadorContextoLog.inicializar(usuarioAtivo); // Puxa traceId/spanId reais e injeta no MDC
    // ...
} finally {
    gerenciadorContextoLog.limpar();
}
```

---

## 5. Trade-offs (Custo e Desvantagens)

Embora agregue grande maturidade ao diagnóstico logístico, a arquitetura de rastreamento exige as seguintes ressalvas (trade-offs):
- **Overhead Transacional:** A coleta de traces de alta amostragem adiciona esforço computacional, latência extra a cada requisição HTTP devido à injeção de headers e aumento do tamanho dos payloads das respostas internas.
- **Complexidade de Infraestrutura:** Exige sustentação e configuração de tecnologias externas dedicadas (Jaeger, Zipkin, OTel Collector, Bancos de Dados de grande retenção para Spans).
- **Gerenciamento de Volume:** Em ecossistemas gigantescos, gravar 100% de todos os metadados satura o disco. Faz-se necessário adotar técnicas como *Tail-based Sampling* para reter apenas as falhas.

---

## 6. Padrões Relacionados (Related Patterns)

O "Distributed Tracing" atua de forma muito mais poderosa quando desenhado em conjunto com os padrões de microsserviços clássicos apontados pelo catálogo `iluwatar`:

- Padrão **API Gateway**: Atua como o ponto absoluto de entrada (Front-door) e geralmente é o responsável número inicial por acartar a criação do primeiro *Root Span* na requisição do cliente que viaja aos *downstreams*.
- Padrão **Log Aggregation**: Tracing isolado é pouco útil; ele brilha em completude quando associado ao Log Aggregation (Ex: Elasticsearch), garantindo que ao pesquisar por um ID, veja-se o relatório inteiro agrupado.
- Padrão **Circuit Breaker**: Utilizados em união para mapear falhas em cascata; o rastreio expõe de forma gráfica na UI exatamente qual nó ativou o disjuntor de circuito de forma visual.
- Padrão **Saga**: Orquestra e coreografa a malha de transações distribuídas (onde cada nó encerra partes da compra); eleva-se pelo uso de IDs persistados no rastro para manter a integridade dos cancelamentos.

---

## 7. `requestId` vs `traceId`

Esses identificadores não competem; eles operam em funis de escopo dispares:

| Identificador | Escopo e Limitação | Propósito Real |
| --- | --- | --- |
| `requestId` | Duração de uma simples conexão HTTP presa em UM ÚNICO serviço. | Investigar loops e processamentos isolados acontecendo na thread do container. |
| `traceId` | Ciclo integral e infinito do usuário, rasgando BORDAS de múltiplas APIs. | Desenhar o mapa de rede, descobrir qual serviço quebrou a fila de todos os outros. |

*Ambos migraram formalmente para o formato `camelCase` (em substituição às versões com _underline) devido à taxonomia consolidada na saída do OTel/Quarkus Logging (`requestId`, `traceId`, `spanId`).*

---

## Conceitos Não Aplicáveis ou Fora do Escopo do Projeto

Parte dos manuais legados de arquitetura exigiam configurações massivas e controle rígido no âmbito da aplicação corporativa, sendo que, atualmente, esses temas foram empurrados estritamente para a borda (Plataforma Kubernetes / Agente Externo).

### 1. Construção de Interceptors e Filtros de Tracing Próprios (`observa4j`)
As versões anteriores detalhavam componentes da suíte `observa4j` encarregados de interceptar JAX-RS Filters, capturar cabeçalhos X-Correlation e construir chaves manuais para transitar informações até bibliotecas de log fluentes. Hoje, a simples declaração de dependência das frentes do Quarkus Smallrye (`quarkus-smallrye-context-propagation`) atrela o W3C TraceContext organicamente em toda malha mutiny/vert.x — **sem necessidade de nenhuma biblioteca do tipo `observa4j-tracing` escrita in-house.**

### 2. Estratégias Complexas de Amostragem (*Sampling Head/Tail-based*)
Foi redigido historicamente que a aplicação gerenciava suas taxas de coleta (*Head-Based*) ou definia quais serviços logariam por erro ou vazão (*Tail-Based* bufferizado). No modelo de abstração presente, a SDK se define agnóstica a taxas, enviando 100% de Spans (via propriedade `quarkus.otel.traces.sampler=always_on`). Tais negociações, filtros dinâmicos e políticas de decaimento percentual residem 100% nas configurações de YAML dos contêineres do `OTel Collector` no cluster.

### 3. Nomes de Chaves Forçadas (`traceId`, `spanId`)
A taxonomia arcaica, herdada da tentativa de aderir à notação flat via customização forte, deu lugar à integridade nativa. Se a biblioteca JBoss Logging adere por padrão aos campos `traceId` e `spanId` associados ao OTel extensor, o projeto aceita a premissa de `camelCase` e foca em sanidade ao invés de manipulação bruta de strings no JSON final de saída stdout.
