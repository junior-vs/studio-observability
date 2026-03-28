package br.com.vsjr.labs.observability.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Representação imutável de um evento de observability segundo o framework 5W1H.
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
 * automaticamente: Who pelo {@link br.com.vsjr.labs.observability.filtro.LogContextoFiltro}
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
 *   "applicationName":  "pedidos-service",
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
        evento = normalizarObrigatorio(evento, "evento_nao_informado", false);
        classe = normalizarObrigatorio(classe, "desconhecido", true);
        metodo = normalizarObrigatorio(metodo, "desconhecido", true);
        motivo = normalizarOpcional(motivo, true);
        canal = normalizarOpcional(canal, true);

        if (detalhes == null || detalhes.isEmpty()) {
            detalhes = Map.of();
        } else {
            var detalhesNormalizados = new LinkedHashMap<String, Object>();
            detalhes.forEach((chave, valor) -> {
                var chaveNormalizada = normalizarOpcional(chave, true);
                if (chaveNormalizada != null) {
                    detalhesNormalizados.put(canonizarChaveDetalhe(chaveNormalizada), valor != null ? valor : "null");
                }
            });

            detalhes = detalhesNormalizados.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(detalhesNormalizados);
        }
    }

    private static String normalizarObrigatorio(String valor, String fallback, boolean lowerCase) {
        var normalizado = normalizarOpcional(valor, lowerCase);
        return normalizado != null ? normalizado : fallback;
    }

    private static String normalizarOpcional(String valor, boolean lowerCase) {
        if (valor == null) {
            return null;
        }
        var normalizado = valor.trim();
        if (normalizado.isEmpty()) {
            return null;
        }
        return lowerCase ? normalizado.toLowerCase(Locale.ROOT) : normalizado;
    }

    private static String canonizarChaveDetalhe(String chaveNormalizada) {
        if ("eventtype".equals(chaveNormalizada) || "event_type".equals(chaveNormalizada)) {
            return "eventType";
        }
        return chaveNormalizada;
    }
}