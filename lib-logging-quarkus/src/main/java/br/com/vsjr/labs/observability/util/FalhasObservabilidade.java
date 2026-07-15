package br.com.vsjr.labs.observability.util;

import java.util.function.Consumer;

/**
 * Política compartilhada para tratamento de falhas em componentes de observabilidade.
 *
 * <p>
 * Falhas não fatais são isoladas para não interromper fluxo de negócio.
 * Falhas fatais são relançadas imediatamente.
 * </p>
 */
public final class FalhasObservabilidade {

    private FalhasObservabilidade() {
    }

    @FunctionalInterface
    public interface OperacaoArriscada {
        void executar() throws Throwable;
    }

    public static void executarComIsolamento(OperacaoArriscada operacao, Consumer<Throwable> aoFalhar) {
        try {
            operacao.executar();
        } catch (Throwable falha) {
            if (isFatal(falha)) {
                relancar(falha);
            }
            aoFalhar.accept(falha);
        }
    }

    public static boolean isFatal(Throwable falha) {
        return falha instanceof OutOfMemoryError
                || falha instanceof StackOverflowError
                || falha instanceof LinkageError
                || falha instanceof InterruptedException
                || falha instanceof ThreadDeath;
    }

    public static void relancar(Throwable falha) {
        if (falha instanceof InterruptedException interrompida) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interrompida);
        }
        if (falha instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (falha instanceof Error erro) {
            throw erro;
        }
        throw new RuntimeException(falha);
    }
}
