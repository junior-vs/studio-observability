# Implementação da Biblioteca — SLF4J + Log4j2 + CDI

> Documento de referência de implementação. Para os fundamentos, decisões de design e padrões de uso,
> consulte o documento principal: [Padrão de Logging em Aplicações Java](logging_revisado.md).
>
> Para a implementação nativa Quarkus, consulte: [Implementação Quarkus 3.27](biblioteca_quarkus.md).

Esta biblioteca é **portável entre containers Jakarta EE** (Wildfly, TomEE, Payara, OpenLiberty).
Não há acoplamento com nenhum runtime específico. A saída JSON é produzida pelo Log4j2
`JsonTemplateLayout` via `log4j2.xml`, e a ativação do interceptor CDI exige `beans.xml`
com declaração explícita — comportamento padrão do CDI Weld, diferente do ArC do Quarkus.

---

## Estrutura de Pacotes

```
lib-logging/
├── annotations/
│   └── Logged.java                  ← @InterceptorBinding CDI
├── context/
│   ├── LoggingContextManager.java   ← Gerenciamento centralizado do MDC
│   └── SensitiveDataSanitizer.java  ← Mascaramento de dados sensíveis
├── dsl/
│   ├── LogEtapas.java               ← Interfaces da Fluent Interface
│   └── LogSistematico.java          ← Ponto de entrada da DSL
├── core/
│   └── LogEvento.java               ← Modelo imutável do evento (Record)
└── interceptor/
    └── LoggingInterceptor.java      ← CDI Interceptor + métricas de duração
```

O desenvolvedor acessa apenas `LogSistematico` (DSL) e `@Logged` (anotação).
Os demais componentes são detalhes de implementação internos à biblioteca.

---

## 1. Dependências Maven

```xml
<!-- SLF4J 2.x: API de logging portável com key-value nativo -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>2.0.13</version>
</dependency>

<!-- Log4j2: implementação com suporte a JsonTemplateLayout -->
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-slf4j2-impl</artifactId>
    <version>2.23.1</version>
</dependency>
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-layout-template-json</artifactId>
    <version>2.23.1</version>
</dependency>

<!-- Jakarta CDI: interceptores e injeção de dependência -->
<dependency>
    <groupId>jakarta.enterprise</groupId>
    <artifactId>jakarta.enterprise.cdi-api</artifactId>
    <version>4.0.1</version>
    <scope>provided</scope>
</dependency>

<!-- OpenTelemetry: traceId e spanId reais para correlação distribuída -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
    <version>1.38.0</version>
</dependency>

<!-- Micrometer: métricas de duração integradas ao interceptor -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
    <version>1.13.0</version>
</dependency>
```

---

## 2. Configuração JSON (`log4j2.xml`)

```xml
<!-- src/main/resources/log4j2.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Appenders>
        <Console name="JsonConsole" target="SYSTEM_OUT">
            <!--
                JsonTemplateLayout serializa o evento completo como JSON.
                Todos os campos do MDC aparecem automaticamente como
                chaves de primeiro nível no objeto JSON de saída.
            -->
            <JsonTemplateLayout eventTemplateUri="classpath:LogstashJsonEventLayoutV1.json"/>
        </Console>
    </Appenders>
    <Loggers>
        <Root level="INFO">
            <AppenderRef ref="JsonConsole"/>
        </Root>
        <!-- DEBUG ativável por pacote, sem afetar o restante da aplicação -->
        <Logger name="br.com.seudominio.pedidos" level="DEBUG" additivity="false">
            <AppenderRef ref="JsonConsole"/>
        </Logger>
    </Loggers>
</Configuration>
```

---

## 3. `SensitiveDataSanitizer` — Mascaramento de Dados Sensíveis

O sanitizador é aplicado automaticamente pela DSL em todo valor antes de seu registro.
A verificação é feita pelo nome da chave, permitindo extensão sem alteração da classe central.

Conforme o princípio de *data minimization* (seção 10 de [logging_revisado.md](logging_revisado.md)),
dados sensíveis nunca devem aparecer em logs. O sanitizador é a última linha de defesa
quando o desenvolvedor passa um campo sensível inadvertidamente via `.comDetalhe()`.

```java
package br.com.seudominio.log.context;

import java.util.Set;

/**
 * Mascara automaticamente valores associados a chaves sensíveis.
 *
 * <p>A verificação é feita pelo nome da chave (case-insensitive), não pelo valor.
 * Isso garante que campos como {@code "password"} ou {@code "senha"} sejam
 * sempre mascarados, independente do que o desenvolvedor passar como valor.</p>
 *
 * <p>A lista pode ser estendida conforme necessidades de conformidade
 * (LGPD, PCI-DSS, HIPAA, etc.) sem alterar nenhuma outra classe.</p>
 */
public final class SensitiveDataSanitizer {

    private static final Set<String> CHAVES_SENSIVEIS = Set.of(
            "password", "senha",
            "token", "accesstoken", "refreshtoken",
            "authorization",
            "cpf", "rg",
            "cardnumber", "cvv",
            "secret", "apikey"
    );

    private SensitiveDataSanitizer() {}

    /**
     * Retorna o valor original ou {@code "****"} se a chave for considerada sensível.
     *
     * @param chave nome do campo (verificação case-insensitive)
     * @param valor valor original
     * @return valor seguro para registro em log
     */
    public static Object sanitizar(String chave, Object valor) {
        if (valor == null) return null;
        return CHAVES_SENSIVEIS.contains(chave.toLowerCase()) ? "****" : valor;
    }
}
```

---

## 4. `LoggingContextManager` — Ciclo de Vida do MDC

Centraliza toda interação com o MDC, eliminando chamadas diretas e dispersas a `MDC.put()`
no código de aplicação. Integra-se com OpenTelemetry para capturar o `traceId` e o `spanId`
reais do span ativo.

**Por que `traceId` e `spanId` separados?** O `traceId` identifica toda a operação
distribuída de ponta a ponta — o mesmo valor em todos os serviços envolvidos. O `spanId`
identifica a etapa específica dentro desse trace: cada serviço, cada chamada de banco,
cada operação relevante gera seu próprio `spanId`. Filtrar por `traceId` reconstrói a
história completa; filtrar por `spanId` isola exatamente o componente que falhou.

```java
package br.com.seudominio.log.context;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.slf4j.MDC;

/**
 * Gerencia o ciclo de vida do MDC para uma execução.
 *
 * <p>Responsável por:</p>
 * <ul>
 *   <li>Capturar {@code traceId} e {@code spanId} reais do OpenTelemetry</li>
 *   <li>Propagar o {@code userId} autenticado</li>
 *   <li>Garantir a limpeza do MDC no bloco {@code finally}</li>
 * </ul>
 *
 * <p><b>Regra:</b> nunca gerar {@code traceId} como {@code UUID.randomUUID()}.
 * Um ID falso impede correlação com Jaeger/Zipkin e invalida o tracing distribuído.</p>
 */
public final class LoggingContextManager {

    private static final String CAMPO_TRACE_ID = "traceId";
    private static final String CAMPO_SPAN_ID  = "spanId";
    private static final String CAMPO_USER_ID  = "userId";
    private static final String CAMPO_SERVICE  = "service";

    private LoggingContextManager() {}

    /**
     * Inicializa o MDC com os campos de correlação da requisição atual.
     *
     * <p>O {@code traceId} e o {@code spanId} são extraídos do span ativo do
     * OpenTelemetry. Se não houver span ativo (ex: em testes unitários ou jobs
     * sem instrumentação), os campos de trace não são inseridos — evitando
     * valores inválidos que atrapalhariam buscas nos sistemas de observabilidade.</p>
     *
     * @param userId  identificador do usuário autenticado, ou {@code null} se anônimo
     * @param service nome do microsserviço atual
     */
    public static void inicializar(String userId, String service) {
        // Captura traceId e spanId reais do OpenTelemetry — nunca gera UUID manual
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            MDC.put(CAMPO_TRACE_ID, spanContext.getTraceId());
            MDC.put(CAMPO_SPAN_ID,  spanContext.getSpanId());
        }

        if (userId != null && !userId.isBlank()) {
            MDC.put(CAMPO_USER_ID, userId);
        }

        if (service != null && !service.isBlank()) {
            MDC.put(CAMPO_SERVICE, service);
        }
    }

    /**
     * Remove todos os campos do MDC.
     *
     * <p>Deve sempre ser chamado no bloco {@code finally} do interceptor ou filtro.
     * Sem essa limpeza, o contexto da requisição anterior vaza para a próxima
     * requisição atendida pela mesma thread no pool.</p>
     */
    public static void limpar() {
        MDC.clear();
    }
}
```

---

## 5. `LogEvento` — Modelo de Dados 5W1H

`Record` imutável que transporta o evento do *builder* da DSL até o emissor.
Imutabilidade garante *thread-safety* sem necessidade de sincronização —
conforme o princípio de Imutabilidade dos Objetos de Valor (seção 5.4 de
[logging_revisado.md](logging_revisado.md)).

```java
package br.com.seudominio.log.core;

import java.util.Collections;
import java.util.Map;

/**
 * Representação imutável de um evento de log estruturado segundo o framework 5W1H.
 *
 * <p>Mapeamento das dimensões:</p>
 * <ul>
 *   <li>{@code evento}   → <b>What</b>: o que aconteceu</li>
 *   <li>{@code classe}   → <b>Where</b>: localização técnica</li>
 *   <li>{@code metodo}   → <b>Where</b>: método específico</li>
 *   <li>{@code motivo}   → <b>Why</b>: causa de negócio (opcional)</li>
 *   <li>{@code canal}    → <b>How</b>: canal de origem (opcional)</li>
 *   <li>{@code detalhes} → contexto adicional em chave-valor</li>
 * </ul>
 *
 * <p>As dimensões <em>Who</em> (userId) e <em>When</em> (timestamp) são
 * preenchidas automaticamente pelo MDC e pelo Log4j2, respectivamente.</p>
 */
public record LogEvento(
        String evento,
        String classe,
        String metodo,
        String motivo,
        String canal,
        Map<String, Object> detalhes
) {
    /** Garante imutabilidade do mapa independente do que for passado. */
    public LogEvento {
        detalhes = detalhes != null
                ? Collections.unmodifiableMap(detalhes)
                : Collections.emptyMap();
    }
}
```

---

## 6. `LogEtapas` — Interfaces da Fluent Interface

As interfaces modelam a progressão da Fluent Interface. O compilador impõe a ordem de
preenchimento — impossibilitando o uso da API de forma incompleta.

```java
package br.com.seudominio.log.dsl;

import org.slf4j.Logger;

/**
 * Define as etapas da Fluent Interface da DSL de logging.
 *
 * <p>Fluxo de chamadas com validação em tempo de compilação:</p>
 * <pre>
 *   LogSistematico
 *     .registrando(evento)          // What  — obrigatório, retorna EtapaOnde
 *     .em(classe, metodo)           // Where — obrigatório, retorna EtapaOpcional
 *     [ .porque(motivo)        ]    // Why   — opcional
 *     [ .como(canal)           ]    // How   — opcional
 *     [ .comDetalhe(chave, v)  ]*   // extra — zero ou mais
 *     .info(log) | .debug(log) | .warn(log) | .erro(log, ex)
 * </pre>
 *
 * <p>O compilador impede chamar {@code .info()} sem passar por
 * {@code .registrando()} e {@code .em()} — logs incompletos são erros
 * de compilação, não bugs silenciosos em produção.</p>
 */
public final class LogEtapas {

    private LogEtapas() {}

    /** Etapa 1 — What capturado. Exige o Where antes de qualquer outra operação. */
    public interface EtapaOnde {
        EtapaOpcional em(Class<?> classe, String metodo);
    }

    /** Etapa 2 — Where preenchido. Log pode ser emitido ou enriquecido. */
    public interface EtapaOpcional {

        /** Why: motivo ou causa de negócio do evento. */
        EtapaOpcional porque(String motivo);

        /** How: canal ou mecanismo pelo qual o evento ocorreu. */
        EtapaOpcional como(String canal);

        /**
         * Contexto extra em chave-valor.
         * Valores de chaves sensíveis são mascarados automaticamente pelo
         * {@link SensitiveDataSanitizer}. Pode ser chamado múltiplas vezes.
         */
        EtapaOpcional comDetalhe(String chave, Object valor);

        // ── Terminadores ─────────────────────────────────────────────────────

        /** INFO: operações que alteram estado. Sempre habilitado em produção. */
        void info(Logger logger);

        /** DEBUG: fluxos internos sem alteração de estado. Desabilitado em produção. */
        void debug(Logger logger);

        /** WARN: situações anômalas recuperáveis. */
        void warn(Logger logger);

        /** ERROR: falhas que impedem o cumprimento do contrato da operação. */
        void erro(Logger logger, Throwable causa);

        /**
         * ERROR que registra e relança a exceção em uma única chamada.
         * Útil em lambdas e streams onde a exceção não pode ser engolida.
         */
        <T extends Throwable> void erroERelanca(Logger logger, T causa) throws T;
    }
}
```

---

## 7. `LogSistematico` — Implementação da DSL

Ponto de entrada público da biblioteca. Acumula o estado do evento e o emite
via SLF4J 2.x usando a API de key-value nativa — sem montar strings manualmente.

```java
package br.com.seudominio.log.dsl;

import br.com.seudominio.log.context.SensitiveDataSanitizer;
import br.com.seudominio.log.core.LogEvento;
import org.slf4j.Logger;
import org.slf4j.event.Level;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ponto de entrada da DSL de logging sistemático.
 *
 * <p><b>Uso mínimo obrigatório (What + Where):</b></p>
 * <pre>{@code
 * LogSistematico
 *     .registrando("Pedido criado")
 *     .em(PedidoService.class, "criar")
 *     .info(log);
 * }</pre>
 *
 * <p><b>Uso completo com todas as dimensões do 5W1H:</b></p>
 * <pre>{@code
 * LogSistematico
 *     .registrando("Pagamento recusado")
 *     .em(PagamentoService.class, "processar")
 *     .porque("Saldo insuficiente no gateway")
 *     .como("API REST — POST /pagamentos")
 *     .comDetalhe("pedidoId", pedido.getId())
 *     .comDetalhe("valor",    pedido.getValor())
 *     .comDetalhe("token",    req.token())   // ← mascarado: "****"
 *     .erro(log, excecao);
 * }</pre>
 */
public final class LogSistematico implements LogEtapas.EtapaOnde, LogEtapas.EtapaOpcional {

    private String evento;
    private String classe;
    private String metodo;
    private String motivo;
    private String canal;
    // LinkedHashMap preserva a ordem de inserção no JSON de saída
    private final Map<String, Object> detalhes = new LinkedHashMap<>();

    private LogSistematico() {}

    // ── Ponto de entrada — What ───────────────────────────────────────────────

    /**
     * Inicia a construção do log com a descrição do evento (dimensão <em>What</em>).
     *
     * @param evento o que está acontecendo — ex: "Pedido criado", "Login falhou"
     */
    public static LogEtapas.EtapaOnde registrando(String evento) {
        LogSistematico builder = new LogSistematico();
        builder.evento = evento;
        return builder;
    }

    // ── Etapa obrigatória — Where ─────────────────────────────────────────────

    @Override
    public LogEtapas.EtapaOpcional em(Class<?> classe, String metodo) {
        this.classe = classe.getSimpleName();
        this.metodo = metodo;
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
        // Sanitização automática: valores sensíveis são mascarados aqui,
        // antes de qualquer registro — não depende da disciplina do desenvolvedor
        this.detalhes.put(chave, SensitiveDataSanitizer.sanitizar(chave, valor));
        return this;
    }

    // ── Terminadores ──────────────────────────────────────────────────────────

    @Override
    public void info(Logger logger) {
        emitir(logger, Level.INFO, null);
    }

    @Override
    public void debug(Logger logger) {
        emitir(logger, Level.DEBUG, null);
    }

    @Override
    public void warn(Logger logger) {
        emitir(logger, Level.WARN, null);
    }

    @Override
    public void erro(Logger logger, Throwable causa) {
        emitir(logger, Level.ERROR, causa);
    }

    @Override
    public <T extends Throwable> void erroERelanca(Logger logger, T causa) throws T {
        emitir(logger, Level.ERROR, causa);
        throw causa;
    }

    // ── Emissão via SLF4J 2.x key-value API ──────────────────────────────────

    /**
     * Emite o evento via SLF4J 2.x key-value API.
     *
     * <p>Cada dimensão do 5W1H torna-se um campo JSON independente e pesquisável.
     * Nenhuma string é montada manualmente — o Log4j2 serializa os pares
     * chave-valor diretamente no JSON de saída.</p>
     */
    private void emitir(Logger logger, Level level, Throwable causa) {
        if (!logger.isEnabledForLevel(level)) return;

        var entry = logger.atLevel(level);

        // Dimensões estruturais do 5W1H como campos chave-valor
        if (classe != null) entry = entry.addKeyValue("log_classe", classe);
        if (metodo != null) entry = entry.addKeyValue("log_metodo", metodo);
        if (motivo != null) entry = entry.addKeyValue("log_motivo", motivo);
        if (canal  != null) entry = entry.addKeyValue("log_canal",  canal);

        // Detalhes de negócio: prefixo "detalhe_" distingue dos campos de infraestrutura
        for (var par : detalhes.entrySet()) {
            entry = entry.addKeyValue("detalhe_" + par.getKey(), par.getValue());
        }

        if (causa != null) {
            entry = entry.setCause(causa);
        }

        entry.log(evento);
    }
}
```

---

## 8. `@Logged` — Anotação CDI

```java
package br.com.seudominio.log.annotations;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.*;

/**
 * Marca um bean ou método CDI para interceptação automática de logging.
 *
 * <p>Quando aplicada, o {@link LoggingInterceptor} injeta automaticamente no MDC:
 * {@code userId}, {@code traceId}, {@code spanId}, {@code class} e {@code method}.
 * Registra também a duração da execução como métrica Micrometer.</p>
 *
 * <pre>{@code
 * // Toda a classe — todos os métodos interceptados
 * @ApplicationScoped
 * @Logged
 * public class PedidoService { ... }
 *
 * // Apenas um método específico
 * @ApplicationScoped
 * public class RelatorioService {
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

## 9. `LoggingInterceptor` — CDI Interceptor

O interceptor fecha o ciclo: gerencia o MDC, captura o `userId` autenticado via Jakarta Security
e registra a duração da execução como métrica via Micrometer — integrando logs, rastreamento
e métricas em um único ponto transversal.

Falhas de infraestrutura de observabilidade (ex: `MeterRegistry` indisponível) devem ser tratadas
localmente e nunca relançadas — conforme a seção 11 de [logging_revisado.md](logging_revisado.md).

```java
package br.com.seudominio.log.interceptor;

import br.com.seudominio.log.annotations.Logged;
import br.com.seudominio.log.context.LoggingContextManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.security.enterprise.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.TimeUnit;

/**
 * CDI Interceptor que automatiza o contexto de observabilidade.
 *
 * <p>Para cada método anotado com {@link Logged}:</p>
 * <ol>
 *   <li>Inicializa o MDC com {@code userId}, {@code traceId} e {@code spanId} (OpenTelemetry)</li>
 *   <li>Registra classe e método no MDC (dimensão <em>Where</em>)</li>
 *   <li>Executa o método interceptado</li>
 *   <li>Registra a duração como métrica Micrometer (histograma)</li>
 *   <li>Limpa o MDC — garantido no bloco {@code finally}</li>
 * </ol>
 *
 * <p><b>Ativação:</b> este interceptor precisa ser declarado no {@code beans.xml}
 * (ver seção 10). Diferente do Quarkus/ArC, o CDI Weld não descobre interceptores
 * automaticamente — a declaração explícita é obrigatória.</p>
 */
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LoggingInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Inject
    private SecurityContext securityContext;

    @Inject
    private MeterRegistry meterRegistry;

    @AroundInvoke
    public Object interceptar(InvocationContext contexto) throws Exception {
        var className  = contexto.getMethod().getDeclaringClass().getSimpleName();
        var methodName = contexto.getMethod().getName();

        var userId = resolverUsuario();
        LoggingContextManager.inicializar(userId, className);

        MDC.put("class",  className);
        MDC.put("method", methodName);

        var cronometro = Timer.start(meterRegistry);

        try {
            return contexto.proceed();

        } catch (Exception e) {
            // Counter de falhas por tipo — permite alertas no Prometheus
            try {
                meterRegistry.counter("metodo.falha",
                        "classe",  className,
                        "metodo",  methodName,
                        "excecao", e.getClass().getSimpleName()
                ).increment();
            } catch (Exception metricaFalhou) {
                // Falha de observabilidade não deve interromper o negócio (seção 11)
                log.warn("Falha ao registrar métrica de erro: {}", metricaFalhou.getMessage());
            }
            throw e;

        } finally {
            // Registra duração — disponível como histograma (p50, p95, p99)
            try {
                cronometro.stop(
                    Timer.builder("metodo.execucao")
                        .tag("classe", className)
                        .tag("metodo", methodName)
                        .publishPercentileHistogram()
                        .register(meterRegistry)
                );
            } catch (Exception metricaFalhou) {
                // Falha de observabilidade não deve interromper o negócio (seção 11)
                log.warn("Falha ao registrar métrica de duração: {}", metricaFalhou.getMessage());
            }

            // Limpeza obrigatória — garante que o MDC não vaze entre threads
            LoggingContextManager.limpar();
        }
    }

    private String resolverUsuario() {
        try {
            var principal = securityContext.getCallerPrincipal();
            return (principal != null) ? principal.getName() : "anonimo";
        } catch (Exception e) {
            return "anonimo";
        }
    }
}
```

---

## 10. Ativação do Interceptor — `beans.xml`

Diferente do Quarkus (que usa o ArC e descobre interceptores automaticamente via `@Interceptor`),
o CDI padrão com Weld **exige declaração explícita** no `beans.xml`. Sem essa entrada, o
`LoggingInterceptor` não será ativado — sem erro de compilação ou runtime, simplesmente
ignorado em silêncio.

```xml
<!-- src/main/resources/META-INF/beans.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
           https://jakarta.ee/xml/ns/jakartaee/beans_4_0.xsd"
       version="4.0"
       bean-discovery-mode="annotated">
    <interceptors>
        <!-- Sem esta declaração, o interceptor é ignorado silenciosamente pelo Weld -->
        <class>br.com.seudominio.log.interceptor.LoggingInterceptor</class>
    </interceptors>
</beans>
```

> **Quarkus não precisa deste arquivo.** O ArC descobre o `LoggingInterceptor`
> automaticamente via `@Interceptor` no classpath. Ver [biblioteca_quarkus.md](biblioteca_quarkus.md),
> seção 5.10.

---

## 11. Não Conformidades

| Não conformidade | Impacto |
|---|---|
| `System.out.println` ou `e.printStackTrace()` | Sem estrutura, sem nível, sem MDC |
| Concatenação de strings ou `String.format` em mensagens | Campo não indexável, frágil a caracteres especiais |
| `log.error(e.getMessage())` sem objeto completo | Descarta stack trace e cadeia de causas |
| Mensagens genéricas sem identificadores de entidade | Inúteis para diagnóstico em produção |
| Log-and-throw sem contexto adicional | Duplicação de erro sem valor analítico |
| Dados sensíveis sem mascaramento | Violação de LGPD e políticas de segurança |
| `traceId` como `UUID.randomUUID()` | Impossibilita correlação com tracing distribuído |
| MDC sem limpeza no `finally` | Vazamento de contexto entre threads em produção |
| `beans.xml` sem declaração do `LoggingInterceptor` | Interceptor ignorado silenciosamente — MDC não populado |
| Computação custosa sem guarda de nível | Overhead de serialização com nível desabilitado |
| Eventos de negócio via `log.info()` genérico | Não identificáveis em ferramentas de observabilidade |
| Falha de observabilidade relançada como exceção de negócio | Interrompe o fluxo de negócio por falha de infraestrutura |

---

## Ver Também

- [Padrão de Logging em Aplicações Java](logging_revisado.md) — fundamentos, 5W1H, padrões de uso
- [Implementação Quarkus 3.27](biblioteca_quarkus.md) — biblioteca nativa Quarkus sem `beans.xml`
- [Registro de Nomes de Campos](FIELD_NAMES.md) — nomes canônicos dos campos JSON
