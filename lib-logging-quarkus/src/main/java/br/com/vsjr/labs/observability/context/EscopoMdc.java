package br.com.vsjr.labs.observability.context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.MDC;

/**
 * Escopo restaurável para alterações de MDC.
 *
 * <p>O MDC é um recurso compartilhado pela thread. Em vez de remover valores ao
 * final de uma operação, este escopo restaura exatamente o estado observado antes
 * da alteração. Isso torna chamadas interceptadas aninhadas seguras.</p>
 */
public final class EscopoMdc implements AutoCloseable {

    private final Map<String, Object> valoresAnteriores;
    private boolean fechado;

    private EscopoMdc(Map<String, Object> valoresAnteriores) {
        this.valoresAnteriores = valoresAnteriores;
    }

    public static EscopoMdc aplicar(Map<String, ?> valores) {
        var escopo = capturar(valores.keySet());
        valores.forEach((chave, valor) -> {
            if (valor == null) {
                MDC.remove(chave);
            } else {
                MDC.put(chave, valor);
            }
        });
        return escopo;
    }

    public static EscopoMdc capturar(Set<String> chaves) {
        var anteriores = new LinkedHashMap<String, Object>();
        chaves.forEach(chave -> anteriores.put(chave, MDC.get(chave)));
        return new EscopoMdc(anteriores);
    }

    @Override
    public void close() {
        if (fechado) {
            return;
        }
        fechado = true;
        valoresAnteriores.forEach((chave, valorAnterior) -> {
            if (valorAnterior == null) {
                MDC.remove(chave);
            } else {
                MDC.put(chave, valorAnterior);
            }
        });
    }
}
