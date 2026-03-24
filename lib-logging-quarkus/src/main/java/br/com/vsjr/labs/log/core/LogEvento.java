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