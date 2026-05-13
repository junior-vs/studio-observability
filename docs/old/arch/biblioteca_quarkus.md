# Biblioteca de Logging Sistemático — Quarkus 3.27 + Java 21

> **Documentos relacionados**
> - [Padrão de Logging em Aplicações Java](Padrão de Logging em Aplicações Java.md) — fundamentos, 5W1H, padrões proibidos e obrigatórios
> - [Implementação SLF4J + Log4j2](implementacao_slf4j.md) — biblioteca portável para containers Jakarta EE

Esta seção documenta a implementação da biblioteca de logging para aplicações Quarkus 3.27.
Todos os conceitos do padrão 5W1H, Fluent Interface e DSL são preservados. O que muda é
a forma como o Quarkus os expressa: sem bridging de frameworks, sem configuração duplicada,
utilizando exclusivamente o ecossistema nativo da plataforma.

---

## 1. Decisões de Arquitetura

### 1.1. Por que Quarkus exige uma implementação distinta?

A versão SLF4J + Log4j2 é portável entre containers Jakarta EE. O Quarkus, por sua
arquitetura de compilação nativa (GraalVM) e modelo reativo, impõe restrições e oferece
alternativas superiores em cada camada:

**Premissa arquitetural preservada:** independente da implementação, o log é tratado como
um **fluxo *append-only*** — cada evento é acrescentado ao final do fluxo, nunca
modificado retroativamente. Em uma arquitetura de microsserviços com múltiplas instâncias,
esse fluxo é a **fonte da verdade** do que aconteceu no sistema. A agregação centralizada
(ELK, Loki, Datadog) não é opcional: sem ela, os logs de uma única operação distribuída
ficam espalhados em dezenas de processos distintos, tornando o diagnóstico impraticável.

**Princípio editorial:** mensagens de log devem usar linguagem direta e neutra, consistente
com os nomes de domínio do sistema. Jargão informal, abreviações ambíguas ou termos que
dependam de contexto não registrado tornam o log ilegível para quem não escreveu o código —
exatamente as pessoas que o lerão em um incidente.

| Camada | Versão SLF4J | Versão Quarkus 3.27 | Motivo da mudança |
|---|---|---|---|
| Logger | `LoggerFactory.getLogger()` | `@Inject Logger` via JBoss Logging | Logger injetado pelo CDI, otimizado para native image |
| MDC | `org.slf4j.MDC` | `org.jboss.logging.MDC` | Implementação nativa; SLF4J delega para JBoss de qualquer forma |
| JSON | `log4j2.xml` + `JsonTemplateLayout` | `quarkus.log.console.json=true` | Uma linha no `application.properties`, sem XML |
| Contexto HTTP | Spring `@Aspect` + `@Before` | JAX-RS `ContainerRequestFilter` | Padrão Jakarta EE, funciona em native image |
| TraceId | OTel manual no interceptor | `quarkus-opentelemetry` auto-instrumentado | Quarkus injeta o span automaticamente |
| Span customizado | Instrumentação manual ad-hoc | `@Rastreado` + `RastreamentoInterceptor` | CDI interceptor declarativo sobre a API OTel oficial |
| Reatividade | `ThreadLocal` puro | SmallRye Context Propagation | `ThreadLocal` é perdido na troca de thread do Vert.x |
| Métricas | Micrometer standalone | `quarkus-micrometer-registry-prometheus` | Integrado ao `/q/metrics` sem configuração extra |

### 1.2. Features do Java 21 aplicadas

Cada feature é usada onde agrega legibilidade ou segurança de tipos — não por modismo:

| Feature | Onde é usada | Benefício concreto |
|---|---|---|
| `record` | `LogEvento`, `LogContexto` | Imutabilidade e thread-safety sem boilerplate |
| `sealed interface` | `LogEtapas` | O compilador garante que apenas `LogSistematico` implementa as etapas — sem subclasses externas acidentais |
| Pattern matching (`switch`) | `SanitizadorDados`, emissor | Substituição expressiva de cadeias `if-instanceof` |
| Text blocks | Exemplos de JSON nos Javadocs | Legibilidade na documentação inline |
| `var` | Blocos locais no interceptor e filtro | Reduz ruído onde o tipo é óbvio pelo contexto |

---

## 2. Estrutura do Projeto

```
lib-logging-quarkus/
├── pom.xml
└── src/main/
    ├── java/br/com/vsjr/labs/log/
    │   ├── annotations/
    │   │   ├── Logged.java                    ← @InterceptorBinding CDI (logging)
    │   │   └── Rastreado.java                 ← @InterceptorBinding CDI (tracing)
    │   ├── context/
    │   │   ├── EnriquecedorContexto.java      ← Contrato do pipeline MDC (Chain of Responsibility)
    │   │   ├── EnriquecedorLocalizacao.java   ← classe + metodo MDC (prioridade 10)
    │   │   ├── EnriquecedorUsuario.java        ← usuario.perfis MDC (prioridade 20)
    │   │   ├── GerenciadorContextoLog.java    ← MDC + pipeline de enriquecimento
    │   │   ├── LogContexto.java               ← Record: snapshot imutável do MDC
    │   │   └── SanitizadorDados.java          ← Mascaramento LGPD-compliant
    │   ├── core/
    │   │   └── LogEvento.java                 ← Record 5W1H imutável
    │   ├── dsl/
    │   │   ├── LogEtapas.java                 ← sealed interfaces da Fluent Interface
    │   │   └── LogSistematico.java            ← Ponto de entrada público da DSL
    │   ├── filtro/
    │   │   └── LogContextoFiltro.java         ← ContainerRequestFilter JAX-RS
    │   ├── interceptor/
    │   │   ├── LogInterceptor.java            ← CDI @AroundInvoke (pipeline MDC)
    │   │   └── RastreamentoInterceptor.java   ← CDI @AroundInvoke (OTel Tracer)
    │   └── tracing/
    │       ├── EnriquecedorSpan.java           ← Contrato do pipeline de spans
    │       ├── EnriquecedorMetadados.java      ← Metadados técnicos (prioridade 10)
    │       ├── EnriquecedorIdentidade.java     ← Identidade do usuário (prioridade 20)
    │       └── GerenciadorRastreamento.java    ← Ciclo de vida do Span + MDC sync
    ├── java/br/com/vsjr/labs/exemple/
    │   ├── context/
    │   │   └── EnriquecedorContextoOperacao.java  ← Exemplo: enriquecedor MDC de negócio (prioridade 100)
    │   ├── rest/
    │   │   ├── HelloService.java                  ← Exemplo: serviço com todas as features DSL
    │   │   └── HellowrdResource.java              ← Exemplo: recurso REST com @Logged/@Rastreado
    │   └── tracing/
    │       └── EnriquecedorOperacao.java           ← Exemplo: enriquecedor de span de negócio
    └── resources/
        └── application.properties             ← Configuração JSON + níveis + OTLP
```

---

## 3. Dependências Maven

```xml
<!-- pom.xml -->
<properties>
    <quarkus.platform.version>3.27.0</quarkus.platform.version>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.quarkus.platform</groupId>
            <artifactId>quarkus-bom</artifactId>
            <version>${quarkus.platform.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Runtime base do Quarkus (CDI + RESTEasy Reactive) -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-resteasy-reactive</artifactId>
    </dependency>

    <!--
        JSON estruturado nativo.
        Todos os campos do MDC viram chaves de primeiro nível no objeto JSON.
        Ativado por quarkus.log.console.json=true no application.properties.
    -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-logging-json</artifactId>
    </dependency>

    <!--
        OpenTelemetry: auto-instrumentação HTTP, traceId/spanId reais.
        O Quarkus injeta o span no contexto automaticamente — sem código manual.
    -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-opentelemetry</artifactId>
    </dependency>

    <!--
        Micrometer + Prometheus: métricas de duração expostas em /q/metrics.
        MeterRegistry é injetável via CDI sem configuração extra.
    -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
    </dependency>

    <!--
        Context Propagation: garante que MDC e OTel span sobrevivam à troca
        de thread do Vert.x em pipelines Mutiny (Uni / Multi).
        Obrigatório em qualquer aplicação reativa.
    -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-smallrye-context-propagation</artifactId>
    </dependency>

    <!--
        Segurança: SecurityIdentity injetável via CDI.
        Substitui o SecurityContextHolder do Spring sem acoplamento estático.
    -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-security</artifactId>
    </dependency>

    <!-- JWT: decodificação e validação de tokens Bearer -->
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-smallrye-jwt</artifactId>
    </dependency>
</dependencies>
```

---

## 4. Configuração (`application.properties`)

```properties
# ─── Logging JSON ─────────────────────────────────────────────────────────────
# Ativa saída JSON nativa. Todos os campos do MDC viram chaves no JSON.
quarkus.log.console.json=true

# Nível global: INFO em produção.
# DEBUG ativável por pacote sem reinicialização (via /q/logging-manager se habilitado).
quarkus.log.level=INFO

# DEBUG seletivo por pacote — sem afetar toda a aplicação
quarkus.log.category."br.com.vsjr.labs.pedidos".level=DEBUG

# ─── OpenTelemetry ────────────────────────────────────────────────────────────
# O Quarkus auto-instrumenta chamadas HTTP e injeta traceId/spanId no MDC.
quarkus.otel.exporter.otlp.endpoint=http://jaeger:4317

# ─── Micrometer / Prometheus ──────────────────────────────────────────────────
# Métricas de duração de métodos expostas em /q/metrics
quarkus.micrometer.export.prometheus.enabled=true

# ─── Context Propagation (Reativo) ────────────────────────────────────────────
# Garante que MDC e span OTel sobrevivam à troca de thread do Vert.x
quarkus.arc.context-propagation.mdc=true
```

### 4.1. Tabela de Níveis de Severidade

A escolha do nível deve ser determinística — baseada no impacto sobre o estado do sistema,
não em julgamento subjetivo:

| Nível | Quando usar | Habilitado em produção? |
|---|---|---|
| `TRACE` | Diagnóstico de baixo nível: entradas/saídas de métodos, iterações, valores intermediários detalhados | Nunca — apenas em desenvolvimento local |
| `DEBUG` | Fluxos internos, decisões condicionais, dados intermediários sem alteração de estado | Não por padrão — ativável por pacote sem reinicialização |
| `INFO` | Operações que alteram estado: persistência, autenticação, chamadas externas | Sempre |
| `WARN` | Situações anômalas recuperáveis: tentativas de acesso indevido, *fallbacks* ativados | Sempre |
| `ERROR` | Falhas reais: exceção que impede o cumprimento do contrato da operação | Sempre |
| `FATAL` | Falhas que tornam a aplicação incapaz de continuar — exigem intervenção imediata | Sempre |

**Ativação dinâmica de DEBUG:** com `quarkus-logging-manager`, o nível pode ser alterado
por pacote em tempo de execução via `/q/logging-manager` — sem reinicialização e sem afetar
o volume global de logs.

### 4.2. Requisito de Sincronização de Tempo (NTP + UTC)

Em uma arquitetura de microsserviços, desvios de relógio entre servidores tornam a ordenação
cronológica de eventos impossível, mesmo com logs em JSON. O timestamp de um evento em
`pedidos-service` e o timestamp de um evento correlacionado em `pagamentos-service` devem
ser comparáveis para que a reconstrução da sequência de causa e efeito funcione.

**Requisito obrigatório de infraestrutura:** todos os servidores e containers que geram
logs devem estar sincronizados ao **UTC** via **NTP**. Sem essa sincronização, o `traceId`
correlaciona os logs pela identidade, mas não garante ordenação cronológica confiável entre
serviços.

A configuração do `quarkus.log.console.json=true` já garante que os timestamps sejam
emitidos em UTC com precisão de milissegundos — o que é necessário mas não suficiente sem
a sincronização NTP na infraestrutura.

### 4.3. Transporte Seguro de Logs (SSL/TLS)

Mascarar dados sensíveis na aplicação não é suficiente se o canal de transporte não estiver
protegido. Logs transmitidos em texto claro entre o container e o coletor (Fluentd, Logstash,
OTel Collector) podem expor dados de contexto não mascarados — como `userId`,
`traceId`, `spanId` e nomes de entidades — a qualquer observador de rede.

**Requisito:** o transporte de logs entre nós da rede deve usar **SSL/TLS**, garantindo
confidencialidade e autenticidade do fluxo. Isso se aplica ao canal entre a aplicação e o
coletor de logs, e entre o coletor e o backend de armazenamento (ELK, Loki, Datadog).

---

## 5. Código-Fonte

### 5.1. `SanitizadorDados` — Mascaramento LGPD-Compliant

Usa *pattern matching* com `switch` do Java 21 para categorizar o nível de
mascaramento por tipo de dado, em vez de uma simples verificação booleana.
Dados parcialmente identificáveis recebem tratamento diferente de credenciais.

**Distinção entre mascaramento e redação:**

- **Mascaramento:** o valor é substituído por uma representação que confirma presença sem expor o conteúdo (`"****"` para credenciais, `"[PROTEGIDO]"` para dados pessoais). Preserva a evidência de que o campo foi fornecido — útil para diagnóstico e conformidade.
- **Redação:** o valor é completamente removido do registro — o campo não aparece no JSON. Indicado quando nem a confirmação de presença pode ser registrada (ex: dados sob sigilo legal, informações de menores). **Não implementado nesta versão — campos que exigem redação completa devem ser omitidos antes de chamar `.comDetalhe()`.**

Em caso de dúvida sobre qual técnica aplicar, prefira a redação (omissão do campo).

```java
package br.com.vsjr.labs.log.context;

import java.util.Set;

/**
 * Mascara valores associados a chaves sensíveis antes do registro.
 *
 * <p>Aplica três graus de tratamento segundo o princípio de <em>data minimization</em>:</p>
 * <ul>
 *   <li><b>Credenciais</b> (senha, token): substituídas por {@code "****"}</li>
 *   <li><b>Dados pessoais</b> (CPF, e-mail): substituídos por {@code "[PROTEGIDO]"}</li>
 *   <li><b>Demais valores</b>: registrados normalmente</li>
 * </ul>
 *
 * <p>Usa <em>pattern matching</em> com {@code switch} do Java 21 para
 * distinguir categorias sem cadeia de {@code if-else}.</p>
 */
public final class SanitizadorDados {

    private static final Set<String> CHAVES_CREDENCIAIS = Set.of(
            "password", "senha", "secret",
            "token", "accesstoken", "refreshtoken",
            "authorization", "apikey", "cvv"
    );

    private static final Set<String> CHAVES_DADOS_PESSOAIS = Set.of(
            "cpf", "rg", "email", "celular",
            "cardnumber", "numerocartao"
    );

    private SanitizadorDados() {}

    /**
     * Retorna o valor sanitizado conforme a sensibilidade da chave.
     *
     * @param chave nome do campo
     * @param valor valor original
     * @return valor seguro para registro em log
     */
    public static Object sanitizar(String chave, Object valor) {
        if (valor == null) return null;

        // Java 21: pattern matching com switch — categoriza por tipo de sensibilidade
        return switch (classificar(chave)) {
            case CREDENCIAL    -> "****";
            case DADO_PESSOAL  -> "[PROTEGIDO]";
            case PUBLICO       -> valor;
        };
    }

    // ── Tipos de sensibilidade ────────────────────────────────────────────────

    private enum Sensibilidade { CREDENCIAL, DADO_PESSOAL, PUBLICO }

    private static Sensibilidade classificar(String chave) {
        var chaveLower = chave.toLowerCase();
        if (CHAVES_CREDENCIAIS.contains(chaveLower))   return Sensibilidade.CREDENCIAL;
        if (CHAVES_DADOS_PESSOAIS.contains(chaveLower)) return Sensibilidade.DADO_PESSOAL;
        return Sensibilidade.PUBLICO;
    }
}
```

---

### 5.2. `LogContexto` — Snapshot Imutável do MDC

Um `record` que representa o contexto de correlação de uma requisição. Imutável
por definição — pode ser passado entre *threads* sem sincronização.

```java
package br.com.vsjr.labs.log.context;

/**
 * Snapshot imutável do contexto de correlação de uma requisição.
 *
 * <p>Produzido pelo {@link GerenciadorContextoLog} a partir da identidade
 * autenticada e do nome da aplicação. Pode ser inspecionado em testes sem
 * dependência de infraestrutura de MDC.</p>
 *
 * <p>Este record pertence exclusivamente à camada de logging. Dados de rastreamento
 * distribuído ({@code traceId}, {@code spanId}) são responsabilidade do
 * {@code GerenciadorRastreamento}.</p>
 *
 * <p>Usa {@code record} do Java 21: imutável, thread-safe e com
 * {@code equals/hashCode/toString} gerados sem boilerplate.</p>
 *
 * @param userId           identificador do usuário autenticado; {@code null} é coercido
 *                         para {@code "anonimo"}
 * @param applicationName  nome do microsserviço ({@code quarkus.application.name})
 */
public record LogContexto(
        String userId,
        String applicationName
) {
    /** Contexto vazio: usado quando nenhuma requisição está ativa (ex: testes, jobs). */
    public static final LogContexto VAZIO = new LogContexto("anonimo", "desconhecido");
}
```

---

### 5.3. `GerenciadorContextoLog` — MDC e Pipeline de Enriquecimento

Gerencia o ciclo de vida do MDC para a camada de logging. Delega o enriquecimento
do contexto ao pipeline de `EnriquecedorContexto` (Chain of Responsibility) —
cada enriquecedor adiciona campos ao MDC em ordem crescente de prioridade, e todas
as suas chaves são removidas por `limparEnriquecimento()` no `finally` do interceptor.
O tratamento de `traceId` e `spanId` é responsabilidade exclusiva do
`GerenciadorRastreamento`, mantendo a separação de responsabilidades entre as duas
camadas.

```java
package br.com.vsjr.labs.log.context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.MDC;

import java.util.Comparator;

/**
 * Gerencia o ciclo de vida do MDC para a camada de logging.
 *
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Propagar {@code userId} e nome do serviço para o MDC</li>
 *   <li>Executar o pipeline de enriquecimento de contexto ({@link EnriquecedorContexto})</li>
 *   <li>Garantir limpeza segura após execução</li>
 *   <li>Produzir {@link LogContexto} inspecionável (útil em testes)</li>
 * </ul>
 *
 * <p>Este bean é responsável exclusivamente pelo contexto de logging.
 * Dados de rastreamento distribuído ({@code traceId}, {@code spanId})
 * são gerenciados pelo {@link br.com.vsjr.labs.log.tracing.GerenciadorRastreamento}.</p>
 */
@ApplicationScoped
public class GerenciadorContextoLog {

    String applicationName;
    Instance<EnriquecedorContexto> enriquecedores;

    /**
     * Construtor CDI: recebe o nome da aplicação e o conjunto de enriquecedores disponíveis.
     *
     * @param applicationName nome do microsserviço, lido de {@code quarkus.application.name};
     *                        padrão {@code "aplicacao-desconhecido"}
     * @param enriquecedores  todos os beans {@link EnriquecedorContexto} descobertos via CDI
     */
    public GerenciadorContextoLog(
            @ConfigProperty(name = "quarkus.application.name", defaultValue = "aplicacao-desconhecido") String applicationName,
            Instance<EnriquecedorContexto> enriquecedores) {
        this.applicationName = applicationName;
        this.enriquecedores = enriquecedores;
    }

    private static final String CAMPO_USER_ID    = "userId";
    private static final String CAMPO_APPLICATION = "aplicacao";

    /**
     * Inicializa o MDC com o contexto de identificação da requisição atual.
     *
     * <p>Popula as chaves MDC {@code userId} e {@code aplicacao}. Os campos de rastreamento
     * distribuído ({@code traceId}, {@code spanId}) são responsabilidade do
     * {@code GerenciadorRastreamento}, que deve ser chamado na mesma fase de filtro.</p>
     *
     * @param userId identificador do usuário autenticado; {@code null} é coercido para
     *               {@code "anonimo"}
     * @return snapshot imutável do contexto registrado no MDC
     */
    public LogContexto inicializar(String userId) {
        var uid = userId != null ? userId : "anonimo";
        MDC.put(CAMPO_USER_ID, uid);
        MDC.put(CAMPO_APPLICATION, applicationName);
        return new LogContexto(uid, applicationName);
    }

    /**
     * Executa o pipeline de enriquecimento do contexto de logging.
     *
     * <p>Cada {@link EnriquecedorContexto} descoberto via CDI é executado em ordem
     * crescente de {@link EnriquecedorContexto#prioridade()}, adicionando campos ao MDC.
     * Deve ser chamado no início do bloco interceptado; {@link #limparEnriquecimento()}
     * deve ser chamado no {@code finally} correspondente.</p>
     *
     * @param contexto contexto CDI da invocação
     */
    public void enriquecer(InvocationContext contexto) {
        enriquecedores.stream()
                .sorted(Comparator.comparingInt(EnriquecedorContexto::prioridade))
                .forEach(e -> e.enriquecer(contexto));
    }

    /**
     * Remove do MDC todas as chaves gerenciadas pelo pipeline de enriquecimento.
     *
     * <p>Deve ser chamado em bloco {@code finally} após {@link #enriquecer(InvocationContext)}
     * para garantir que campos de localização não contaminem logs subsequentes
     * na mesma thread.</p>
     */
    public void limparEnriquecimento() {
        enriquecedores.stream()
                .flatMap(e -> e.chavesMdc().stream())
                .forEach(MDC::remove);
    }

    /**
     * Remove todos os campos do MDC.
     *
     * <p>Deve sempre ser chamado em bloco {@code finally} para evitar
     * vazamento de contexto entre threads no pool do Vert.x.</p>
     */
    public void limpar() {
        MDC.clear();
    }
}


---

### 5.4. `LogEvento` — Modelo de Dados 5W1H

`record` imutável que transporta o evento do *builder* da DSL até o emissor.
Os Javadocs usam *text blocks* do Java 21 para exemplificar o JSON gerado.

```java
package br.com.vsjr.labs.log.core;

import java.util.Collections;
import java.util.Map;

/**
 * Representação imutável de um evento de log segundo o framework 5W1H.
 *
 * <p>Mapeamento das dimensões:</p>
 * <ul>
 *   <li>{@code evento}  → <b>What</b>: o que aconteceu</li>
 *   <li>{@code classe}  → <b>Where</b>: localização técnica</li>
 *   <li>{@code metodo}  → <b>Where</b>: método específico</li>
 *   <li>{@code motivo}  → <b>Why</b>: causa de negócio (opcional)</li>
 *   <li>{@code canal}   → <b>How</b>: canal de origem (opcional)</li>
 *   <li>{@code detalhes}→ contexto adicional tipado</li>
 * </ul>
 *
 * <p>As dimensões <b>Who</b> (userId) e <b>When</b> (timestamp) são preenchidas
 * automaticamente: Who pelo {@link br.com.vsjr.labs.log.filtro.LogContextoFiltro}
 * via MDC, e When pelo Quarkus no momento da emissão.</p>
 *
 * <p>Exemplo de saída JSON completa:</p>
 * <pre>{@code
 * {
 *   "timestamp":        "2026-03-11T21:55:00.123Z",
 *   "level":            "INFO",
 *   "message":          "Pedido criado",
 *   "traceId":          "4bf92f3577b34da6a3ce929d0e0e4736",
 *   "spanId":           "a3ce929d0e0e4736",
 *   "userId":           "joao.silva@empresa.com",
 *   "aplicacao":        "pedidos-service",
 *   "classe":           "PedidoService",
 *   "metodo":           "criar",
 *   "log_classe":       "PedidoService",
 *   "log_metodo":       "criar",
 *   "log_motivo":       "Solicitação do cliente via checkout",
 *   "log_canal":        "API REST — POST /pedidos",
 *   "detalhe_pedidoId": "4821",
 *   "detalhe_valor":    "349.90"
 * }
 * }</pre>
 *
 * @param evento   descrição do evento (What)
 * @param classe   nome simples da classe (Where)
 * @param metodo   nome do método (Where)
 * @param motivo   causa de negócio (Why) — pode ser {@code null}
 * @param canal    canal de origem (How) — pode ser {@code null}
 * @param detalhes mapa ordenado de contexto adicional — nunca {@code null}
 */
public record LogEvento(
        String evento,
        String classe,
        String metodo,
        String motivo,
        String canal,
        Map<String, Object> detalhes
) {
    /**
     * Garante imutabilidade do mapa de detalhes independente do que for passado.
     * {@code Collections.unmodifiableMap} preserva a ordem de inserção original.
     */
    public LogEvento {
        detalhes = detalhes != null
                ? Collections.unmodifiableMap(detalhes)
                : Map.of();
    }
}
```

---

### 5.5. `LogEtapas` — Sealed Interfaces da Fluent Interface

`sealed interface` do Java 21 garante que apenas `LogSistematico` possa
implementar as etapas. O compilador rejeita qualquer tentativa de criar
implementações externas, tornando o contrato da DSL fechado e previsível.

```java
package br.com.vsjr.labs.log.dsl;

import org.jboss.logging.Logger;

/**
 * Define as etapas da Fluent Interface da DSL de logging sistemático.
 *
 * <p>Usa {@code sealed interface} do Java 21: apenas {@link LogSistematico}
 * pode implementar essas interfaces, garantindo que o contrato da DSL
 * não possa ser alterado ou estendido acidentalmente por código externo.</p>
 *
 * <p>Fluxo de chamadas com validação em tempo de compilação:</p>
 * <pre>
 *   LogSistematico
 *     .registrando(evento)           // What  — obrigatório, retorna EtapaOnde
 *     .em(classe, metodo)            // Where — obrigatório, retorna EtapaOpcional
 *     [ .porque(motivo)         ]    // Why   — opcional
 *     [ .como(canal)            ]    // How   — opcional
 *     [ .comDetalhe(chave, val) ]*   // extra — zero ou mais chamadas
 *     .info() | .debug() | .warn() | .erro(ex)
 * </pre>
 *
 * <p>O compilador impede chamar {@code .info()} sem passar por
 * {@code .registrando()} e {@code .em()} — logs incompletos são
 * erros de compilação, não bugs em produção.</p>
 */
public final class LogEtapas {

    private LogEtapas() {}

    /**
     * Etapa 1 — What capturado.
     * Exige a declaração do Where antes de qualquer outra operação.
     */
    public sealed interface EtapaOnde permits LogSistematico {

        /**
         * Declara o Where: localização técnica do evento no código.
         *
         * @param classe referência da classe — evita strings hard-coded
         * @param metodo nome do método
         */
        EtapaOpcional em(Class<?> classe, String metodo);
    }

    /**
     * Etapa 2 — Where preenchido.
     * O log pode ser emitido ou enriquecido com qualquer combinação de
     * dimensões opcionais antes do terminador.
     */
    public sealed interface EtapaOpcional permits LogSistematico {

        /** Why: motivo ou causa de negócio do evento. */
        EtapaOpcional porque(String motivo);

        /** How: canal ou mecanismo pelo qual o evento chegou ao sistema. */
        EtapaOpcional como(String canal);

        /**
         * Contexto extra em chave-valor.
         * Valores de chaves sensíveis são mascarados automaticamente pelo
         * {@link br.com.vsjr.labs.log.context.SanitizadorDados}.
         * Pode ser chamado múltiplas vezes — a ordem de inserção é preservada.
         *
         * @param chave nome do campo no JSON de saída
         * @param valor valor a registrar (mascarado se sensível)
         */
        EtapaOpcional comDetalhe(String chave, Object valor);

        // ── Terminadores ─────────────────────────────────────────────────────

        /**
         * Emite em nível INFO.
         * Usar para operações que alteram estado: persistência, chamadas externas,
         * autenticação. Sempre habilitado em produção.
         */
        void info();

        /**
         * Emite em nível DEBUG.
         * Usar para fluxos internos sem alteração de estado: validações descartadas,
         * buscas sem persistência. Desabilitado em produção por padrão.
         */
        void debug();

        /**
         * Emite em nível WARN.
         * Usar para situações anômalas recuperáveis: tentativas de acesso indevido,
         * fallbacks ativados, rate limits atingidos.
         */
        void warn();

        /**
         * Emite em nível ERROR com a exceção associada.
         * O stack trace é capturado e serializado automaticamente pelo Quarkus.
         * Usar apenas quando a operação não pôde cumprir seu contrato.
         *
         * @param causa exceção que motivou o erro
         */
        void erro(Throwable causa);

        /**
         * Emite em nível ERROR e relança a exceção em uma única chamada.
         * Útil em lambdas e streams onde a exceção não pode ser engolida.
         *
         * @param causa exceção a registrar e relançar
         * @param <T>   tipo da exceção (preserva o tipo checado para o compilador)
         * @throws T sempre — a linha após esta chamada é inalcançável
         */
        <T extends Throwable> void erroERelanca(T causa) throws T;
    }
}
```

---

### 5.6. `LogSistematico` — Implementação da DSL

Ponto de entrada público da biblioteca. `permits` nas `sealed interfaces`
restringe a implementação exclusivamente a esta classe.

O logger é obtido por `Logger.getLogger(classe)` — a forma idiomática do
Quarkus, que integra com o sistema de logging nativo sem bridging adicional.

```java
package br.com.vsjr.labs.log.dsl;

import br.com.vsjr.labs.log.context.SanitizadorDados;
import br.com.vsjr.labs.log.core.LogEvento;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.LinkedHashMap;

/**
 * Ponto de entrada público da DSL de logging sistemático para Quarkus.
 *
 * <p>A DSL é construída sobre {@code sealed interfaces} do Java 21:
 * o compilador valida a sequência de chamadas, tornando logs incompletos
 * erros de compilação em vez de bugs silenciosos em produção.</p>
 *
 * <p><b>Uso mínimo obrigatório (What + Where):</b></p>
 * <pre>{@code
 * LogSistematico
 *     .registrando("Pedido criado")
 *     .em(PedidoService.class, "criar")
 *     .info();
 * }</pre>
 *
 * <p><b>Uso completo com todas as dimensões do 5W1H:</b></p>
 * <pre>{@code
 * LogSistematico
 *     .registrando("Pagamento recusado")
 *     .em(PagamentoService.class, "processar")
 *     .porque("Saldo insuficiente no gateway")
 *     .como("API REST — POST /pagamentos")
 *     .comDetalhe("pedidoId",   pedido.getId())
 *     .comDetalhe("valor",      pedido.getValor())
 *     .comDetalhe("token",      request.token())  // ← mascarado: "****"
 *     .erro(excecao);
 * }</pre>
 *
 * <p><b>Nota sobre o logger:</b> o logger é criado por {@code Logger.getLogger(classe)},
 * que é o padrão idiomático do Quarkus (JBoss Logging). Isso garante compatibilidade
 * com {@code quarkus-logging-json} e com native image sem adaptadores externos.</p>
 */
public final class LogSistematico
        implements LogEtapas.EtapaOnde, LogEtapas.EtapaOpcional {

    private String evento;
    private Class<?> classeAlvo;
    private String metodo;
    private String motivo;
    private String canal;
    // LinkedHashMap: preserva a ordem de inserção dos detalhes no JSON de saída
    private final LinkedHashMap<String, Object> detalhes = new LinkedHashMap<>();

    private LogSistematico() {}

    // ── Ponto de entrada — What ───────────────────────────────────────────────

    /**
     * Inicia a construção do log com a descrição do evento (dimensão <em>What</em>).
     *
     * @param evento o que está acontecendo — ex: "Pedido criado", "Login falhou"
     * @return etapa seguinte, que exige a declaração do Where
     */
    public static LogEtapas.EtapaOnde registrando(String evento) {
        var builder = new LogSistematico();
        builder.evento = evento;
        return builder;
    }

    // ── Etapa obrigatória — Where ─────────────────────────────────────────────

    @Override
    public LogEtapas.EtapaOpcional em(Class<?> classe, String metodo) {
        this.classeAlvo = classe;
        this.metodo     = metodo;
        return this;
    }

    // ── Etapas opcionais ──────────────────────────────────────────────────────

    @Override
    public LogEtapas.EtapaOpcional porque(String motivo) {
        this.motivo = motivo;
        return this;
    }

    @Override
    public LogEtapas.EtapaOpcional como(String canal) {
        this.canal = canal;
        return this;
    }

    @Override
    public LogEtapas.EtapaOpcional comDetalhe(String chave, Object valor) {
        // Sanitização automática: credenciais e dados pessoais são mascarados aqui
        detalhes.put(chave, SanitizadorDados.sanitizar(chave, valor));
        return this;
    }

    // ── Terminadores ──────────────────────────────────────────────────────────

    @Override
    public void info() {
        emitir(Logger.Level.INFO, null);
    }

    @Override
    public void debug() {
        emitir(Logger.Level.DEBUG, null);
    }

    @Override
    public void warn() {
        emitir(Logger.Level.WARN, null);
    }

    @Override
    public void erro(Throwable causa) {
        emitir(Logger.Level.ERROR, causa);
    }

    @Override
    public <T extends Throwable> void erroERelanca(T causa) throws T {
        emitir(Logger.Level.ERROR, causa);
        throw causa;
    }

    // ── Emissão ───────────────────────────────────────────────────────────────

    /**
     * Monta o {@link LogEvento} e o emite via JBoss Logging.
     *
     * <p>As dimensões estruturais (classe, método, motivo, canal) são inseridas
     * no MDC imediatamente antes da emissão e removidas logo após — garantindo
     * que campos do evento atual não contaminem logs subsequentes na mesma thread.</p>
     *
     * <p>Os detalhes de negócio são prefixados com {@code "detalhe_"} para
     * diferenciá-los dos campos de infraestrutura no JSON de saída.</p>
     */
    private void emitir(Logger.Level level, Throwable causa) {
        var logger = Logger.getLogger(classeAlvo);
        if (!logger.isEnabled(level)) return;

        var nomeClasse = classeAlvo.getSimpleName();

        // Popula MDC com dimensões estruturais do evento
        MDC.put("log_classe", nomeClasse);
        MDC.put("log_metodo", metodo);
        if (motivo != null) MDC.put("log_motivo", motivo);
        if (canal  != null) MDC.put("log_canal",  canal);

        // Detalhes de negócio: cada entrada vira um campo JSON de primeiro nível
        detalhes.forEach((chave, valor) ->
                MDC.put("detalhe_" + chave, valor != null ? valor.toString() : "null")
        );

        try {
            // Emissão via JBoss Logging — integração nativa com quarkus-logging-json
            if (causa != null) {
                logger.log(level, evento, causa);
            } else {
                logger.log(level, evento);
            }
        } finally {
            // Limpeza dos campos do evento: não remove o contexto da requisição
            // (traceId, userId), que é responsabilidade do GerenciadorContextoLog
            MDC.remove("log_classe");
            MDC.remove("log_metodo");
            MDC.remove("log_motivo");
            MDC.remove("log_canal");
            detalhes.keySet().forEach(chave -> MDC.remove("detalhe_" + chave));
        }
    }
}
```

---

### 5.7. `@Logged` — Anotação CDI

```java
package br.com.vsjr.labs.log.annotations;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.*;

/**
 * Ativa interceptação automática de logging para um bean ou método CDI.
 *
 * <p>Quando aplicada, o {@link br.com.vsjr.labs.log.interceptor.LogInterceptor}
 * injeta no MDC os campos de localização técnica ({@code classe}, {@code metodo}) e
 * os campos de negócio de enriquecedores registrados via CDI. Ao término, todos os
 * campos adicionados pelo pipeline são removidos do MDC automaticamente.</p>
 *
 * <p>Pode ser aplicada na classe (todos os métodos) ou em métodos específicos:</p>
 *
 * <pre>{@code
 * // Toda a classe
 * @ApplicationScoped
 * @Logged
 * public class PedidoService { ... }
 *
 * // Apenas um método
 * @ApplicationScoped
 * public class RelatorioService {
 *
 *     @Logged
 *     public Relatorio gerar(Long id) { ... }
 * }
 * }</pre>
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Logged {
}
```

---

### 5.8. `LogContextoFiltro` — Contexto HTTP via JAX-RS

Popula o MDC no início de cada requisição HTTP e o limpa na resposta.
Usa `ContainerRequestFilter` — o mecanismo nativo do JAX-RS para lógica
transversal, sem o `@Aspect` do Spring.

A identidade do usuário é obtida via `SecurityContext` do JAX-RS, compatível
com qualquer mecanismo de autenticação configurado no Quarkus (JWT, OIDC, Basic).

O filtro popula o MDC com `traceId`, `spanId` (via OpenTelemetry) e `userId` —
os três identificadores de correlação do padrão. O `spanId` é o do Root Span
da requisição HTTP; ele será atualizado para o span filho quando o
`RastreamentoInterceptor` criar um Child Span para o método de negócio.

```java
package br.com.vsjr.labs.log.filtro;

import br.com.vsjr.labs.log.context.GerenciadorContextoLog;
import br.com.vsjr.labs.log.dsl.LogSistematico;
import br.com.vsjr.labs.log.tracing.GerenciadorRastreamento;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

import java.security.Principal;

/**
 * Filtro JAX-RS que gerencia o contexto de logging para requisições HTTP.
 *
 * <p>{@code @Provider} registra automaticamente o filtro no runtime do Quarkus —
 * sem necessidade de declaração em XML ou configuração adicional.</p>
 *
 * <p>Executa em duas fases:</p>
 * <ol>
 *   <li><b>Request</b>: extrai o usuário autenticado e inicializa o MDC com
 *       {@code userId}, {@code traceId} e {@code spanId} do span OTel ativo.</li>
 *   <li><b>Response</b>: limpa o MDC — obrigatório para evitar vazamento de
 *       contexto entre requisições no pool de threads do Vert.x.</li>
 * </ol>
 *
 * <p>Para ambientes reativos (RESTEasy Reactive + Mutiny), o MDC e o span OTel
 * são propagados automaticamente pelo SmallRye Context Propagation — habilitado
 * via {@code quarkus-smallrye-context-propagation} no pom.xml.</p>
 */
@Provider
public class LogContextoFiltro implements ContainerRequestFilter, ContainerResponseFilter {

    GerenciadorContextoLog gerenciador;
    GerenciadorRastreamento gerenciadorRastreamento;

    public LogContextoFiltro(GerenciadorContextoLog gerenciador,
                              GerenciadorRastreamento gerenciadorRastreamento) {
        this.gerenciador = gerenciador;
        this.gerenciadorRastreamento = gerenciadorRastreamento;
    }

    /**
     * Fase de requisição: inicializa o MDC antes de qualquer código de negócio.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        var userId = resolverUsuario(requestContext);
        var contexto = gerenciador.inicializar(userId);
        gerenciadorRastreamento.sincronizarMdcRequisicao();

        LogSistematico
                .registrando("Contexto de log inicializado")
                .em(LogContextoFiltro.class, "filter")
                .comDetalhe("userId", contexto.userId())
                .debug();
    }

    /**
     * Fase de resposta: limpa o MDC independente de sucesso ou exceção.
     */
    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        gerenciador.limpar();
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    private String resolverUsuario(ContainerRequestContext requestContext) {
        return switch (requestContext.getSecurityContext()) {
            case null -> "anonimo";
            case SecurityContext sc when sc.getUserPrincipal() instanceof Principal p
                    && p.getName() != null -> p.getName();
            default -> "anonimo";
        };
    }
}
```

---

### 5.9. `LogInterceptor` — CDI Interceptor de Logging

Intercepta métodos anotados com `@Logged` e delega o enriquecimento e limpeza do
contexto de logging ao `GerenciadorContextoLog`. * A responsabilidade de métricas
de duração está planejada para um módulo separado, preservando a responsabilidade
única deste interceptor.

```java
package br.com.vsjr.labs.log.interceptor;

import br.com.vsjr.labs.log.annotations.Logged;
import br.com.vsjr.labs.log.context.GerenciadorContextoLog;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * CDI Interceptor ativado por {@link Logged}.
 *
 * <p>Para cada método interceptado:</p>
 * <ol>
 *   <li>Executa o pipeline de enriquecimento do contexto de logging
 *       ({@code Chain of Responsibility}) — cada {@code EnriquecedorContexto}
 *       CDI adiciona campos ao MDC em ordem crescente de prioridade</li>
 *   <li>Executa o método de negócio</li>
 *   <li>Remove do MDC as chaves inseridas pelo pipeline no {@code finally}</li>
 * </ol>
 *
 * <p>Os campos de correlação da requisição ({@code traceId}, {@code userId})
 * são responsabilidade do {@link br.com.vsjr.labs.log.filtro.LogContextoFiltro} e
 * permanecem intactos durante toda a execução da requisição.</p>
 */
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LogInterceptor {

    GerenciadorContextoLog gerenciador;

    public LogInterceptor(GerenciadorContextoLog gerenciador) {
        this.gerenciador = gerenciador;
    }

    @AroundInvoke
    public Object interceptar(InvocationContext contexto) throws Exception {
        gerenciador.enriquecer(contexto);
        try {
            return contexto.proceed();
        } finally {
            gerenciador.limparEnriquecimento();
        }
    }
}

```

---

### 5.10. `@Rastreado` — Anotação CDI de Tracing

```java
package br.com.vsjr.labs.log.annotations;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.*;

/**
 * Ativa rastreamento distribuído para um bean ou método CDI.
 *
 * <p>Quando aplicada, o {@link br.com.vsjr.labs.log.interceptor.RastreamentoInterceptor}
 * cria um {@code Child Span} no span OTel ativo, registra metadados da operação
 * (classe, método, hora de início/fim) e propaga o {@code spanId} atualizado
 * para o MDC — mantendo a correlação com as linhas de log emitidas dentro
 * do método.</p>
 *
 * <p>Pode ser combinada com {@link Logged} no mesmo bean sem conflito:
 * {@code @Logged} gerencia o MDC de logging; {@code @Rastreado} gerencia
 * o span OTel. Quando usadas juntas, a ordem de execução é controlada
 * por {@code @Priority}: {@code RastreamentoInterceptor} executa primeiro
 * (cria o span), depois {@code LogInterceptor} (registra localização no MDC).</p>
 *
 * <pre>{@code
 * // Apenas tracing
 * @ApplicationScoped
 * @Rastreado
 * public class IntegracaoFiscalClient { ... }
 *
 * // Tracing + Logging — interceptors acumulados
 * @ApplicationScoped
 * @Logged
 * @Rastreado
 * public class PagamentoService { ... }
 * }</pre>
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Rastreado {
}
```

---

### 5.11. `GerenciadorRastreamento` — Ciclo de Vida do Span

Centraliza a criação, enriquecimento e encerramento de spans OTel.
Separa a lógica de rastreamento do interceptor, tornando cada
responsabilidade testável de forma isolada. Implementa o padrão
**Chain of Responsibility** via `Instance<EnriquecedorSpan>`: novos
enriquecedores são descobertos automaticamente pelo CDI sem qualquer
alteração nesta classe.

```java
package br.com.vsjr.labs.log.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

import java.util.Comparator;

/**
 * Gerencia o ciclo de vida de spans customizados para métodos de negócio.
 *
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Criar {@code Child Span} vinculado ao span pai do contexto OTel ativo</li>
 *   <li>Sincronizar o {@code spanId} atualizado no MDC após criação do span filho</li>
 *   <li>Executar o pipeline de enriquecimento: cada {@link EnriquecedorSpan} descoberto
 *       via CDI contribui com atributos OTel em ordem crescente de prioridade</li>
 *   <li>Marcar o span como {@code ERROR} em caso de exceção — com mensagem e stack trace</li>
 *   <li>Encerrar o span e restaurar o {@code spanId} do pai no MDC</li>
 * </ul>
 *
 * <p>O {@link Tracer} é injetado por construtor. Novos {@link EnriquecedorSpan}
 * são descobertos automaticamente pelo CDI — sem alteração nesta classe.</p>
 */
@ApplicationScoped
public class GerenciadorRastreamento {

    private static final String CAMPO_SPAN_ID = "spanId";

    Tracer tracer;

    Instance<EnriquecedorSpan> enriquecedores;

    public GerenciadorRastreamento(Tracer tracer, Instance<EnriquecedorSpan> enriquecedores) {
        this.tracer = tracer;
        this.enriquecedores = enriquecedores;
    }

    /**
     * Cria um Child Span e executa o pipeline de enriquecimento.
     *
     * <p>O span é criado como filho do span ativo em {@code Context.current()}.
     * O MDC é atualizado com o {@code spanId} do filho, garantindo que logs
     * emitidos durante a execução do método referenciem o span correto.</p>
     *
     * <p>O pipeline de enriquecimento segue o padrão Chain of Responsibility:
     * cada {@link EnriquecedorSpan} é executado em ordem crescente de
     * {@link EnriquecedorSpan#prioridade()}.</p>
     *
     * @param nomeSpan nome do span no formato {@code "Classe.metodo"}
     * @param contexto contexto CDI da invocação; repassado para o pipeline de enriquecimento
     * @return contexto do span criado; deve ser passado para {@link #encerrar}
     */
    public ContextoSpan iniciar(String nomeSpan, InvocationContext contexto) {
        var span = tracer.spanBuilder(nomeSpan)
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        var scope = span.makeCurrent();
        MDC.put(CAMPO_SPAN_ID, span.getSpanContext().getSpanId());

        // Pipeline de enriquecimento — Chain of Responsibility em ordem de prioridade
        enriquecedores.stream()
                .sorted(Comparator.comparingInt(EnriquecedorSpan::prioridade))
                .forEach(e -> e.enriquecer(span, contexto));

        return new ContextoSpan(span, scope);
    }

    /**
     * Encerra o span e restaura o MDC ao estado anterior ao span filho.
     *
     * <p>A ordem de encerramento é obrigatória: {@code Scope} antes do {@code Span}.
     * Deve ser chamado em bloco {@code finally}. Restaura o {@code spanId} do pai
     * no MDC para que logs subsequentes continuem referenciando o span correto.</p>
     *
     * @param ctx       contexto retornado por {@link #iniciar}
     * @param spanIdPai {@code spanId} do span pai antes da chamada {@link #iniciar};
     *                  {@code null} quando não havia span ativo no MDC
     */
    public void encerrar(ContextoSpan ctx, String spanIdPai) {
        ctx.scope().close();
        ctx.span().end();

        if (spanIdPai != null) {
            MDC.put(CAMPO_SPAN_ID, spanIdPai);
        } else {
            MDC.remove(CAMPO_SPAN_ID);
        }
    }

    /**
     * Marca o span como falha e registra a exceção como evento do span.
     *
     * <p>O status {@code ERROR} torna o span visível como falha no Jaeger/Grafana Tempo.
     * A chamada a {@link #encerrar} ainda é necessária após este método.</p>
     *
     * @param ctx   contexto do span ativo
     * @param causa exceção que causou a falha
     */
    public void marcarErro(ContextoSpan ctx, Throwable causa) {
        ctx.span().setStatus(StatusCode.ERROR, causa.getMessage());
        ctx.span().recordException(causa);
    }

    // ── Estrutura de retorno ──────────────────────────────────────────────────

    /**
     * Transporta o par (Span, Scope) produzido por {@link #iniciar}.
     *
     * <p>{@code record} do Java 21: imutável, sem boilerplate.
     * O {@code Scope} deve ser fechado antes do {@code Span}
     * — a ordem garantida pelo sequenciamento em {@link #encerrar}.</p>
     */
    public record ContextoSpan(Span span, Scope scope) {}
}
```

---

### 5.12. `RastreamentoInterceptor` — CDI Interceptor de Tracing

Intercepta métodos anotados com `@Rastreado` e delega o ciclo de vida do span
ao `GerenciadorRastreamento`. Executa antes do `LogInterceptor` via `@Priority`
menor, garantindo que o `spanId` do Child Span já esteja no MDC quando o
`LogInterceptor` registrar a localização do método.

```java
package br.com.vsjr.labs.log.interceptor;

import br.com.vsjr.labs.log.annotations.Rastreado;
import br.com.vsjr.labs.log.tracing.GerenciadorRastreamento;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

/**
 * CDI Interceptor ativado por {@link Rastreado}.
 *
 * <p>Para cada método interceptado:</p>
 * <ol>
 *   <li>Cria um {@code Child Span} OTel vinculado ao span pai da requisição</li>
 *   <li>Registra classe, método e serviço como atributos do span</li>
 *   <li>Sincroniza o {@code spanId} do filho no MDC</li>
 *   <li>Executa o método de negócio</li>
 *   <li>Em caso de exceção: marca o span como {@code ERROR} com stack trace</li>
 *   <li>Encerra o span (registra hora de término) e restaura o span pai</li>
 * </ol>
 *
 * <p><b>Ordem na cadeia de interceptors (prioridade menor = executa primeiro):</b></p>
 * <pre>
 *   RastreamentoInterceptor  [APPLICATION - 10] → cria span, atualiza spanId no MDC
 *   LogInterceptor           [APPLICATION]      → registra classe/metodo no MDC
 *   Método de negócio
 * </pre>
 *
 * <p><b>Falha de infraestrutura OTel:</b> exceções do {@code Tracer} são
 * capturadas e logadas localmente — nunca relançadas. Uma falha no backend
 * de rastreamento não deve interromper o fluxo de negócio.</p>
 */
@Rastreado
@Interceptor
@Priority(Interceptor.Priority.APPLICATION - 10)
public class RastreamentoInterceptor {

    GerenciadorRastreamento gerenciador;

    public RastreamentoInterceptor(GerenciadorRastreamento gerenciador) {
        this.gerenciador = gerenciador;
    }

    @AroundInvoke
    public Object rastrear(InvocationContext contexto) throws Exception {
        var metodo     = contexto.getMethod();
        var classe     = metodo.getDeclaringClass().getSimpleName();
        var nomeMetodo = metodo.getName();
        var nomeSpan   = classe + "." + nomeMetodo;

        // Salva o spanId do pai antes de criar o Child Span para restaurar no finally
        var spanIdPai = (String) MDC.get("spanId");

        var contextoSpan = gerenciador.iniciar(nomeSpan, contexto);
        try {
            return contexto.proceed();

        } catch (Exception e) {
            gerenciador.marcarErro(contextoSpan, e);
            throw e;

        } finally {
            try {
                gerenciador.encerrar(contextoSpan, spanIdPai);
            } catch (Exception otelEx) {
                LogSistematico.registrando("Falha ao encerrar span OTel")
                        .em(RastreamentoInterceptor.class, "rastrear")
                        .porque("Exceção durante encerramento de span OTel")
                        .como("Interceptor de rastreamento")
                        .erro(otelEx);
            }
        }
    }
}
```

---

### 5.13. `EnriquecedorSpan` — Interface do Pipeline de Enriquecimento

Define o contrato para enriquecer spans com atributos OTel. Segue o padrão
**Chain of Responsibility**: cada implementação é descoberta pelo CDI e contribui
com atributos em ordem crescente de prioridade — sem que o `GerenciadorRastreamento`
precise conhecer nenhuma implementação concreta.

**Bandas de prioridade:**

| Faixa | Tipo | Exemplos |
|---|---|---|
| 1–50 | Atributos técnicos obrigatórios | `EnriquecedorMetadados` (10), `EnriquecedorIdentidade` (20) |
| 100+ | Atributos de negócio opcionais | Identifiers de pedido, valor de pagamento, metadados de operação |

```java
package br.com.vsjr.labs.log.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.interceptor.InvocationContext;

/**
 * Contrato do pipeline de enriquecimento de span (Chain of Responsibility).
 *
 * <p>Cada implementação contribui com atributos OTel ao span no momento da criação.
 * O {@link GerenciadorRastreamento} executa todos os enriquecedores descobertos via
 * CDI em ordem crescente de {@link #prioridade()}.</p>
 *
 * <p><b>Acesso a parâmetros de negócio:</b> {@code contexto.getParameters()} expõe
 * os argumentos reais da invocação. Implemente de forma defensiva — verifique
 * nulidade e tipos antes de usar.</p>
 *
 * <p><b>Convenções de nomes:</b> use as
 * <a href="https://opentelemetry.io/docs/specs/semconv/">OTel Semantic Conventions</a>
 * para atributos técnicos e prefixos de domínio para atributos de negócio
 * (ex: {@code pagamento.valor}, {@code pedido.id}).</p>
 */
public interface EnriquecedorSpan {

    /**
     * Enriquece o span com atributos OTel.
     *
     * @param span     span ativo no momento da interceptação
     * @param contexto contexto CDI da invocação; use {@code getParameters()} para
     *                 acessar os argumentos de negócio do método interceptado
     */
    void enriquecer(Span span, InvocationContext contexto);

    /**
     * Define a ordem de execução na cadeia.
     * Valores menores executam primeiro. Padrão: {@link Integer#MAX_VALUE}.
     */
    default int prioridade() {
        return Integer.MAX_VALUE;
    }
}
```

---

### 5.14. `EnriquecedorMetadados` — Metadados Técnicos (Prioridade 10)

Enriquecedor obrigatório que adiciona os atributos de identificação técnica ao span.
Executa primeiro na cadeia (prioridade 10), garantindo que os atributos OTel Semantic
Conventions estejam presentes antes de qualquer enriquecedor de negócio.

```java
package br.com.vsjr.labs.log.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Enriquecedor obrigatório — metadados técnicos da invocação.
 *
 * <p>Prioridade {@code 10}: executa primeiro na cadeia.</p>
 *
 * <p>Atributos adicionados seguindo as
 * <a href="https://opentelemetry.io/docs/specs/semconv/code/">OTel Code Semantic Conventions</a>:</p>
 * <ul>
 *   <li>{@code application.name} — nome da aplicação ({@code quarkus.application.name})</li>
 *   <li>{@code code.namespace} — nome qualificado da classe interceptada</li>
 *   <li>{@code code.function}  — nome do método interceptado</li>
 * </ul>
 */
@ApplicationScoped
public class EnriquecedorMetadados implements EnriquecedorSpan {

    String applicationName;

    public EnriquecedorMetadados(
            @ConfigProperty(name = "quarkus.application.name", defaultValue = "aplicacao-desconhecida") String applicationName) {
        this.applicationName = applicationName;
    }

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        var metodo = contexto.getMethod();
        span.setAttribute("application.name", applicationName);
        span.setAttribute("code.namespace", metodo.getDeclaringClass().getName());
        span.setAttribute("code.function",  metodo.getName());
    }

    @Override
    public int prioridade() { return 10; }
}
```

---

### 5.15. `EnriquecedorIdentidade` — Identidade do Usuário (Prioridade 20)

Adiciona o identificador do usuário autenticado ao span quando a requisição não é
anônima. Usa `SecurityIdentity` do Quarkus — sempre disponível via CDI, nunca `null`,
retorna identidade anônima quando nenhuma extensão de segurança está configurada.

```java
package br.com.vsjr.labs.log.tracing;

import io.opentelemetry.api.trace.Span;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.interceptor.InvocationContext;

/**
 * Enriquecedor opcional — identidade do usuário autenticado.
 *
 * <p>Prioridade {@code 20}: executa após {@link EnriquecedorMetadados}.</p>
 *
 * <p>Atributos adicionados (OTel semantic conventions):</p>
 * <ul>
 *   <li>{@code enduser.id} — nome do principal autenticado;
 *       omitido quando a requisição é anônima.</li>
 * </ul>
 */
@ApplicationScoped
public class EnriquecedorIdentidade implements EnriquecedorSpan {

    SecurityIdentity identidade;

    public EnriquecedorIdentidade(SecurityIdentity identidade) {
        this.identidade = identidade;
    }

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        if (!identidade.isAnonymous()) {
            span.setAttribute("enduser.id", identidade.getPrincipal().getName());
        }
    }

    @Override
    public int prioridade() { return 20; }
}
```

---

### 5.16. `EnriquecedorContexto` — Contrato do Pipeline MDC

Interface do padrão **Chain of Responsibility** para enriquecimento do MDC.
Cada implementação é descoberta automaticamente pelo CDI e executada em ordem
crescente de `prioridade()` pelo `GerenciadorContextoLog`. Novas implementações
são adicionadas sem alterar nenhuma classe existente.

**Bandas de prioridade:**

| Faixa | Tipo | Exemplos |
|---|---|---|
| 1–50 | Atributos técnicos obrigatórios | `EnriquecedorLocalizacao` (10), `EnriquecedorUsuario` (20) |
| 100+ | Atributos de negócio opcionais | Operação, identificadores de domínio |

```java
package br.com.vsjr.labs.log.context;

import jakarta.interceptor.InvocationContext;

import java.util.Set;

/**
 * Contrato do pipeline de enriquecimento do contexto de logging (Chain of Responsibility).
 *
 * <p>Cada implementação contribui com campos MDC para o contexto da invocação
 * interceptada. O {@link GerenciadorContextoLog} executa todos os enriquecedores
 * descobertos via CDI em ordem crescente de {@link #prioridade()}.</p>
 *
 * <p><b>Limpeza automática:</b> após a execução do método interceptado,
 * {@link GerenciadorContextoLog#limparEnriquecimento()} remove do MDC todas as
 * chaves declaradas por {@link #chavesMdc()}. Inclua todas as chaves potenciais,
 * mesmo as inseridas condicionalmente — {@code MDC.remove} em chave inexistente é seguro.</p>
 *
 * <p><b>Implementação mínima:</b></p>
 * <pre>{@code
 * @ApplicationScoped
 * public class EnriquecedorOperacaoNome implements EnriquecedorContexto {
 *
 *     @Override
 *     public void enriquecer(InvocationContext contexto) {
 *         var nome = contexto.getMethod().getDeclaringClass().getSimpleName()
 *                  + "." + contexto.getMethod().getName();
 *         MDC.put("operacao.nome", nome);
 *     }
 *
 *     @Override
 *     public Set<String> chavesMdc() {
 *         return Set.of("operacao.nome");
 *     }
 *
 *     @Override
 *     public int prioridade() { return 100; }
 * }
 * }</pre>
 */
public interface EnriquecedorContexto {

    /**
     * Enriquece o MDC com campos adicionais para a invocação interceptada.
     *
     * @param contexto contexto CDI da invocação
     */
    void enriquecer(InvocationContext contexto);

    /**
     * Declara as chaves MDC que este enriquecedor pode inserir.
     *
     * <p>Usado pelo {@link GerenciadorContextoLog} para limpeza automática
     * após a execução do método interceptado.</p>
     */
    Set<String> chavesMdc();

    /**
     * Ordem de execução na cadeia — valor menor executa primeiro.
     * Padrão: {@link Integer#MAX_VALUE}.
     */
    default int prioridade() {
        return Integer.MAX_VALUE;
    }
}
```

---

### 5.17. `EnriquecedorLocalizacao` — Localização Técnica (Prioridade 10)

Enriquecedor obrigatório que adiciona classe e método ao MDC. Executa primeiro
na cadeia (prioridade 10), garantindo que os campos de localização estejam no MDC
antes de qualquer enriquecedor de negócio.

```java
package br.com.vsjr.labs.log.context;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

import java.util.Set;

/**
 * Enriquecedor obrigatório — localização técnica da invocação interceptada.
 *
 * <p>Prioridade {@code 10}: executa primeiro na cadeia.</p>
 *
 * <p>Campos adicionados:</p>
 * <ul>
 *   <li>{@code classe} — nome simples da classe interceptada</li>
 *   <li>{@code metodo} — nome do método interceptado</li>
 * </ul>
 */
@ApplicationScoped
public class EnriquecedorLocalizacao implements EnriquecedorContexto {

    @Override
    public void enriquecer(InvocationContext contexto) {
        var metodo = contexto.getMethod();
        MDC.put("classe", metodo.getDeclaringClass().getSimpleName());
        MDC.put("metodo", metodo.getName());
    }

    @Override
    public Set<String> chavesMdc() {
        return Set.of("classe", "metodo");
    }

    @Override
    public int prioridade() {
        return 10;
    }
}
```

---

### 5.18. `EnriquecedorUsuario` — Perfis do Usuário (Prioridade 20)

Enriquecedor opcional que adiciona os papéis do usuário autenticado ao MDC.
Executa após `EnriquecedorLocalizacao`. A injeção de `SecurityIdentity` é segura
mesmo sem extensão de segurança configurada — o Quarkus provê identidade anônima.

```java
package br.com.vsjr.labs.log.context;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enriquecedor opcional — perfis do usuário autenticado.
 *
 * <p>Prioridade {@code 20}: executa após {@link EnriquecedorLocalizacao}.</p>
 *
 * <p>Campo adicionado:</p>
 * <ul>
 *   <li>{@code usuario.perfis} — lista de papéis (roles) separada por vírgula;
 *       omitido quando a requisição é anônima ou quando o usuário não possui papéis.</li>
 * </ul>
 */
@ApplicationScoped
public class EnriquecedorUsuario implements EnriquecedorContexto {

    SecurityIdentity identidade;

    public EnriquecedorUsuario(SecurityIdentity identidade) {
        this.identidade = identidade;
    }

    @Override
    public void enriquecer(InvocationContext contexto) {
        if (identidade.isAnonymous()) {
            return;
        }
        var perfis = identidade.getRoles().stream()
                .sorted()
                .collect(Collectors.joining(","));
        if (!perfis.isBlank()) {
            MDC.put("usuario.perfis", perfis);
        }
    }

    @Override
    public Set<String> chavesMdc() {
        return Set.of("usuario.perfis");
    }

    @Override
    public int prioridade() {
        return 20;
    }
}
```

---

### 5.19. Ativação do Interceptor — sem `beans.xml`

No CDI padrão (Weld, OpenLiberty, Payara), o `beans.xml` com a declaração
`<interceptors>` é obrigatório para ativar qualquer interceptor. **No Quarkus,
esse arquivo não é necessário** — e quando presente com `bean-discovery-mode="all"`,
gera um aviso de build porque o ArC o trata como `annotated` de qualquer forma.

O ArC, container CDI do Quarkus, realiza a descoberta inteiramente em *build-time*
via índice Jandex. A anotação `@Interceptor` é uma *bean defining annotation*:
o ArC a reconhece diretamente no classpath da aplicação e ativa o interceptor
automaticamente — sem nenhuma declaração em XML.

```java
// Isso é suficiente. O ArC descobre e ativa o interceptor
// apenas pela presença de @Interceptor no classpath da aplicação.

@Logged                                          // ← @InterceptorBinding
@Interceptor                                     // ← bean defining annotation
@Priority(Interceptor.Priority.APPLICATION)      // ← define a ordem na cadeia
public class LogInterceptor {
    // ...
}
```

A regra de quando o `beans.xml` ainda é necessário:

| Cenário | `beans.xml` necessário? |
|---|---|
| Interceptor em `src/main/java` (mesmo projeto) | **Não** |
| Interceptor em JAR de dependência externa sem índice Jandex | **Sim** — conteúdo ignorado, serve apenas como marcador de bean archive |
| Interceptor em JAR com `META-INF/jandex.idx` gerado | **Não** — o índice substitui o `beans.xml` |

Se a biblioteca for distribuída como JAR independente (cenário de biblioteca
compartilhada entre projetos), a alternativa ao `beans.xml` é gerar o índice
Jandex durante o build:

```xml
<!-- pom.xml da biblioteca — gera META-INF/jandex.idx -->
<plugin>
    <groupId>io.smallrye</groupId>
    <artifactId>jandex-maven-plugin</artifactId>
    <version>3.2.0</version>
    <executions>
        <execution>
            <id>make-index</id>
            <goals><goal>jandex</goal></goals>
        </execution>
    </executions>
</plugin>
```

Ou, alternativamente, indexar o JAR externo via `application.properties`
da aplicação consumidora:

```properties
# Instrui o ArC a indexar um JAR externo que não possui jandex.idx nem beans.xml
quarkus.index-dependency.log-lib.group-id=br.com.vsjr.labs
quarkus.index-dependency.log-lib.artifact-id=lib-logging-quarkus
```

---

## 6. Exemplos de Uso

### Caso 1 — Serviço de negócio com persistência

```java
@ApplicationScoped
@Logged  // Injeta classe, método e métricas de duração automaticamente
public class PedidoService {

    // Logger injetado pelo Quarkus via JBoss Logging — idiomático e otimizado
    // para native image. Equivalente a Logger.getLogger(PedidoService.class).
    private static final Logger log = Logger.getLogger(PedidoService.class);

    @Inject PedidoRepository repository;

    public Pedido criar(NovoPedidoRequest request) {
        var pedido = new Pedido(request);
        repository.salvar(pedido);

        // INFO: persistência — sempre obrigatório
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

    public List<Pedido> buscarPorCliente(Long clienteId) {
        if (clienteId == null) {
            // DEBUG: validação descartada — sem alteração de estado
            LogSistematico
                .registrando("Busca por cliente ignorada")
                .em(PedidoService.class, "buscarPorCliente")
                .porque("clienteId ausente na requisição")
                .debug();

            return List.of();
        }
        return repository.findByCliente(clienteId);
    }
}
```

**JSON gerado (caso 1 — INFO):**

```json
{
  "timestamp":            "2026-03-11T21:55:00.123Z",
  "level":                "INFO",
  "message":              "Pedido criado",
  "traceId":              "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":               "a3ce929d0e0e4736",
  "userId":               "joao.silva@empresa.com",
  "aplicacao":            "pedidos-service",
  "classe":               "PedidoService",
  "metodo":               "criar",
  "log_classe":           "PedidoService",
  "log_metodo":           "criar",
  "log_motivo":           "Solicitação do cliente via checkout",
  "log_canal":            "API REST — POST /pedidos",
  "detalhe_pedidoId":     "4821",
  "detalhe_valorTotal":   "349.90"
}
```

---

### Caso 2 — Tratamento de erro com gateway externo

```java
public void processar(Long pedidoId) {
    try {
        gateway.cobrar(pedidoId);
    } catch (GatewayException e) {
        LogSistematico
            .registrando("Falha ao processar pagamento")
            .em(PagamentoService.class, "processar")
            .porque("Gateway recusou a transação")
            .comDetalhe("pedidoId",           pedidoId)
            .comDetalhe("codigoErroGateway",  e.getCodigo())
            .erro(e);

        throw new PagamentoException("Pagamento não processado", e);
    }
}
```

---

### Caso 2a — Código de erro único para integração com KEDB

Eventos críticos de negócio e infraestrutura devem receber um código único e estável.
Esse código é a chave de ligação entre o log em produção e a **Base de Conhecimento de
Erros Conhecidos (KEDB)** — repositório interno que documenta causa raiz, impacto e
procedimento de remediação para cada tipo de falha. Quando um operador vê `PAG-4022` em
um alerta, consulta a KEDB e executa o procedimento documentado sem precisar interpretar
a mensagem de log do zero.

```java
public void processar(Long pedidoId) {
    try {
        gateway.cobrar(pedidoId);
    } catch (GatewayException e) {
        LogSistematico
            .registrando("Falha ao processar pagamento")
            .em(PagamentoService.class, "processar")
            .porque("Gateway recusou a transação")
            .comDetalhe("errorCode",          "PAG-4022")   // ← chave KEDB
            .comDetalhe("pedidoId",           pedidoId)
            .comDetalhe("codigoErroGateway",  e.getCodigo())
            .erro(e);

        throw new PagamentoException("Pagamento não processado", e);
    }
}
```

**JSON gerado:**

```json
{
  "level":                   "ERROR",
  "message":                 "Falha ao processar pagamento",
  "traceId":                 "4bf92f3577b34da6a3ce929d0e0e4736",
  "userId":                  "joao.silva@empresa.com",
  "log_motivo":              "Gateway recusou a transação",
  "detalhe_errorCode":       "PAG-4022",
  "detalhe_pedidoId":        "4821",
  "detalhe_codigoErroGateway": "INSUFFICIENT_FUNDS"
}
```

---

### Caso 3 — Dado sensível mascarado automaticamente

```java
// O SanitizadorDados intercepta "password" e registra "****"
// O campo "email" é classificado como dado pessoal e registra "[PROTEGIDO]"
LogSistematico
    .registrando("Tentativa de autenticação")
    .em(AutenticacaoService.class, "autenticar")
    .comDetalhe("email",    request.email())    // → "[PROTEGIDO]"
    .comDetalhe("password", request.senha())    // → "****"
    .comDetalhe("ipOrigem", request.ip())       // → valor original
    .warn();
```

---

### Caso 4 — Pipeline reativo com Mutiny

Com `quarkus-smallrye-context-propagation`, o MDC e o span OTel são propagados
automaticamente entre as trocas de thread do Vert.x. Nenhum código extra é necessário.

```java
@ApplicationScoped
@Logged
public class RelatorioService {

    public Uni<Relatorio> gerarAsync(Long clienteId) {
        return Uni.createFrom().item(clienteId)
                .onItem().transformToUni(id -> {
                    // O MDC e o span OTel são propagados automaticamente pelo
                    // SmallRye Context Propagation — sem código extra
                    LogSistematico
                        .registrando("Relatório gerado de forma assíncrona")
                        .em(RelatorioService.class, "gerarAsync")
                        .comDetalhe("clienteId", id)
                        .info();
                    return repository.buscarAsync(id);
                });
    }
}
```

---

### Caso 5 — Enriquecedor de span de negócio

`EnriquecedorSpan` permite adicionar atributos de domínio ao span sem alterar o código
de negócio. O enriquecedor é descoberto automaticamente pelo CDI — basta implementar
a interface; `GerenciadorRastreamento` o executa sem configuração adicional.

O acesso a `contexto.getParameters()` expõe os argumentos reais da invocação, permitindo
extrair valores de negócio diretamente para o span OTel. Use **pattern matching com
`instanceof`** do Java 21 para extração type-safe sem cast explícito.

```java
/**
 * Enriquecedor de negócio — captura os operandos da operação de divisão.
 *
 * Prioridade 100: executa após os enriquecedores de infra (10 e 20).
 */
@ApplicationScoped
public class EnriquecedorOperacao implements EnriquecedorSpan {

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        // Guard: atua apenas no método "divide"
        if (!"divide".equals(contexto.getMethod().getName())) {
            return;
        }

        var parametros = contexto.getParameters();
        if (parametros == null || parametros.length < 2) {
            return;
        }

        // Java 21: pattern matching com instanceof — extrai Double sem cast explícito
        if (parametros[0] instanceof Double dividendo && parametros[1] instanceof Double divisor) {
            span.setAttribute("operacao.dividendo", dividendo.toString());
            span.setAttribute("operacao.divisor",   divisor.toString());
            span.setAttribute("operacao.risco",     divisor == 0.0);
        }
    }

    @Override
    public int prioridade() { return 100; }
}
```

**Atributos gerados no span:**

| Atributo | Tipo | Descrição |
|---|---|---|
| `operacao.dividendo` | `string` | Valor do numerador |
| `operacao.divisor` | `string` | Valor do denominador |
| `operacao.risco` | `boolean` | `true` quando divisor == 0 — indicador de divisão por zero |

---

### Caso 6 — `@Logged` + `@Rastreado` combinados

As duas anotações acumulam seus interceptors na mesma cadeia CDI. O `@Priority` garante
a ordem correta: `RastreamentoInterceptor` cria o span filho primeiro, depois
`LogInterceptor` registra a localização com o `spanId` já atualizado.

```java
@ApplicationScoped
@Logged    // LogInterceptor [APPLICATION]      → MDC: pipeline EnriquecedorContexto (classe, metodo, ...)
@Rastreado // RastreamentoInterceptor [APPLICATION - 10] → Child Span + pipeline enriquecimento
public class PagamentoService {

    public void processar(Long pedidoId) {
        // MDC neste ponto:
        //   traceId   → ID do trace OTel da requisição HTTP
        //   spanId    → ID do Child Span criado pelo RastreamentoInterceptor
        //   userId    → identidade autenticada (ou "anonimo")
        //   classe    → "PagamentoService"
        //   metodo    → "processar"
        LogSistematico
            .registrando("Pagamento processado")
            .em(PagamentoService.class, "processar")
            .comDetalhe("pedidoId", pedidoId)
            .info();
    }
}
```
        return Uni.createFrom()
            .item(() -> repository.buscar(clienteId))
            .map(dados -> {
                // MDC ainda contém traceId e userId aqui —
                // garantido pelo SmallRye Context Propagation
                LogSistematico
                    .registrando("Relatório gerado")
                    .em(RelatorioService.class, "gerarAsync")
                    .comDetalhe("clienteId",   clienteId)
                    .comDetalhe("totalLinhas", dados.size())
                    .info();

                return new Relatorio(dados);
            });
    }
}
```

---

### Caso 5 — Evento de segurança com erro que relança

```java
public Pedido buscarOuFalhar(Long pedidoId) {
    return repository.findByIdOptional(pedidoId)
        .orElseThrow(() -> {
            var ex = new PedidoNaoEncontradoException(pedidoId);

            // Registra e relança em uma única chamada — sem try/catch extra
            LogSistematico
                .registrando("Pedido não encontrado")
                .em(PedidoService.class, "buscarOuFalhar")
                .porque("Recurso inexistente")
                .comDetalhe("pedidoId", pedidoId)
                .erroERelanca(ex);

            return ex; // Inalcançável — erroERelanca sempre lança
        });
}
```

---

### Caso 6 — Integração externa com `@Rastreado`

```java
@ApplicationScoped
@Rastreado  // Child Span criado para cada método — visível no Jaeger
public class IntegracaoFiscalClient {

    public NotaFiscal emitir(Pedido pedido) {
        // Span "IntegracaoFiscalClient.emitir" criado e vinculado ao traceId
        // da requisição HTTP original.
        // Qualquer exceção aqui é automaticamente marcada no span como ERROR.
        return chamarApiReceita(pedido);
    }
}
```

---

### Caso 7 — `@Logged` + `@Rastreado` com atributo de domínio customizado

```java
@ApplicationScoped
@Logged     // LogInterceptor: MDC via EnriquecedorContexto pipeline (classe, metodo, ...)
@Rastreado  // RastreamentoInterceptor: Child Span OTel + spanId do filho no MDC
public class PagamentoService {

    @Inject GerenciadorRastreamento rastreamento;

    public Pagamento processar(OrdemPagamento ordem) {
        // Enriquece o span com atributos de domínio — visíveis no Jaeger sem
        // necessidade de vasculhar logs para encontrar o contexto da operação
        var spanAtivo = io.opentelemetry.api.trace.Span.current();
        rastreamento.adicionarAtributo(spanAtivo, "pagamento.ordemId", ordem.getId().toString());
        rastreamento.adicionarAtributo(spanAtivo, "pagamento.valor",   ordem.getValor().toString());

        LogSistematico
            .registrando("Pagamento iniciado")
            .em(PagamentoService.class, "processar")
            .comDetalhe("ordemId", ordem.getId())
            .comDetalhe("valor",   ordem.getValor())
            .info();
        // JSON inclui: traceId, spanId (Child Span), userId, aplicacao,
        //              log_classe, log_metodo, detalhe_ordemId, detalhe_valor

        return gateway.processar(ordem);
    }
}
```

**JSON gerado (caso 7 — INFO dentro de método `@Logged` + `@Rastreado`):**

```json
{
  "timestamp":          "2026-03-24T14:32:00.847Z",
  "level":              "INFO",
  "message":            "Pagamento iniciado",
  "traceId":            "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":             "f9d3a1b2c4e56789",
  "userId":             "joao.silva@empresa.com",
  "aplicacao":          "pagamentos-service",
  "classe":             "PagamentoService",
  "metodo":             "processar",
  "log_classe":         "PagamentoService",
  "log_metodo":         "processar",
  "detalhe_ordemId":    "8821",
  "detalhe_valor":      "1249.90"
}
```

> O `spanId` acima é o do **Child Span** criado pelo `RastreamentoInterceptor` —
> não o do Root Span HTTP. Isso permite localizar exatamente qual operação de negócio
> gerou cada linha de log no grafo do Jaeger.

---

## 7. Arquitetura de Observabilidade Completa

Com todas as extensões configuradas, cada requisição gera três sinais correlacionados:

```
Requisição HTTP recebida
        │
        ▼
[LogContextoFiltro]           ←── ContainerRequestFilter JAX-RS
        │  GerenciadorContextoLog.inicializar(userId)
        │  MDC: { userId, aplicacao }
        │  GerenciadorRastreamento.sincronizarMdcRequisicao()
        │  MDC: { traceId, spanId (Root Span) }
        │
        ▼
[RastreamentoInterceptor]     ←── CDI @AroundInvoke em beans @Rastreado  [Priority = APP - 10]
        │  Cria Child Span vinculado ao Root Span
        │  MDC: { spanId atualizado para o filho }
        │  Cronômetro OTel iniciado
        │
        ▼
[LogInterceptor]              ←── CDI @AroundInvoke em beans @Logged     [Priority = APP]
        │  GerenciadorContextoLog.enriquecer(contexto)
        │    → EnriquecedorLocalizacao (prioridade 10):  MDC: { classe, metodo }
        │    → EnriquecedorUsuario    (prioridade 20):  MDC: { usuario.perfis }
        │    → enriquecedores de negócio (prioridade ≥100)
        │
        ▼
[Código de Negócio]
        │  LogSistematico.registrando(...)
        │    → LOG estruturado JSON com: traceId, spanId(filho), userId, aplicacao, ...
        │
        ▼
[LogInterceptor — finally]
        │  GerenciadorContextoLog.limparEnriquecimento()
        │    → remove todas as chaves declaradas pelos EnriquecedorContexto
        │
        ▼
[RastreamentoInterceptor — finally]
        │  GerenciadorRastreamento.encerrar()
        │    Scope.close()  → restaura Root Span como corrente
        │    Span.end()     → registra hora de término, exporta via OTLP → TRACE
        │
        ▼
[LogContextoFiltro — response]
        │  GerenciadorContextoLog.limpar() → MDC: {} (limpo)
        │
        ▼
Exportadores:
  ├── Logs JSON  → ELK Stack / Datadog / Loki
  ├── Métricas   → Prometheus → Grafana  (via /q/metrics)
  └── Traces     → Jaeger / Grafana Tempo / Zipkin  (via OTLP)

Correlação: o traceId é a chave que une os três sinais na mesma requisição.
```

**Aggregação centralizada como requisito arquitetural:** em uma arquitetura de microsserviços,
cada instância de `pedidos-service`, `pagamentos-service` e `notificacoes-service` gera seu
próprio fluxo de logs. Sem um ponto de agregação centralizado (ELK, Loki, Datadog), os logs
de uma única operação distribuída ficam em processos distintos, sem possibilidade de correlação
prática. O `traceId` injetado automaticamente pelo Quarkus é o identificador que torna essa
correlação possível — mas ele só é útil se todos os fluxos convergirem para um mesmo sistema
de consulta.

### 7.1. Log de Auditoria — Padrão Distinto

> Padrão de referência: Chris Richardson — [Audit Logging (microservices.io)](https://microservices.io/patterns/observability/audit-logging.html)

O log de auditoria é um padrão **distinto e complementar** ao log de aplicação produzido
pela DSL. Os dois coexistem e servem audiências diferentes:

| Dimensão | Log de Aplicação (esta biblioteca) | Log de Auditoria |
|---|---|---|
| **Propósito** | Diagnóstico técnico, resposta a incidentes | Conformidade, responsabilização, disputas |
| **Consumidor** | Engenheiros, SRE | Jurídico, segurança, suporte, reguladores |
| **Retenção** | Dias a semanas | Meses a anos (LGPD, regulatórios) |
| **Mutabilidade** | *Append-only* | Imutável e à prova de adulteração |
| **Granularidade** | Eventos técnicos (erros, latência, fluxo) | Ações de negócio (quem alterou o quê, antes/depois) |

**Campos obrigatórios de um registro de auditoria:**

| Campo | Descrição |
|---|---|
| `actorId` | Quem executou a ação (`userId` ou identidade de sistema) |
| `actorIp` | Endereço IP de origem |
| `action` | Tipo: `CREATE`, `UPDATE`, `DELETE`, `READ` (sensível), `LOGIN`, `LOGOUT` |
| `entityType` | Tipo da entidade afetada (`Order`, `UserProfile`, `PaymentMethod`) |
| `entityId` | Identificador da entidade |
| `stateBefore` | Estado relevante antes da ação |
| `stateAfter` | Estado relevante após a ação |
| `@timestamp` | UTC com precisão de milissegundos |
| `traceId` | Correlação com o trace distribuído |
| `outcome` | `SUCCESS` ou `FAILURE` com motivo |

**O que deve sempre gerar um registro de auditoria** (independente de já gerar log de aplicação):
autenticação (`LOGIN`, `LOGIN_FAILED`, `LOGOUT`, `PASSWORD_CHANGED`), decisões de autorização
(`ACCESS_DENIED` em recursos sensíveis), mutações em entidades sensíveis (Usuário, Pedido,
Pagamento), ações administrativas e exportações de dados pessoais.

> ⚠️ **Implementação futura:** o interceptor `@Auditable`, o `AuditWriter` e o pipeline de
> persistência de auditoria estão planejados para uma versão futura da biblioteca.

### 7.2. Rastreamento de Exceções — Padrão Distinto

> Padrão de referência: Chris Richardson — [Exception Tracking (microservices.io)](https://microservices.io/patterns/observability/exception-tracking.html)

Registrar uma exceção em log não é suficiente em sistemas com alto volume. Sem rastreamento
centralizado, a mesma exceção pode ocorrer milhares de vezes antes que alguém perceba —
o cenário de **degradação silenciosa**.

O rastreamento de exceções é **complementar** ao log de aplicação. Uma exceção deve ser simultaneamente:

1. **Registrada** no log estruturado — com contexto 5W1H completo, para correlação com a linha do tempo da requisição.
2. **Reportada** a um serviço de rastreamento centralizado — para de-duplicação por *fingerprint*, atribuição de responsabilidade e acompanhamento de resolução.

**Fingerprinting:** um *fingerprint* é um identificador estável para uma classe de exceções,
calculado a partir do nome da classe da exceção e dos primeiros *stack frames* do código
da aplicação (ignorando frames de frameworks). Isso garante que a 1.000ª ocorrência do mesmo
bug seja reconhecida como o mesmo bug — não como 1.000 ocorrências distintas.

**Pré-requisito de logging:** a qualidade do rastreamento depende inteiramente de receber
o objeto de exceção completo. `e.getMessage()` descarta a classe (necessária para *fingerprinting*),
o *stack trace* (necessário para localizar o bug) e a cadeia de causas (necessária para
entender a raiz). Esse é o motivo da regra absoluta da seção 8 abaixo.

> ⚠️ **Implementação futura:** o `ExceptionReporter` — CDI bean com integração a backends
> como Sentry, Rollbar ou webhook — está planejado para uma versão futura da biblioteca.
> As boas práticas de logging já implementadas (objeto completo, sem log-and-throw) são os
> pré-requisitos que tornarão essa integração eficaz.

---

## 8. Não Conformidades e Checklist de Code Review

Os padrões abaixo são estritamente proibidos e devem ser bloqueados em *Code Review*.
A lista completa de padrões proibidos, com exemplos de código, está na seção 7 de
[Padrão de Logging em Aplicações Java.md](Padrão de Logging em Aplicações Java.md).

| Não conformidade | Impacto |
|---|---|
| `System.out.println` ou `System.err.println` | Sem nível, sem MDC, sem JSON |
| Concatenação de strings ou `String.format` em mensagens | Campo não indexável, frágil a caracteres especiais |
| `log.error(e.getMessage())` sem objeto completo | Descarta stack trace e cadeia de causas — inviabiliza fingerprinting futuro |
| Mensagens genéricas sem identificadores de entidade | Inúteis para diagnóstico em produção |
| Log-and-throw sem contexto adicional | Duplicação de erro sem valor analítico |
| Linguagem informal ou abreviações ambíguas em mensagens | Log ilegível para quem não escreveu o código — viola princípio editorial |
| `Logger.getLogger()` com strings livres fora de `LogSistematico` | Viola a DSL — log sem estrutura 5W1H |
| MDC manipulado fora de `GerenciadorContextoLog` | Risco de vazamento e campos inconsistentes |
| `traceId` gerado como `UUID.randomUUID()` | Impossibilita correlação com Jaeger/Grafana Tempo |
| `MDC.clear()` fora do bloco `finally` do filtro ou interceptor | Vazamento de contexto em caso de exceção |
| Dados sensíveis em `.comDetalhe()` com chave ausente no `SanitizadorDados` | Dado sensível registrado sem mascaramento — adicionar chave ao sanitizador |
| Campo que exige redação total passado via `.comDetalhe()` | Usar mascaramento quando redação (omissão completa) seria obrigatória |
| `@Logged` em beans sem `quarkus-smallrye-context-propagation` ativo | MDC e span OTel perdidos em pipelines reativos Mutiny |
| `@Rastreado` em beans sem `quarkus-smallrye-context-propagation` ativo | Child Span perdido na troca de thread do Vert.x — hierarquia de spans corrompida |
| `Scope.close()` omitido ou fora do `finally` no `GerenciadorRastreamento` | Vazamento de Scope OTel — spans subsequentes na thread ficam com pai errado |
| `Span.end()` omitido em spans criados manualmente | Span nunca exportado ao Collector — invisível no Jaeger |
| Computação custosa sem guarda de nível | Overhead de serialização mesmo com nível desabilitado |
| Eventos de negócio via `log.info()` genérico | Não identificáveis como categoria em ferramentas de observabilidade |
| Falha de observabilidade (`MeterRegistry`, OTel exporter) relançada como exceção | Interrompe o fluxo de negócio por falha de infraestrutura |
| Infraestrutura de log sem SSL/TLS no transporte | Dados de contexto expostos em trânsito mesmo com mascaramento na aplicação |

### Checklist de Code Review

- [ ] Nenhum `System.out.println` ou `System.err.println`
- [ ] Nenhuma concatenação de string ou `String.format` em mensagens de log
- [ ] Nenhum `log.error(e.getMessage())` — objeto de exceção completo passado
- [ ] Nenhuma mensagem genérica — identificadores de entidade presentes
- [ ] Nenhum log-and-throw sem contexto adicional
- [ ] Mensagens usam linguagem direta, neutra e consistente com o domínio
- [ ] Nenhum dado sensível (senhas, tokens, PAN, CPF) nos campos de log
- [ ] Campos que exigem redação total omitidos antes de `.comDetalhe()` — não mascarados
- [ ] Nenhum `UUID.randomUUID()` como `traceId` — contexto OpenTelemetry usado
- [ ] MDC limpo no bloco `finally` via `GerenciadorContextoLog.limpar()`
- [ ] `Scope` OTel fechado antes de `Span.end()` — sempre no bloco `finally`
- [ ] `Span.end()` chamado para todo span criado manualmente
- [ ] Falhas do `GerenciadorRastreamento` tratadas localmente — não relançadas
- [ ] Computações custosas protegidas por guarda de nível
- [ ] Nomes de campos canônicos do [Registro de Nomes de Campos](FIELD_NAMES.md) usados
- [ ] Eventos críticos incluem `errorCode` para correlação com KEDB
- [ ] Eventos de negócio usam campo `eventType` identificável — não `log.info()` genérico
- [ ] Falhas de backend de observabilidade tratadas localmente — não relançadas
- [ ] `quarkus-smallrye-context-propagation` presente em aplicações com `@Rastreado` em pipelines Mutiny

---

## Ver Também

**Documentos do projeto:**
- [Padrão de Logging em Aplicações Java](Padrão de Logging em Aplicações Java.md) — fundamentos, 5W1H, padrões proibidos e obrigatórios
- [Implementação SLF4J + Log4j2](implementacao_slf4j.md) — biblioteca portável para containers Jakarta EE
- [Registro de Nomes de Campos](FIELD_NAMES.md) — nomes canônicos dos campos JSON

**Referências bibliográficas:**
- Chris Richardson — [Application Logging (microservices.io)](https://microservices.io/patterns/observability/application-logging.html)
- Chris Richardson — [Distributed Tracing (microservices.io)](https://microservices.io/patterns/observability/distributed-tracing.html)
- Chris Richardson — [Exception Tracking (microservices.io)](https://microservices.io/patterns/observability/exception-tracking.html)
- Chris Richardson — [Audit Logging (microservices.io)](https://microservices.io/patterns/observability/audit-logging.html)
- Iluwatar — [java-design-patterns: microservices-log-aggregation](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-log-aggregation)
- Iluwatar — [java-design-patterns: microservices-distributed-tracing](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-distributed-tracing)
