# Plano de Implementacao da Biblioteca de Observabilidade

> Projeto: `lib-logging-quarkus`  
> Base de referencia: `docs/observabilidade.md` e `docs/especificacao.md`  
> Escopo deste documento: planejar a implementacao. Nao executar alteracoes de codigo.

## 1. Objetivo

Evoluir a implementacao atual de `lib-logging-quarkus` para aderir ao contrato documentado:

- Biblioteca comum para uso em aplicacoes Quarkus, nao extensao Quarkus.
- Java 21.
- API publica com `Log`, `Event`, `Entrypoint`, `@Logged` e `@Traced`.
- DSL com `registrando(Event)`, `em(Class<?>, String)`, `aqui()`, `como(Entrypoint)` e terminadores de nivel.
- Campo canonico `log_entrypoint`, substituindo o conceito anterior de `log_canal`.
- Exemplos fora do artefato principal da biblioteca.
- Testes cobrindo DSL, MDC, tracing, metricas, sanitizacao e extensibilidade CDI.

## 2. Estado Atual Observado

Implementacao atual em `lib-logging-quarkus`:

| Area | Estado atual | Lacuna em relacao a documentacao |
|---|---|---|
| Build | `maven.compiler.release=25`; `packaging=quarkus` | Contrato pede Java 21 e biblioteca comum, nao app/extensao |
| DSL | Classe publica `LOG` | Contrato pede `Log` |
| Eventos | `Event` ja existe; enums `EventEnum` e `EventError` existem | Precisa consolidar contrato publico e exemplos de dominio |
| Where | Apenas `.em(Class<?>, String)` | Falta `.aqui()` com captura automatica |
| How | `.como(String)` e campo `log_canal` | Deve virar `.como(Entrypoint)` e `log_entrypoint` |
| Tracing | Anotacao `@Rastreado` | Contrato futuro pede `@Traced` |
| Exemplos | Pacote `br.com.vsjr.labs.example` dentro de `src/main/java` | Devem sair do artefato principal |
| Configuracao | `application.properties` inclui defaults de app, dev e prod | Biblioteca deve evitar impor politica operacional indevida aos consumidores |
| Testes | Nao ha arvore de testes visivel em `src/test` | Precisa suite de regressao antes de estabilizar API |

## 3. Premissas e Regras de Trabalho

- As sprints abaixo sao sequenciais; uma sprint so deve ser considerada pronta quando seu checklist e criterios de aceite forem cumpridos.
- Mudancas de API publica devem ser feitas antes de ampliar testes de integracao, para evitar reescrita repetida.
- Se ainda nao houver consumidores externos, preferir rename limpo em vez de manter aliases/deprecations.
- Se ja houver consumidor externo, manter adaptadores temporarios com `@Deprecated` e planejar remocao antes da versao estavel.
- Qualquer falha em observabilidade deve ser isolada do fluxo de negocio.
- Nenhum exemplo deve permanecer empacotado no artefato principal da biblioteca ao final do plano.

## Sprint 0 - Preparacao, Baseline e Decisoes de Build

### Objetivo

Preparar o projeto para evolucao segura: definir alvo Java 21, formato do artefato, estrategia de testes e baseline de compilacao.

### Tasks

- Revisar `lib-logging-quarkus/pom.xml` e definir o formato final do artefato:
  - biblioteca comum Quarkus;
  - nao extensao Quarkus;
  - avaliar troca de `packaging=quarkus` para empacotamento adequado de biblioteca.
- Alterar alvo de compilacao planejado para Java 21.
- Levantar dependencias realmente necessarias para a biblioteca principal.
- Classificar dependencias de exemplo/dev (`quarkus-scheduler`, recursos REST, gauges demonstrativos etc.) para extracao posterior.
- Criar estrutura de testes se inexistente:
  - testes unitarios para DSL e sanitizacao;
  - testes Quarkus/CDI para interceptors e filtros;
  - perfil de teste para Micrometer habilitado.
- Documentar estrategia de compatibilidade:
  - rename limpo se nao houver consumidor externo;
  - deprecations temporarias se houver consumidor.

### Entregavel

Baseline tecnico do projeto definido, com build alvo Java 21, estrategia de empacotamento decidida e estrutura minima de testes planejada.

### Checklist

- [ ] Decisao registrada sobre `packaging` final da biblioteca.
- [ ] Java 21 definido no build.
- [ ] Dependencias de runtime da biblioteca separadas das dependencias de exemplo.
- [ ] Estrutura de testes definida.
- [ ] Comando oficial de validacao documentado.

### Criterios de Aceite

- O projeto possui decisao explicita de empacotamento compativel com biblioteca comum Quarkus.
- O build alvo e Java 21.
- Existe lista clara de dependencias que pertencem ao core da biblioteca.
- Existe plano de testes executavel para as sprints seguintes.

## Sprint 1 - API Publica da DSL: `Log`, `Event`, `Entrypoint` e `Where`

### Objetivo

Alinhar a API publica da DSL ao contrato documentado, mantendo enforcement de fluxo em tempo de compilacao.

### Tasks

- Renomear o ponto de entrada da DSL de `LOG` para `Log`.
- Atualizar `LogEtapas` para permitir dois caminhos obrigatorios de `Where`:
  - `.em(Class<?> classe, String metodo)`;
  - `.aqui()`.
- Implementar captura automatica em `.aqui()`:
  - ignorar frames internos da biblioteca;
  - capturar classe e metodo reais do consumidor;
  - usar fallback conhecido quando a captura falhar.
- Consolidar `Event` como contrato publico:
  - manter interface extensivel;
  - revisar nomes dos metodos (`getEvent()` ou nome mais semantico);
  - padronizar enum default da biblioteca.
- Criar contrato `Entrypoint`:
  - interface publica;
  - enum default `EntrypointEnum`;
  - valores iniciais sugeridos: `API_REST`, `KAFKA_CONSUMER`, `SCHEDULER`, `GRPC`, `INTERNO`.
- Alterar `.como(String)` para `.como(Entrypoint)`.
- Atualizar `LogEvento` para substituir `canal` por `entrypoint`.
- Atualizar `CamposMdc`:
  - remover ou depreciar `LOG_CANAL`;
  - adicionar `LOG_ENTRYPOINT("log_entrypoint")`.
- Atualizar emissoes internas da biblioteca que ainda usam `.como(String)`.
- Atualizar JavaDoc da DSL para refletir `Log`, `Event`, `Entrypoint`, `.aqui()` e `log_entrypoint`.

### Entregavel

DSL publica alinhada ao contrato documentado e protegida por tipos: eventos e entrypoints extensiveis, `Where` automatico disponivel, campo `log_entrypoint` emitido.

### Checklist

- [ ] `Log.registrando(Event)` e o ponto de entrada publico.
- [ ] Nao ha uso publico de `LOG` em exemplos ou documentacao tecnica nova.
- [ ] `.aqui()` existe na etapa correta da DSL.
- [ ] `.como(...)` aceita `Entrypoint`, nao `String`.
- [ ] `LogEvento` usa `entrypoint`.
- [ ] `CamposMdc` expoe `LOG_ENTRYPOINT`.
- [ ] Exemplos de codigo usam `EventEnum`/eventos de dominio e `EntrypointEnum`.

### Criterios de Aceite

- Codigo consumidor consegue compilar:

```java
Log.registrando(EventEnum.EVENTO_GENERICO)
    .aqui()
    .como(EntrypointEnum.API_REST)
    .info();
```

- Codigo consumidor nao consegue chamar terminadores antes de declarar `Where`.
- Codigo consumidor nao consegue passar `String` diretamente em `.registrando(...)`.
- Codigo consumidor nao consegue passar `String` diretamente em `.como(...)`.
- Evento com `.aqui()` emite `log_classe` e `log_metodo` do chamador real.
- Evento com `.como(EntrypointEnum.API_REST)` emite `log_entrypoint=API_REST`.

## Sprint 2 - Contexto, MDC e Emissao Estruturada

### Objetivo

Garantir que logs emitidos pela DSL carreguem campos canonicos corretos, tenham limpeza segura de MDC e mantenham correlacao com tracing.

### Tasks

- Revisar `GerenciadorContextoLog` e `GerenciadorTracing` para garantir separacao clara:
  - identidade e aplicacao no contexto de log;
  - `traceId` e `spanId` no tracing.
- Avaliar a regra documentada para contexto reativo:
  - sincronizar `traceId`/`spanId` no momento da emissao quando houver span OTel ativo;
  - evitar depender apenas do valor inicial do filtro HTTP.
- Atualizar `Log.emitir(...)` para usar os nomes canonicos novos:
  - `log_entrypoint`;
  - `detalhe_*`;
  - `log_classe`, `log_metodo`, `log_motivo`.
- Garantir limpeza de todos os campos inseridos pela DSL no `finally`.
- Revisar sanitizacao de detalhes:
  - credenciais para `****`;
  - dados pessoais para `[PROTEGIDO]`;
  - chaves normalizadas sem destruir nomes canonicos como `eventType`.
- Criar testes unitarios de:
  - emissao minima;
  - emissao com detalhes;
  - sanitizacao;
  - limpeza de MDC apos evento;
  - `eventType` preservado como `detalhe_eventType`.
- Criar testes para MDC em filtro HTTP:
  - usuario autenticado;
  - usuario anonimo;
  - limpeza no response filter.

### Entregavel

Pipeline de logging estruturado consistente, com campos canonicos e sem vazamento de MDC entre eventos ou requisicoes.

### Checklist

- [ ] `log_entrypoint` substitui `log_canal`.
- [ ] Campos da DSL sao removidos do MDC apos a emissao.
- [ ] Campos de contexto de requisicao permanecem ate o final da resposta.
- [ ] `eventType` e canonizado corretamente.
- [ ] Chaves sensiveis sao mascaradas.
- [ ] Testes cobrem valores nulos, vazios e fallback.

### Criterios de Aceite

- Um evento minimo gera `message`, `log_classe` e `log_metodo`.
- Um evento com entrypoint gera `log_entrypoint`.
- Um evento com `.comDetalhe("pedidoId", 123)` gera `detalhe_pedidoId`.
- Um evento com `.comDetalhe("token", valor)` gera `detalhe_token=****`.
- Apos uma emissao, `log_classe`, `log_metodo`, `log_motivo`, `log_entrypoint` e `detalhe_*` nao permanecem no MDC.
- Ao final de uma requisicao HTTP, o MDC e limpo.

## Sprint 3 - Tracing: `@Traced`, Interceptor e Enriquecimento

### Objetivo

Renomear e estabilizar a API publica de tracing, preservando comportamento: child span, MDC de `spanId`, erro no span e prioridade antes de `@Logged`.

### Tasks

- Renomear anotacao publica de `@Rastreado` para `@Traced`.
- Atualizar `TracingInterceptor` para usar `@Traced`.
- Definir estrategia de compatibilidade:
  - remover `@Rastreado` se nao houver consumidor externo;
  - ou manter `@Rastreado` como deprecated delegando para novo contrato ate remocao.
- Atualizar JavaDoc, README e exemplos para `@Traced`.
- Revisar prioridade:
  - `TracingInterceptor` deve executar antes de `LogInterceptor`;
  - documentar e testar prioridade numerica.
- Revisar tratamento de erro:
  - `setStatus(ERROR)`;
  - `recordException`;
  - relancar excecao original.
- Isolar falhas de encerramento de span:
  - falha em OTel nao deve quebrar negocio;
  - log interno deve usar nova DSL tipada.
- Criar testes CDI/Quarkus para:
  - criacao de child span;
  - restauracao de `spanId` pai;
  - marcacao de erro;
  - ordem com `@Logged`;
  - enriquecedores `EnriquecedorTracing` por prioridade.

### Entregavel

API de tracing publica com `@Traced`, interoperando corretamente com `@Logged`, MDC e enriquecedores CDI.

### Checklist

- [ ] `@Traced` existe como anotacao publica.
- [ ] `TracingInterceptor` usa `@Traced`.
- [ ] Estrategia para `@Rastreado` definida e aplicada.
- [ ] Ordem `TracingInterceptor` antes de `LogInterceptor` coberta por teste.
- [ ] `spanId` pai e restaurado.
- [ ] Excecoes marcam span como erro.
- [ ] Enriquecedores de tracing executam por prioridade.

### Criterios de Aceite

- Metodo anotado com `@Traced` cria child span.
- Logs emitidos dentro do metodo usam `spanId` do child span.
- Ao sair do metodo, o `spanId` anterior e restaurado.
- Excecao no metodo marca o span como `ERROR` e e relancada.
- Falha ao encerrar span nao substitui a excecao de negocio.
- `@Traced` e `@Logged` no mesmo metodo mantem ordem correta de interceptacao.

## Sprint 4 - Metricas e Observabilidade do Metodo

### Objetivo

Estabilizar metricas automaticas de `@Logged`, garantindo nomes, tags de baixa cardinalidade e isolamento de falhas de Micrometer.

### Tasks

- Revisar `LogInterceptor` para usar nova DSL (`Log`, `Entrypoint`) nos logs internos.
- Confirmar nomes canonicos:
  - `<application>.metodo.execucao`;
  - `<application>.metodo.falha`.
- Validar tags permitidas:
  - classe;
  - metodo;
  - excecao.
- Garantir que `MeterRegistry` ausente nao gere erro.
- Garantir que falha no registro de metrica gere apenas `WARN`.
- Criar perfil de teste com Micrometer habilitado.
- Criar testes para:
  - timer de sucesso;
  - counter de falha;
  - metodo sem registry;
  - falha simulada de registry;
  - ausencia de tags de alta cardinalidade.
- Revisar configuracao default de metricas em `application.properties` para nao impor comportamento indesejado ao consumidor.

### Entregavel

Metricas automaticas de metodo confiaveis, opcionais e seguras para uso em aplicacoes consumidoras.

### Checklist

- [ ] Timer de execucao usa nome com prefixo da aplicacao.
- [ ] Counter de falha usa nome com prefixo da aplicacao.
- [ ] Tags sao de baixa cardinalidade.
- [ ] `MeterRegistry` ausente e tratado.
- [ ] Falhas de metrica nao quebram negocio.
- [ ] Testes com Micrometer habilitado existem.

### Criterios de Aceite

- Metodo `@Logged` bem-sucedido registra timer quando Micrometer esta ativo.
- Metodo `@Logged` que falha incrementa counter por tipo de excecao.
- Metodo de negocio retorna normalmente quando Micrometer esta ausente.
- Falha de Micrometer nao altera excecao/retorno do metodo de negocio.
- Nenhuma metrica automatica usa `userId`, `traceId`, `spanId`, `pedidoId` ou outro identificador de alta cardinalidade como tag.

## Sprint 5 - Separacao de Artefato, Exemplos e Configuracao

### Objetivo

Separar biblioteca principal de exemplos, reduzir configuracoes impostas e preparar o artefato para consumo por outros projetos Quarkus.

### Tasks

- Remover pacotes `br.com.vsjr.labs.example` do artefato principal.
- Criar modulo/projeto de exemplos, se desejado:
  - REST example;
  - exemplos de gauges;
  - exemplos de enriquecedores;
  - quickstart usando a biblioteca como dependencia.
- Revisar `application.properties` da biblioteca:
  - remover configuracoes que simulam aplicacao final, se nao forem necessarias;
  - manter apenas defaults seguros e documentados;
  - evitar impor politica de arquivo em producao.
- Revisar dependencias Maven:
  - mover dependencias exclusivas de exemplos para modulo de exemplos;
  - manter core enxuto.
- Atualizar README:
  - instalacao;
  - uso minimo;
  - uso com `@Logged` + `@Traced`;
  - configuracao de metricas;
  - extensao com `Event`, `Entrypoint`, enriquecedores.
- Atualizar docs para refletir paths reais apos a separacao.

### Entregavel

Artefato principal consumivel como biblioteca, com exemplos separados e documentacao de uso atualizada.

### Checklist

- [ ] `br.com.vsjr.labs.example` nao e empacotado no core.
- [ ] Exemplos estao em modulo/projeto/documentacao separado.
- [ ] Dependencias exclusivas de exemplo nao estao no core.
- [ ] Configuracao default nao age como aplicacao final.
- [ ] README tem guia de consumo.
- [ ] Paths em docs estao atualizados.

### Criterios de Aceite

- Um projeto Quarkus consumidor consegue adicionar a biblioteca como dependencia.
- O artefato principal nao expoe endpoints REST de exemplo.
- O artefato principal nao registra gauges de exemplo automaticamente.
- A documentacao mostra como executar exemplos separados.
- Configuracoes de producao permanecem sob controle da aplicacao consumidora.

## Sprint 6 - Governanca, Testes de Contrato e Qualidade

### Objetivo

Fechar a implementacao com testes de contrato, validacoes de arquitetura e checklist de governanca para evolucao futura.

### Tasks

- Criar suite de testes de contrato da API publica:
  - `Log`;
  - `Event`;
  - `Entrypoint`;
  - `@Logged`;
  - `@Traced`;
  - enriquecedores.
- Criar testes de regressao para padroes proibidos quando viavel:
  - ausencia de `System.out` no core;
  - ausencia de MDC direto fora de gerenciadores autorizados;
  - ausencia de `log_canal`;
  - ausencia de `@Rastreado`, se rename limpo for escolhido.
- Revisar compatibilidade com native image:
  - CDI discovery;
  - reflection desnecessaria;
  - stack walking usado por `.aqui()`.
- Executar validacoes:
  - `mvn test`;
  - build JVM;
  - native build containerizado, se aplicavel.
- Atualizar checklist de code review em `docs/observabilidade.md`.
- Registrar riscos remanescentes e decisoes adiadas para v0.3.

### Entregavel

Versao candidata da biblioteca com API publica estabilizada, testes de contrato e governanca documentada.

### Checklist

- [ ] Suite de testes passa.
- [ ] Testes de contrato cobrem APIs publicas.
- [ ] Testes de arquitetura cobrem padroes proibidos principais.
- [ ] Documentacao e README convergem com implementacao.
- [ ] Build JVM passa.
- [ ] Native build validado ou risco documentado.

### Criterios de Aceite

- `mvn test` passa no modulo da biblioteca.
- Build JVM passa.
- Documentacao nao referencia APIs removidas como contrato atual.
- API publica final esta refletida em README, especificacao e exemplos.
- Checklist de code review cobre logging, tracing, metricas, MDC e dados sensiveis.

## 4. Ordem Recomendada de Execucao

1. Sprint 0: reduzir incerteza de build e testes.
2. Sprint 1: fechar API publica da DSL antes de mexer em consumidores internos.
3. Sprint 2: estabilizar emissao e MDC.
4. Sprint 3: renomear e validar tracing.
5. Sprint 4: fechar metricas.
6. Sprint 5: separar exemplos e limpar artefato.
7. Sprint 6: endurecer qualidade e governanca.

## 5. Riscos e Mitigacoes

| Risco | Impacto | Mitigacao |
|---|---|---|
| Rename de `LOG` para `Log` quebrar exemplos e codigo interno | Medio | Fazer em sprint propria com busca global e testes de compilacao |
| Rename de `@Rastreado` para `@Traced` quebrar consumidores existentes | Alto se houver usuarios externos | Decidir entre rename limpo e alias deprecated antes da Sprint 3 |
| `.aqui()` capturar frame errado | Medio | Testes unitarios com chamadas diretas, indiretas e lambdas simples |
| `Entrypoint` empobrecer detalhes do canal antigo | Baixo/medio | Permitir enums de dominio implementando `Entrypoint`; detalhes especificos via `.comDetalhe()` |
| Configuracao do core impor comportamento a consumidores | Alto | Limpar `application.properties` e documentar responsabilidades da aplicacao |
| Falhas de OTel/Micrometer afetarem negocio | Alto | Testes com mocks/fakes que lancam excecao |
| Exemplos dentro do core virarem comportamento em producao | Medio | Extrair pacote `example` antes de estabilizar versao |

## 6. Definition of Done Global

- API publica documentada e implementada: `Log`, `Event`, `Entrypoint`, `@Logged`, `@Traced`.
- Core compila em Java 21.
- Biblioteca nao e extensao Quarkus.
- Exemplos nao fazem parte do artefato principal.
- `log_entrypoint` substitui `log_canal`.
- `.aqui()` funciona e tem fallback.
- Metricas automaticas sao opcionais e seguras.
- Falhas de observabilidade nao interrompem fluxo de negocio.
- Testes automatizados cobrem os contratos principais.
- README, especificacao e documentacao conceitual estao alinhados com a implementacao.

