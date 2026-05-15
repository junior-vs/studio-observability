package br.com.vsjr.labs.observability.dsl;


import br.com.vsjr.labs.observability.security.SanitizadorDados;

/**
 * Define as etapas da Fluent Interface da DSL de logging sistemático.
 *
 * <p>Usa {@code sealed interface} do Java 21: apenas {@link Log}
 * pode implementar essas interfaces, garantindo que o contrato da DSL
 * não possa ser alterado ou estendido acidentalmente por código externo.</p>
 *
 * <p>Fluxo de chamadas com validação em tempo de compilação:</p>
 * <pre>
 *   Log
 *     .registrando(evento)           // What  — obrigatório, retorna EtapaOnde
 *     .em(classe, metodo)            // Where — explícito, retorna EtapaOpcional
 *     // ou .aqui()                  // Where — automático, retorna EtapaOpcional
 *     [ .porque(motivo)         ]    // Why   — opcional
 *     [ .como(entrypoint)       ]    // How   — opcional
 *     [ .comDetalhe(chave, val) ]*   // extra — zero ou mais chamadas
 *     .info() | .debug() | .warn() | .erro(ex)
 * </pre>
 *
 * <p>O compilador impede chamar {@code .info()} sem passar por
 * {@code .registrando()} e {@code .em()} ou {@code .aqui()} — logs incompletos são
 * erros de compilação, não bugs em produção.</p>
 */
public final class LogEtapas {

    private LogEtapas() {
    }

    /**
     * Etapa 1 — What capturado.
     * Exige a declaração do Where antes de qualquer outra operação.
     */
    public sealed interface EtapaOnde permits Log {

        /**
         * Declara o Where: localização técnica do evento no código.
         *
         * @param classe referência da classe — evita strings hard-coded
         * @param metodo nome do método
         */
        EtapaOpcional em(Class<?> classe, String metodo);

        /**
         * Declara o Where automaticamente a partir da classe e método do ponto de chamada.
         */
        EtapaOpcional aqui();
    }

    /**
     * Etapa 2 — Where preenchido.
     * O observability pode ser emitido ou enriquecido com qualquer combinação de
     * dimensões opcionais antes do terminador.
     */
    public sealed interface EtapaOpcional permits Log {


        /**
         * Declara o Why: causa de negócio do evento.
         * Why: motivo ou causa de negócio do evento.
         *
         * @param motivo descrição da motivação por trás do evento
         */
        EtapaOpcional porque(String motivo);

        /**
         * Declara o How: ponto de entrada de origem do evento.
         * How: entrypoint pelo qual o evento chegou ao sistema.
         *
         * @param entrypoint ponto de entrada canônico
         */
        EtapaOpcional como(Entrypoint entrypoint);

        /**
         * Adiciona um detalhe extra tipado ao observability.
         * <p>
         * Contexto extra em chave-valor.
         * Valores de chaves sensíveis são mascarados automaticamente pelo
         * {@link SanitizadorDados}.
         * Pode ser chamado múltiplas vezes — a ordem de inserção é preservada.
         *
         * @param chave nome do campo no JSON de saída
         * @param valor valor a registrar (mascarado se sensível)
         */
        EtapaOpcional comDetalhe(String chave, Object valor);


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
