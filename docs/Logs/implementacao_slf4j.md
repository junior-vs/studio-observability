# Implementação da Biblioteca — SLF4J + Log4j2 + CDI

> Documento de referência de implementação. Para os fundamentos, decisões de design e padrões de uso,
> consulte o documento principal: [Padrão de Logging em Aplicações Java](Padrão de Logging em Aplicações Java.md).
>
> Para a implementação nativa Quarkus, consulte: [Implementação Quarkus 3.27](biblioteca_quarkus.md).

Esta biblioteca é **portável entre containers Jakarta EE** (Wildfly, TomEE, Payara, OpenLiberty).
Não há acoplamento com nenhum runtime específico. A saída JSON é produzida pelo Log4j2
`JsonTemplateLayout` via `log4j2.xml`, e a ativação do interceptor CDI exige `beans.xml`
com declaração explícita — comportamento padrão do CDI Weld, diferente do ArC do Quarkus.

---

## Diferenças de API em relação à implementação Quarkus

A API pública (`LogSistematico`, `@Logged`) é a mesma nos dois módulos, com uma exceção
intencional: os **terminadores da DSL não recebem o logger como parâmetro** nesta versão,
alinhando o contrato com a implementação Quarkus.

| Aspecto | Esta biblioteca (SLF4J) | Biblioteca Quarkus |
|---|---|---|
| Terminadores | `.info()`, `.erro(ex)` — sem parâmetro | `.info()`, `.erro(ex)` — sem parâmetro |
| Logger interno | `LoggerFactory.getLogger(classe)` — obtido na emissão | `Logger.getLogger(classe)` — JBoss Logging |
| MDC | `org.slf4j.MDC` | `org.jboss.logging.MDC` |
| JSON | `log4j2.xml` + `JsonTemplateLayout` | `quarkus.log.console.json=true` |
| `GerenciadorContextoLog` | CDI bean `@ApplicationScoped` | CDI bean `@ApplicationScoped` |
| Ativação do interceptor | `beans.xml` obrigatório (Weld não descobre automaticamente) | Automático via ArC |
| Contexto reativo | Não aplicável — ambientes Jakarta EE imperativos | SmallRye Context Propagation |

**Nomes de campos MDC são idênticos** nos dois módulos. Qualquer pipeline de observabilidade
(ELK, Loki, Datadog) que consuma logs de ambas as bibliotecas receberá os mesmos campos JSON,
permitindo queries unificadas entre serviços baseados em Jakarta EE e Quarkus.

---

## Estrutura de Pacotes

```
lib-logging-slf4j/
├── pom.xml
└── src/main/
    ├── java/br/com/vsjr/labs/logging/
    │   ├── annotations/
    │   │   └── Logged.java                    ← @InterceptorBinding CDI
    │   ├── context/
    │   │   ├── LogContexto.java               ← Record: snapshot imutável do MDC
    │   │   ├── GerenciadorContextoLog.java    ← CDI bean: ciclo de vida do MDC + OTel
    │   │   └── SanitizadorDados.java          ← Mascaramento LGPD-compliant
    │   ├── core/
    │   │   └── LogEvento.java                 ← Record 5W1H imutável
    │   ├── dsl/
    │   │   ├── LogEtapas.java                 ← sealed interfaces da Fluent Interface
    │   │   └── LogSistematico.java            ← Ponto de entrada público da DSL
    │   └── interceptor/
    │       └── LoggingInterceptor.java        ← CDI @AroundInvoke + Micrometer
    └── resources/
        ├── META-INF/
        │   └── beans.xml                      ← Ativação explícita do interceptor (Weld)
        └── log4j2.xml                         ← Configuração JSON + níveis
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

<!-- Jakarta Security: resolução do usuário autenticado no interceptor -->
<dependency>
    <groupId>jakarta.security.enterprise</groupId>
    <artifactId>jakarta.security.enterprise-api</artifactId>
    <version>3.0.0</version>
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
        <Logger name="br.com.vsjr.labs.pedidos" level="DEBUG" additivity="false">
            <AppenderRef ref="JsonConsole"/>
        </Logger>
    </Loggers>
</Configuration>
```

### 2.1. Tabela de Níveis de Severidade

A escolha do nível deve ser determinística — baseada no impacto sobre o estado do sistema,
não em julgamento subjetivo:

| Nível | Quando usar | Habilitado em produção? |
|---|---|---|
| `TRACE` | Diagnóstico de baixo nível: entradas/saídas de métodos, iterações, valores intermediários detalhados | Nunca — apenas em desenvolvimento local |
| `DEBUG` | Fluxos internos, decisões condicionais, dados intermediários sem alteração de estado | Não por padrão — ativável por pacote |
| `INFO` | Operações que alteram estado: persistência, autenticação, chamadas externas | Sempre |
| `WARN` | Situações anômalas recuperáveis: tentativas de acesso indevido, *fallbacks* ativados | Sempre |
| `ERROR` | Falhas reais: exceção que impede o cumprimento do contrato da operação | Sempre |
| `FATAL` | Falhas que tornam a aplicação incapaz de continuar — exigem intervenção imediata | Sempre |

### 2.2. Requisito de Sincronização de Tempo (NTP + UTC)

Em uma arquitetura de microsserviços, desvios de relógio entre servidores tornam a ordenação
cronológica de eventos impossível, mesmo com logs em JSON. O timestamp de um evento em
`pedidos-service` e o timestamp de um evento correlacionado em `pagamentos-service` devem
ser comparáveis para que a reconstrução da sequência de causa e efeito funcione.

**Requisito obrigatório de infraestrutura:** todos os servidores e containers que geram
logs devem estar sincronizados ao **UTC** via **NTP**. O `JsonTemplateLayout` já emite
timestamps em UTC com precisão de milissegundos — o que é necessário mas não suficiente
sem a sincronização NTP na infraestrutura.

### 2.3. Transporte Seguro de Logs (SSL/TLS)

Mascarar dados sensíveis na aplicação não é suficiente se o canal de transporte não estiver
protegido. Logs transmitidos em texto claro entre o container e o coletor (Fluentd, Logstash,
OTel Collector) podem expor dados de contexto não mascarados — como `userId`, `traceId`
e nomes de entidades — a qualquer observador de rede.

**Requisito:** o transporte de logs entre nós da rede deve usar **SSL/TLS**. Isso se aplica
ao canal entre a aplicação e o coletor, e entre o coletor e o backend (ELK, Loki, Datadog).

---

## 3. `SanitizadorDados` — Mascaramento LGPD-Compliant

O sanitizador é aplicado automaticamente pela DSL em todo valor antes de seu registro.
A verificação é feita pelo nome da chave, permitindo extensão sem alteração da classe central.

Conforme o princípio de *data minimization* (seção 10 de [Padrão de Logging em Aplicações Java.md](Padrão de Logging em Aplicações Java.md)),
dados sensíveis nunca devem aparecer em logs. O sanitizador é a última linha de defesa
quando o desenvolvedor passa um campo sensível inadvertidamente via `.comDetalhe()`.

**Distinção entre mascaramento e redação:**

- **Mascaramento:** o valor é substituído por uma representação que confirma presença sem expor
  o conteúdo (`"****"` para credenciais, `"[PROTEGIDO]"` para dados pessoais). Preserva a
  evidência de que o campo foi fornecido — útil para diagnóstico e conformidade.
- **Redação:** o valor é completamente removido do registro — o campo não aparece no JSON.
  Indicado quando nem a confirmação de presença pode ser registrada (ex: dados sob sigilo legal,
  informações de menores). **Não implementado nesta versão — campos que exigem redação completa
  devem ser omitidos antes de chamar `.comDetalhe()`.**

Em caso de dúvida sobre qual técnica aplicar, prefira a redação (omissão do campo).

```java
package br.com.vsjr.labs.logging.context;

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
 *
 * <p>A lista de chaves pode ser estendida conforme necessidades de conformidade
 * (LGPD, PCI-DSS, HIPAA, etc.) sem alterar nenhuma outra classe.</p>
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
            case CREDENCIAL   -> "****";
            case DADO_PESSOAL -> "[PROTEGIDO]";
            case PUBLICO      -> valor;
        };
    }

    // ── Tipos de sensibilidade ────────────────────────────────────────────────

    private enum Sensibilidade { CREDENCIAL, DADO_PESSOAL, PUBLICO }

    private static Sensibilidade classificar(String chave) {
        var chaveLower = chave.toLowerCase();
        if (CHAVES_CREDENCIAIS.contains(chaveLower))    return Sensibilidade.CREDENCIAL;
        if (CHAVES_DADOS_PESSOAIS.contains(chaveLower)) return Sensibilidade.DADO_PESSOAL;
        return Sensibilidade.PUBLICO;
    }
}
```

---

## 4. `LogContexto` — Snapshot Imutável do MDC

`record` que representa o contexto de correlação de uma requisição. Imutável por definição —
pode ser passado entre threads sem sincronização e inspecionado em testes sem dependência
de infraestrutura MDC.

```java
package br.com.vsjr.labs.logging.context;

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
 * @param traceId  identificador do trace distribuído (null se sem span OTel ativo)
 * @param spanId   identificador do span atual (null se sem span OTel ativo)
 * @param userId   identificador do usuário autenticado, ou {@code "anonimo"}
 * @param servico  nome do microsserviço, lido de {@code quarkus.application.name}
 *                 ou equivalente no container Jakarta EE
 */
public record LogContexto(
        String traceId,
        String spanId,
        String userId,
        String servico
) {
    /** Retorna {@code true} se há um trace OTel válido associado a esta requisição. */
    public boolean temTrace() {
        return traceId != null && !traceId.isBlank();
    }
}
```

---

## 5. `GerenciadorContextoLog` — Ciclo de Vida do MDC

CDI bean `@ApplicationScoped` que centraliza toda interação com o MDC, eliminando chamadas
diretas e dispersas a `MDC.put()` no código de aplicação. Integra-se com OpenTelemetry
para capturar o `traceId` e o `spanId` reais do span ativo.

**Por que CDI bean em vez de classe estática?** A versão anterior desta classe era estática,
o que impedia substituição em testes e tornava o contrato de ciclo de vida implícito. Como
CDI bean, o `GerenciadorContextoLog` pode ser substituído por um mock em testes unitários
via `@InjectMock` ou equivalente, e sua dependência fica declarada explicitamente via `@Inject`.

**Por que `traceId` e `spanId` separados?** O `traceId` identifica toda a operação
distribuída de ponta a ponta — o mesmo valor em todos os serviços envolvidos. O `spanId`
identifica a etapa específica dentro desse trace: cada serviço, cada chamada de banco,
cada operação relevante gera seu próprio `spanId`. Filtrar por `traceId` reconstrói a
história completa; filtrar por `spanId` isola exatamente o componente que falhou.

```java
package br.com.vsjr.labs.logging.context;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.MDC;

/**
 * CDI bean que gerencia o ciclo de vida do MDC para uma execução.
 *
 * <p>Responsável por:</p>
 * <ul>
 *   <li>Capturar {@code traceId} e {@code spanId} reais do OpenTelemetry</li>
 *   <li>Propagar o {@code userId} autenticado e o nome do serviço</li>
 *   <li>Registrar localização técnica (classe e método) por chamada interceptada</li>
 *   <li>Garantir a limpeza do MDC no bloco {@code finally}</li>
 * </ul>
 *
 * <p><b>Regra:</b> nunca gerar {@code traceId} como {@code UUID.randomUUID()}.
 * Um ID falso impede correlação com Jaeger/Zipkin e invalida o tracing distribuído.</p>
 *
 * <p><b>Injeção:</b> usar {@code @Inject GerenciadorContextoLog} — não instanciar
 * diretamente. O CDI garante o escopo correto e permite substituição em testes.</p>
 */
@ApplicationScoped
public class GerenciadorContextoLog {

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
     * os campos de trace não são inseridos — evitando valores inválidos
     * que atrapalhariam buscas nos sistemas de observabilidade.</p>
     *
     * @param userId   identificador do usuário autenticado, ou {@code null} se anônimo
     * @param servico  nome do microsserviço atual
     * @return snapshot imutável do contexto populado (útil para testes e diagnóstico)
     */
    public LogContexto inicializar(String userId, String servico) {
        var spanContext = capturarSpanContext();

        if (spanContext.isValid()) {
            MDC.put(CAMPO_TRACE_ID, spanContext.getTraceId());
            MDC.put(CAMPO_SPAN_ID,  spanContext.getSpanId());
        }

        MDC.put(CAMPO_USER_ID, userId != null && !userId.isBlank() ? userId : "anonimo");

        if (servico != null && !servico.isBlank()) {
            MDC.put(CAMPO_SERVICO, servico);
        }

        return new LogContexto(
                spanContext.isValid() ? spanContext.getTraceId() : null,
                spanContext.isValid() ? spanContext.getSpanId()  : null,
                userId != null && !userId.isBlank() ? userId : "anonimo",
                servico
        );
    }

    /**
     * Registra o contexto de classe e método interceptado.
     *
     * <p>Chamado pelo {@link LoggingInterceptor} no início de cada invocação.
     * Remove os campos no {@code finally} do próprio interceptor — não aqui.</p>
     *
     * @param classe     nome simples da classe interceptada
     * @param metodo     nome do método interceptado
     */
    public void registrarLocalizacao(String classe, String metodo) {
        MDC.put(CAMPO_CLASSE, classe);
        MDC.put(CAMPO_METODO, metodo);
    }

    /**
     * Remove todos os campos do MDC.
     *
     * <p>Deve sempre ser chamado no bloco {@code finally} do interceptor ou filtro.
     * Sem essa limpeza, o contexto da requisição anterior vaza para a próxima
     * requisição atendida pela mesma thread no pool.</p>
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

## 6. `LogEvento` — Modelo de Dados 5W1H

`record` imutável que transporta o evento do builder da DSL até o emissor.
Imutabilidade garante *thread-safety* sem necessidade de sincronização —
conforme o princípio de Imutabilidade dos Objetos de Valor (seção 5.4 de
[Padrão de Logging em Aplicações Java.md](Padrão de Logging em Aplicações Java.md)).

```java
package br.com.vsjr.labs.logging.core;

import java.util.Collections;
import java.util.Map;
import java.util.SequencedMap;

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
 * preenchidas automaticamente: Who pelo {@link br.com.vsjr.labs.logging.interceptor.LoggingInterceptor}
 * via MDC, e When pelo Log4j2 no momento da emissão.</p>
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

## 7. `LogEtapas` — Sealed Interfaces da Fluent Interface

`sealed interface` do Java 21 garante que apenas `LogSistematico` possa implementar
as etapas. O compilador rejeita qualquer tentativa de criar implementações externas,
tornando o contrato da DSL fechado e previsível.

```java
package br.com.vsjr.labs.logging.dsl;

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
         * {@link br.com.vsjr.labs.logging.context.SanitizadorDados}.
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
         * O stack trace é capturado e serializado pelo Log4j2 automaticamente.
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

## 8. `LogSistematico` — Implementação da DSL

Ponto de entrada público da biblioteca. Acumula o estado do evento e o emite via MDC +
SLF4J 2.x — sem montar strings manualmente.

**Sobre a emissão via MDC:** diferente da API key-value do SLF4J 2.x (que adiciona campos
ao evento de forma portável), esta implementação popula os campos 5W1H no MDC imediatamente
antes da emissão e os remove logo após no bloco `finally`. Isso garante que cada campo
apareça como chave de primeiro nível no JSON de saída via `JsonTemplateLayout`, com o mesmo
resultado visual da versão Quarkus, e que os campos do evento atual não contaminem logs
subsequentes na mesma thread.

```java
package br.com.vsjr.labs.logging.dsl;

import br.com.vsjr.labs.logging.context.SanitizadorDados;
import br.com.vsjr.labs.logging.core.LogEvento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import java.util.LinkedHashMap;

/**
 * Ponto de entrada público da DSL de logging sistemático.
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
 */
public final class LogSistematico
        implements LogEtapas.EtapaOnde, LogEtapas.EtapaOpcional {

    private String   evento;
    private Class<?> classeAlvo;
    private String   metodo;
    private String   motivo;
    private String   canal;
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
        // Sanitização automática: credenciais e dados pessoais são mascarados aqui,
        // antes de qualquer registro — não depende da disciplina do desenvolvedor
        detalhes.put(chave, SanitizadorDados.sanitizar(chave, valor));
        return this;
    }

    // ── Terminadores ──────────────────────────────────────────────────────────

    @Override
    public void info() {
        emitir(Level.INFO, null);
    }

    @Override
    public void debug() {
        emitir(Level.DEBUG, null);
    }

    @Override
    public void warn() {
        emitir(Level.WARN, null);
    }

    @Override
    public void erro(Throwable causa) {
        emitir(Level.ERROR, causa);
    }

    @Override
    public <T extends Throwable> void erroERelanca(T causa) throws T {
        emitir(Level.ERROR, causa);
        throw causa;
    }

    // ── Emissão via MDC + SLF4J ───────────────────────────────────────────────

    /**
     * Popula o MDC com as dimensões do evento, emite via SLF4J e limpa no {@code finally}.
     *
     * <p>As dimensões estruturais (classe, método, motivo, canal) são inseridas
     * no MDC imediatamente antes da emissão e removidas logo após — garantindo
     * que campos do evento atual não contaminem logs subsequentes na mesma thread.</p>
     *
     * <p>Os detalhes de negócio são prefixados com {@code "detalhe_"} para
     * diferenciá-los dos campos de infraestrutura no JSON de saída.</p>
     */
    private void emitir(Level level, Throwable causa) {
        var logger = LoggerFactory.getLogger(classeAlvo);
        if (!logger.isEnabledForLevel(level)) return;

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
            if (causa != null) {
                logger.atLevel(level).setCause(causa).log(evento);
            } else {
                logger.atLevel(level).log(evento);
            }
        } finally {
            // Limpeza dos campos do evento: não remove o contexto da requisição
            // (traceId, userId, servico), que é responsabilidade do GerenciadorContextoLog
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

## 9. `@Logged` — Anotação CDI

```java
package br.com.vsjr.labs.logging.annotations;

import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.*;

/**
 * Ativa interceptação automática de logging para um bean ou método CDI.
 *
 * <p>Quando aplicada, o {@link br.com.vsjr.labs.logging.interceptor.LoggingInterceptor}
 * injeta no MDC: {@code userId}, {@code traceId}, {@code spanId}, {@code servico},
 * {@code classe} e {@code metodo}. Ao término, registra a duração da execução como
 * métrica Micrometer.</p>
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
 *
 * <p><b>Ativação:</b> o {@code LoggingInterceptor} deve estar declarado no {@code beans.xml}
 * (ver seção 11). Diferente do Quarkus/ArC, o CDI Weld não descobre interceptores
 * automaticamente — a declaração explícita é obrigatória.</p>
 *
 * <p><b>Uso em jobs sem contexto HTTP:</b> quando {@code @Logged} é aplicado em um bean
 * acionado por scheduler ou mensageria (sem requisição HTTP), o {@code GerenciadorContextoLog}
 * é inicializado pelo interceptor mas o MDC é limpo no {@code finally} do próprio interceptor
 * — não há filtro HTTP para fazer a limpeza. Esse comportamento é correto e intencional.</p>
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Logged {
}
```

---

## 10. `LoggingInterceptor` — CDI Interceptor

O interceptor fecha o ciclo: inicializa o MDC com o contexto da requisição, registra a
localização técnica (classe e método) e captura a duração da execução como métrica Micrometer.

**Sobre a divisão de responsabilidades de limpeza do MDC:**

| Responsável | Campos limpos | Quando |
|---|---|---|
| `LogSistematico.emitir()` | `log_classe`, `log_metodo`, `log_motivo`, `log_canal`, `detalhe_*` | Imediatamente após cada emissão, no `finally` interno |
| `LoggingInterceptor` (finally) | `classe`, `metodo` | Ao término do método interceptado |
| `LoggingInterceptor` (finally) | Todo o MDC via `gerenciador.limpar()` | Ao término do método interceptado, após remover localização |

O interceptor chama `gerenciador.limpar()` no `finally`, o que remove também `traceId`,
`userId` e `servico`. Em ambientes HTTP, o filtro de requisição (seção 12) repopula
esses campos a cada requisição. Em jobs sem contexto HTTP, o interceptor é o único
responsável pelo ciclo completo — o que é correto, pois não há requisição a correlacionar.

Falhas de infraestrutura de observabilidade (`MeterRegistry` indisponível) devem ser tratadas
localmente e nunca relançadas — conforme a seção 11 de [Padrão de Logging em Aplicações Java.md](Padrão de Logging em Aplicações Java.md).

```java
package br.com.vsjr.labs.logging.interceptor;

import br.com.vsjr.labs.logging.annotations.Logged;
import br.com.vsjr.labs.logging.context.GerenciadorContextoLog;
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

/**
 * CDI Interceptor que automatiza o contexto de observabilidade.
 *
 * <p>Para cada método anotado com {@link Logged}:</p>
 * <ol>
 *   <li>Inicializa o MDC com {@code userId}, {@code traceId}, {@code spanId} e
 *       {@code servico} (via {@link GerenciadorContextoLog})</li>
 *   <li>Registra {@code classe} e {@code metodo} no MDC (dimensão <em>Where</em>)</li>
 *   <li>Executa o método interceptado</li>
 *   <li>Registra a duração como métrica Micrometer (histograma p50/p95/p99)</li>
 *   <li>Limpa o MDC — garantido no bloco {@code finally}</li>
 * </ol>
 *
 * <p><b>Ativação:</b> este interceptor precisa ser declarado no {@code beans.xml}
 * (ver seção 11). Diferente do Quarkus/ArC, o CDI Weld não descobre interceptores
 * automaticamente — a declaração explícita é obrigatória.</p>
 */
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LoggingInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Inject
    GerenciadorContextoLog gerenciador;

    @Inject
    SecurityContext securityContext;

    @Inject
    MeterRegistry meterRegistry;

    @AroundInvoke
    public Object interceptar(InvocationContext contexto) throws Exception {
        var metodo     = contexto.getMethod();
        var classe     = metodo.getDeclaringClass().getSimpleName();
        var nomeMetodo = metodo.getName();

        var userId = resolverUsuario();
        gerenciador.inicializar(userId, resolverServico(contexto));
        gerenciador.registrarLocalizacao(classe, nomeMetodo);

        var cronometro = Timer.start(meterRegistry);

        try {
            return contexto.proceed();

        } catch (Exception e) {
            // Counter de falhas por tipo — permite alertas no Prometheus
            try {
                meterRegistry.counter("metodo.falha",
                        "classe",  classe,
                        "metodo",  nomeMetodo,
                        "excecao", e.getClass().getSimpleName()
                ).increment();
            } catch (Exception metricaFalhou) {
                // Falha de observabilidade não deve interromper o negócio (seção 11 do padrão)
                log.warn("Falha ao registrar métrica de erro: {}", metricaFalhou.getMessage());
            }
            throw e;

        } finally {
            // Registra duração — disponível como histograma (p50, p95, p99)
            try {
                cronometro.stop(
                    Timer.builder("metodo.execucao")
                        .tag("classe", classe)
                        .tag("metodo", nomeMetodo)
                        .publishPercentileHistogram()
                        .register(meterRegistry)
                );
            } catch (Exception metricaFalhou) {
                // Falha de observabilidade não deve interromper o negócio (seção 11 do padrão)
                log.warn("Falha ao registrar métrica de duração: {}", metricaFalhou.getMessage());
            }

            // Remove campos de localização antes da limpeza total
            MDC.remove("classe");
            MDC.remove("metodo");
            // Limpeza total: remove traceId, userId, servico e qualquer remanescente
            gerenciador.limpar();
        }
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    private String resolverUsuario() {
        try {
            var principal = securityContext.getCallerPrincipal();
            return (principal != null) ? principal.getName() : "anonimo";
        } catch (Exception e) {
            return "anonimo";
        }
    }

    /**
     * Resolve o nome do serviço a partir do pacote da classe interceptada.
     * Em containers Jakarta EE sem {@code application.name} configurável via
     * injeção direta, o nome do pacote raiz serve como identificador do serviço.
     * Substitua pela leitura de uma propriedade de configuração se disponível.
     */
    private String resolverServico(InvocationContext contexto) {
        var pacote = contexto.getMethod().getDeclaringClass().getPackageName();
        // Extrai o último segmento do pacote como nome do serviço
        var segmentos = pacote.split("\\.");
        return segmentos.length > 0 ? segmentos[segmentos.length - 1] : "desconhecido";
    }
}
```

---

## 11. Ativação do Interceptor — `beans.xml`

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
        <class>br.com.vsjr.labs.logging.interceptor.LoggingInterceptor</class>
    </interceptors>
</beans>
```

> **Quarkus não precisa deste arquivo.** O ArC descobre o `LoggingInterceptor`
> automaticamente via `@Interceptor` no classpath. Ver [biblioteca_quarkus.md](biblioteca_quarkus.md),
> seção 5.10.

---

## 12. Filtro HTTP — Contexto de Requisição

Em ambientes HTTP, o `GerenciadorContextoLog` deve ser inicializado uma única vez por
requisição — no início do processamento, antes de qualquer código de negócio — e limpo
na resposta. O `LoggingInterceptor` (seção 10) resolve esse ciclo para chamadas de serviço.
Para camada HTTP, um filtro de servlet ou JAX-RS é o ponto correto.

**`traceId` e `spanId` — par mínimo para diagnóstico completo:**

| Identificador | Escopo | Gerado por | Pergunta que responde |
|---|---|---|---|
| `spanId` | Uma operação individual dentro do trace | OpenTelemetry SDK | "Em qual nó exato da execução ocorreu a falha ou o gargalo?" |
| `traceId` | Toda a operação distribuída, em todos os serviços | OpenTelemetry SDK | "Todos os logs de todos os serviços para esta operação" |

O `traceId` é indispensável para cruzar fronteiras de serviço. O `spanId` identifica a
operação individual atual dentro do trace — esses dois identificadores, fornecidos nativamente
pelo OpenTelemetry SDK, são suficientes para diagnóstico completo em todos os níveis, sem
necessidade de identificadores adicionais gerados pelo filtro HTTP.

---

## 13. Exemplos de Uso

### Caso 1 — Persistência (evento obrigatório)

```java
@ApplicationScoped
@Logged  // Injeta userId, traceId, spanId, servico, classe e método automaticamente
public class PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoService.class);

    public Pedido criar(NovoPedidoRequest request) {
        var pedido = new Pedido(request);
        repository.salvar(pedido);

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
}
```

**JSON gerado:**

```json
{
  "timestamp":          "2026-03-11T21:55:00.123Z",
  "level":              "INFO",
  "message":            "Pedido criado",
  "traceId":            "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":             "a3ce929d0e0e4736",
  "userId":             "joao.silva@empresa.com",
  "servico":            "pedidos-service",
  "classe":             "PedidoService",
  "metodo":             "criar",
  "log_classe":         "PedidoService",
  "log_metodo":         "criar",
  "log_motivo":         "Solicitação do cliente via checkout",
  "log_canal":          "API REST — POST /pedidos",
  "detalhe_pedidoId":   "4821",
  "detalhe_valorTotal": "349.90"
}
```

---

### Caso 2 — Validação sem alteração de estado (DEBUG)

```java
public List<Pedido> buscarPorCliente(Long clienteId) {
    if (clienteId == null) {
        LogSistematico
            .registrando("Busca por cliente ignorada")
            .em(PedidoService.class, "buscarPorCliente")
            .porque("clienteId ausente na requisição")
            .debug();

        return Collections.emptyList();
    }
    return repository.findByCliente(clienteId);
}
```

---

### Caso 3 — Erro com exceção e código KEDB

```java
public void processar(Long pedidoId) {
    try {
        gateway.cobrar(pedidoId);
    } catch (GatewayException e) {
        LogSistematico
            .registrando("Falha ao processar pagamento")
            .em(PagamentoService.class, "processar")
            .porque("Gateway recusou a transação")
            .comDetalhe("errorCode",         "PAG-4022")   // ← chave KEDB
            .comDetalhe("pedidoId",          pedidoId)
            .comDetalhe("codigoErroGateway", e.getCodigo())
            .erro(e);

        throw new PagamentoException("Pagamento não processado", e);
    }
}
```

---

### Caso 4 — Dado sensível mascarado automaticamente

```java
// "password"  → "****"        (CREDENCIAL)
// "email"     → "[PROTEGIDO]" (DADO_PESSOAL)
// "ipOrigem"  → valor original (PUBLICO)
LogSistematico
    .registrando("Tentativa de autenticação")
    .em(AutenticacaoService.class, "autenticar")
    .comDetalhe("email",    request.email())
    .comDetalhe("password", request.senha())
    .comDetalhe("ipOrigem", request.ip())
    .warn();
```

---

### Caso 5 — Erro que relança em lambda

```java
public Pedido buscarOuFalhar(Long pedidoId) {
    return repository.findByIdOptional(pedidoId)
        .orElseThrow(() -> {
            var ex = new PedidoNaoEncontradoException(pedidoId);

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

## 14. Não Conformidades

| Não conformidade | Impacto |
|---|---|
| `System.out.println` ou `e.printStackTrace()` | Sem estrutura, sem nível, sem MDC |
| Concatenação de strings ou `String.format` em mensagens | Campo não indexável, frágil a caracteres especiais |
| `log.error(e.getMessage())` sem objeto completo | Descarta stack trace e cadeia de causas |
| Mensagens genéricas sem identificadores de entidade | Inúteis para diagnóstico em produção |
| Log-and-throw sem contexto adicional | Duplicação de erro sem valor analítico |
| Linguagem informal ou abreviações ambíguas em mensagens | Log ilegível para quem não escreveu o código — viola princípio editorial |
| Campo que exige redação total passado via `.comDetalhe()` | Usar mascaramento quando redação (omissão completa) seria obrigatória |
| `traceId` como `UUID.randomUUID()` | Impossibilita correlação com tracing distribuído |
| MDC sem limpeza no `finally` | Vazamento de contexto entre threads em produção |
| `beans.xml` sem declaração do `LoggingInterceptor` | Interceptor ignorado silenciosamente — MDC não populado |
| Computação custosa sem guarda de nível | Overhead de serialização com nível desabilitado |
| Eventos de negócio via `log.info()` genérico | Não identificáveis em ferramentas de observabilidade |
| Falha de observabilidade relançada como exceção de negócio | Interrompe o fluxo de negócio por falha de infraestrutura |
| Infraestrutura de log sem SSL/TLS no transporte | Dados de contexto expostos em trânsito mesmo com mascaramento na aplicação |

---

## Ver Também

**Documentos do projeto:**
- [Padrão de Logging em Aplicações Java](Padrão de Logging em Aplicações Java.md) — fundamentos, 5W1H, padrões de uso
- [Implementação Quarkus 3.27](biblioteca_quarkus.md) — biblioteca nativa Quarkus sem `beans.xml`
- [Registro de Nomes de Campos](FIELD_NAMES.md) — nomes canônicos dos campos JSON

**Referências bibliográficas:**
- Chris Richardson — [Application Logging (microservices.io)](https://microservices.io/patterns/observability/application-logging.html)
- Chris Richardson — [Distributed Tracing (microservices.io)](https://microservices.io/patterns/observability/distributed-tracing.html)
- Chris Richardson — [Exception Tracking (microservices.io)](https://microservices.io/patterns/observability/exception-tracking.html)
- Chris Richardson — [Audit Logging (microservices.io)](https://microservices.io/patterns/observability/audit-logging.html)
- Iluwatar — [java-design-patterns: microservices-log-aggregation](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-log-aggregation)
- Iluwatar — [java-design-patterns: microservices-distributed-tracing](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-distributed-tracing)