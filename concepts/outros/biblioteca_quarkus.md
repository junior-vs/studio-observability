# Biblioteca de Logging Sistemático — Quarkus 3.27 + Java 21

> **Documentos relacionados**
> - [Padrão de Logging em Aplicações Java](logging_revisado.md) — fundamentos, 5W1H, padrões proibidos e obrigatórios
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

| Camada | Versão SLF4J | Versão Quarkus 3.27 | Motivo da mudança |
|---|---|---|---|
| Logger | `LoggerFactory.getLogger()` | `@Inject Logger` via JBoss Logging | Logger injetado pelo CDI, otimizado para native image |
| MDC | `org.slf4j.MDC` | `org.jboss.logging.MDC` | Implementação nativa; SLF4J delega para JBoss de qualquer forma |
| JSON | `log4j2.xml` + `JsonTemplateLayout` | `quarkus.log.console.json=true` | Uma linha no `application.properties`, sem XML |
| Contexto HTTP | Spring `@Aspect` + `@Before` | JAX-RS `ContainerRequestFilter` | Padrão Jakarta EE, funciona em native image |
| TraceId | OTel manual no interceptor | `quarkus-opentelemetry` auto-instrumentado | Quarkus injeta o span automaticamente |
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
    ├── java/br/com/seudominio/log/
    │   ├── annotations/
    │   │   └── Logged.java                   ← @InterceptorBinding CDI
    │   ├── context/
    │   │   ├── LogContexto.java              ← Record: snapshot imutável do MDC
    │   │   ├── GerenciadorContextoLog.java   ← Ciclo de vida do MDC + OTel
    │   │   └── SanitizadorDados.java         ← Mascaramento LGPD-compliant
    │   ├── core/
    │   │   └── LogEvento.java                ← Record 5W1H imutável
    │   ├── dsl/
    │   │   ├── LogEtapas.java                ← sealed interfaces da Fluent Interface
    │   │   └── LogSistematico.java           ← Ponto de entrada público da DSL
    │   ├── filtro/
    │   │   └── LogContextoFiltro.java        ← ContainerRequestFilter JAX-RS
    │   └── interceptor/
    │       └── LogInterceptor.java           ← CDI @AroundInvoke + Micrometer
    └── resources/
        └── application.properties            ← Configuração JSON + níveis
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
quarkus.log.category."br.com.seudominio.pedidos".level=DEBUG

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

---

## 5. Código-Fonte

### 5.1. `SanitizadorDados` — Mascaramento LGPD-Compliant

Usa *pattern matching* com `switch` do Java 21 para categorizar o nível de
mascaramento por tipo de dado, em vez de uma simples verificação booleana.
Dados parcialmente identificáveis recebem tratamento diferente de credenciais.

```java
package br.com.seudominio.log.context;

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
package br.com.seudominio.log.context;

/**
 * Snapshot imutável do contexto de correlação de uma requisição.
 *
 * <p>Produzido pelo {@link GerenciadorContextoLog} a partir do OpenTelemetry
 * e da identidade autenticada. Pode ser inspecionado em testes sem dependência
 * de infraestrutura de MDC.</p>
 *
 * <p>Usa {@code record} do Java 21: imutável, thread-safe e com
 * {@code equals/hashCode/toString} gerados sem boilerplate.</p>
 *
 * @param traceId       identificador único de rastreamento distribuído (OTel)
 * @param spanId        identificador do span atual
 * @param userId        identificador do usuário autenticado, ou {@code "anonimo"}
 * @param servico       nome do microsserviço
 */
public record LogContexto(
        String traceId,
        String spanId,
        String userId,
        String servico
) {
    /** Contexto vazio: usado quando nenhuma requisição está ativa (ex: testes, jobs). */
    public static final LogContexto VAZIO = new LogContexto(null, null, "anonimo", "desconhecido");

    /** Retorna {@code true} se este contexto possui rastreamento OTel válido. */
    public boolean temTrace() {
        return traceId != null && !traceId.isBlank();
    }
}
```

---

### 5.3. `GerenciadorContextoLog` — Ciclo de Vida do MDC

Centraliza toda interação com o MDC do JBoss Logging. Captura o `traceId` real
do OpenTelemetry via API injetável — sem acesso estático ao `Span.current()`.

```java
package br.com.seudominio.log.context;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.MDC;

/**
 * Gerencia o ciclo de vida do MDC para cada execução rastreável.
 *
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Capturar {@code traceId} e {@code spanId} do span OTel ativo</li>
 *   <li>Propagar {@code userId} e nome do serviço para o MDC</li>
 *   <li>Garantir limpeza segura após execução</li>
 *   <li>Produzir {@link LogContexto} inspecionável (útil em testes)</li>
 * </ul>
 *
 * <p>Usa {@code org.jboss.logging.MDC} — implementação nativa do Quarkus.
 * O SLF4J delega para JBoss Logging internamente de qualquer forma; usar
 * a API nativa elimina uma camada de indireção desnecessária.</p>
 */
@ApplicationScoped
public class GerenciadorContextoLog {

    // Nome do serviço injetado do application.properties
    @Inject
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "servico-desconhecido")
    String nomeServico;

    private static final String CAMPO_TRACE_ID = "traceId";
    private static final String CAMPO_SPAN_ID  = "spanId";
    private static final String CAMPO_USER_ID  = "userId";
    private static final String CAMPO_SERVICO  = "servico";
    private static final String CAMPO_CLASSE   = "classe";
    private static final String CAMPO_METODO   = "metodo";

    /**
     * Inicializa o MDC com o contexto de correlação da requisição atual.
     *
     * <p>Captura o {@code traceId} e {@code spanId} do span OTel ativo.
     * Se não houver span ativo (ex: jobs agendados sem instrumentação),
     * os campos de trace não são inseridos — evitando valores inválidos.</p>
     *
     * @param userId identificador do usuário autenticado, ou {@code "anonimo"}
     * @return snapshot imutável do contexto populado (útil para testes e auditoria)
     */
    public LogContexto inicializar(String userId) {
        var spanContext = capturarSpanContext();

        if (spanContext.isValid()) {
            MDC.put(CAMPO_TRACE_ID, spanContext.getTraceId());
            MDC.put(CAMPO_SPAN_ID,  spanContext.getSpanId());
        }

        MDC.put(CAMPO_USER_ID, userId != null ? userId : "anonimo");
        MDC.put(CAMPO_SERVICO, nomeServico);

        return new LogContexto(
                spanContext.isValid() ? spanContext.getTraceId() : null,
                spanContext.isValid() ? spanContext.getSpanId()  : null,
                userId != null ? userId : "anonimo",
                nomeServico
        );
    }

    /**
     * Registra o contexto de classe e método interceptado.
     * Chamado pelo {@link br.com.seudominio.log.interceptor.LogInterceptor}.
     */
    public void registrarLocalizacao(String classe, String metodo) {
        MDC.put(CAMPO_CLASSE, classe);
        MDC.put(CAMPO_METODO, metodo);
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

    // ── Interno ───────────────────────────────────────────────────────────────

    private SpanContext capturarSpanContext() {
        return Span.current().getSpanContext();
    }
}
```

---

### 5.4. `LogEvento` — Modelo de Dados 5W1H

`record` imutável que transporta o evento do *builder* da DSL até o emissor.
Os Javadocs usam *text blocks* do Java 21 para exemplificar o JSON gerado.

```java
package br.com.seudominio.log.core;

import java.util.Collections;
import java.util.Map;
import java.util.SequencedMap;

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
 * automaticamente: Who pelo {@link br.com.seudominio.log.filtro.LogContextoFiltro}
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
 *   "servico":          "pedidos-service",
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
     * {@code SequencedMap} preserva a ordem de inserção definida pelo desenvolvedor.
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
package br.com.seudominio.log.dsl;

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
         * {@link br.com.seudominio.log.context.SanitizadorDados}.
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
package br.com.seudominio.log.dsl;

import br.com.seudominio.log.context.SanitizadorDados;
import br.com.seudominio.log.core.LogEvento;
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
package br.com.seudominio.log.annotations;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.*;

/**
 * Ativa interceptação automática de logging para um bean ou método CDI.
 *
 * <p>Quando aplicada, o {@link br.com.seudominio.log.interceptor.LogInterceptor}
 * injeta no MDC: {@code userId}, {@code traceId}, {@code spanId}, {@code classe}
 * e {@code metodo}. Ao término, registra a duração da execução como métrica
 * Micrometer exposta em {@code /q/metrics}.</p>
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

```java
package br.com.seudominio.log.filtro;

import br.com.seudominio.log.context.GerenciadorContextoLog;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

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

    private static final Logger log = Logger.getLogger(LogContextoFiltro.class);

    @Inject
    GerenciadorContextoLog gerenciador;

    /**
     * Fase de requisição: inicializa o MDC antes de qualquer código de negócio.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        var userId = resolverUsuario(requestContext);
        var contexto = gerenciador.inicializar(userId);

        // DEBUG condicional: útil para diagnóstico de problemas de contexto
        if (log.isDebugEnabled()) {
            log.debugf("Contexto de log inicializado — userId=%s, traceId=%s",
                    contexto.userId(),
                    contexto.temTrace() ? contexto.traceId() : "sem-trace");
        }
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
        // Java 21: pattern matching com instanceof para evitar cast explícito
        return switch (requestContext.getSecurityContext()) {
            case null -> "anonimo";
            case var sc when sc.getUserPrincipal() instanceof Principal p
                    && p.getName() != null -> p.getName();
            default -> "anonimo";
        };
    }
}
```

---

### 5.9. `LogInterceptor` — CDI Interceptor com Métricas

Intercepta métodos anotados com `@Logged`, completa o MDC com localização
técnica (classe e método) e registra a duração como métrica Micrometer.

O `MeterRegistry` é injetado pelo CDI — não há configuração de *bean factory*
nem código de inicialização. O Quarkus registra automaticamente no endpoint
`/q/metrics` do Prometheus.

```java
package br.com.seudominio.log.interceptor;

import br.com.seudominio.log.annotations.Logged;
import br.com.seudominio.log.context.GerenciadorContextoLog;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;

/**
 * CDI Interceptor ativado por {@link Logged}.
 *
 * <p>Para cada método interceptado:</p>
 * <ol>
 *   <li>Registra classe e método no MDC ({@code Where} automático)</li>
 *   <li>Executa o método de negócio</li>
 *   <li>Registra duração em nanosegundos via Micrometer Timer</li>
 *   <li>Limpa os campos de localização do MDC no {@code finally}</li>
 * </ol>
 *
 * <p><b>Sobre a limpeza do MDC:</b> o interceptor remove apenas os campos
 * que ele mesmo inseriu ({@code classe} e {@code metodo}). Os campos de
 * correlação da requisição ({@code traceId}, {@code userId}) são responsabilidade
 * do {@link br.com.seudominio.log.filtro.LogContextoFiltro} e permanecem
 * intactos durante toda a execução da requisição.</p>
 *
 * <p><b>Sobre o Micrometer:</b> o {@link MeterRegistry} é injetado pelo CDI.
 * As métricas são expostas automaticamente em {@code /q/metrics} sem
 * configuração adicional.</p>
 */
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LogInterceptor {

    private static final Logger log = Logger.getLogger(LogInterceptor.class);

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    GerenciadorContextoLog gerenciador;

    @AroundInvoke
    public Object interceptar(InvocationContext contexto) throws Exception {
        var metodo    = contexto.getMethod();
        var classe    = metodo.getDeclaringClass().getSimpleName();
        var nomeMetodo = metodo.getName();

        // Registra localização técnica no MDC (dimensão Where)
        gerenciador.registrarLocalizacao(classe, nomeMetodo);

        var cronometro = Timer.start(meterRegistry);

        try {
            return contexto.proceed();

        } catch (Exception e) {
            // Registra falha como counter separado para alertas no Prometheus
            meterRegistry.counter("metodo.falha",
                    "classe", classe,
                    "metodo", nomeMetodo,
                    "excecao", e.getClass().getSimpleName()
            ).increment();
            throw e;

        } finally {
            // Registra duração — disponível em /q/metrics como histogram
            cronometro.stop(
                Timer.builder("metodo.execucao")
                    .tag("classe",  classe)
                    .tag("metodo",  nomeMetodo)
                    .publishPercentileHistogram()        // p50, p95, p99 automáticos
                    .register(meterRegistry)
            );

            // Remove apenas os campos de localização — não toca no contexto da requisição
            org.jboss.logging.MDC.remove("classe");
            org.jboss.logging.MDC.remove("metodo");
        }
    }
}
```

---

### 5.10. Ativação do Interceptor — sem `beans.xml`

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
quarkus.index-dependency.log-lib.group-id=br.com.seudominio
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
  "servico":              "pedidos-service",
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

## 7. Arquitetura de Observabilidade Completa

Com todas as extensões configuradas, cada requisição gera três sinais correlacionados:

```
Requisição HTTP
      │
      ▼
[LogContextoFiltro]  ←── ContainerRequestFilter JAX-RS
      │  Popula MDC: traceId, spanId, userId, servico
      │
      ▼
[LogInterceptor]     ←── CDI @AroundInvoke em beans @Logged
      │  Popula MDC: classe, metodo
      │  Cronômetro iniciado
      │
      ▼
[Código de Negócio]
      │  LogSistematico.registrando(...)  → LOG estruturado em JSON
      │
      ▼
[LogInterceptor — finally]
      │  Timer.stop() → MÉTRICA  em /q/metrics (Prometheus)
      │  Counter de falha se exceção
      │
      ▼
[LogContextoFiltro — response]
      │  MDC.clear() → contexto limpo para próxima requisição
      │
      ▼
Exportadores:
  ├── Logs JSON     → ELK Stack / Datadog / Loki
  ├── Métricas      → Prometheus → Grafana
  └── Traces        → Jaeger / Zipkin (via quarkus-opentelemetry)

Correlação: o traceId é a chave que une os três sinais na mesma requisição.
```

---

## 8. Não Conformidades e Checklist de Code Review

Os padrões abaixo são estritamente proibidos e devem ser bloqueados em *Code Review*.
A lista completa de padrões proibidos, com exemplos de código, está na seção 7 de
[logging_revisado.md](logging_revisado.md).

| Não conformidade | Impacto |
|---|---|
| `System.out.println` ou `System.err.println` | Sem nível, sem MDC, sem JSON |
| Concatenação de strings ou `String.format` em mensagens | Campo não indexável, frágil a caracteres especiais |
| `log.error(e.getMessage())` sem objeto completo | Descarta stack trace e cadeia de causas |
| Mensagens genéricas sem identificadores de entidade | Inúteis para diagnóstico em produção |
| Log-and-throw sem contexto adicional | Duplicação de erro sem valor analítico |
| `Logger.getLogger()` com strings livres fora de `LogSistematico` | Viola a DSL — log sem estrutura 5W1H |
| MDC manipulado fora de `GerenciadorContextoLog` | Risco de vazamento e campos inconsistentes |
| `traceId` gerado como `UUID.randomUUID()` | Impossibilita correlação com Jaeger/Zipkin |
| `MDC.clear()` fora do bloco `finally` do filtro ou interceptor | Vazamento de contexto em caso de exceção |
| Dados sensíveis em `.comDetalhe()` com chave ausente no `SanitizadorDados` | Dado sensível registrado sem mascaramento — adicionar chave ao sanitizador |
| `@Logged` em beans sem `quarkus-smallrye-context-propagation` ativo | MDC e span OTel perdidos em pipelines reativos Mutiny |
| Computação custosa sem guarda de nível | Overhead de serialização mesmo com nível desabilitado |
| Eventos de negócio via `log.info()` genérico | Não identificáveis como categoria em ferramentas de observabilidade |
| Falha de observabilidade (`MeterRegistry`, OTel exporter) relançada como exceção | Interrompe o fluxo de negócio por falha de infraestrutura |

### Checklist de Code Review

- [ ] Nenhum `System.out.println` ou `System.err.println`
- [ ] Nenhuma concatenação de string ou `String.format` em mensagens de log
- [ ] Nenhum `log.error(e.getMessage())` — objeto de exceção completo passado
- [ ] Nenhuma mensagem genérica — identificadores de entidade presentes
- [ ] Nenhum log-and-throw sem contexto adicional
- [ ] Nenhum dado sensível (senhas, tokens, PAN, CPF) nos campos de log
- [ ] Nenhum `UUID.randomUUID()` como `traceId` — contexto OpenTelemetry usado
- [ ] MDC limpo no bloco `finally` via `GerenciadorContextoLog.limpar()`
- [ ] Computações custosas protegidas por guarda de nível
- [ ] Nomes de campos canônicos do [Registro de Nomes de Campos](FIELD_NAMES.md) usados
- [ ] Eventos de negócio usam `structuredLogger.businessEvent()` — não `log.info()` genérico
- [ ] Falhas de backend de observabilidade tratadas localmente — não relançadas
- [ ] `quarkus-smallrye-context-propagation` presente em aplicações com pipelines Mutiny

---

## Ver Também

- [Padrão de Logging em Aplicações Java](logging_revisado.md) — fundamentos, 5W1H, padrões de uso
- [Implementação SLF4J + Log4j2](implementacao_slf4j.md) — biblioteca portável sem ArC
- [Registro de Nomes de Campos](FIELD_NAMES.md) — nomes canônicos dos campos JSON
