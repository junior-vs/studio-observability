package br.com.vsjr.labs.example.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Demonstra o <b>Padrão 4 de Gauge: {@link MultiGauge} para múltiplas dimensões dinâmicas</b>.
 *
 * <p>O {@code MultiGauge} gerencia internamente um conjunto de séries temporais com a mesma
 * métrica base e tags variáveis. Diferente do Padrão 3 (múltiplos {@code Gauge.builder} com
 * {@code AtomicLong} para dimensões fixas), o {@code MultiGauge} aceita dimensões que surgem
 * e desaparecem com o tempo.</p>
 *
 * <p>Com {@code overwrite=true} em {@link MultiGauge#register(Iterable, boolean)},
 * séries cujos estados desapareceram do resultado são removidas automaticamente,
 * evitando "séries zumbi" no Prometheus.</p>
 *
 * <p><b>Quando usar este padrão:</b> quando as dimensões são dinâmicas — novos estados
 * podem surgir em produção, estados vazios devem ser ocultados automaticamente. Para
 * dimensões estáticas e conhecidas em tempo de compilação, use o Padrão 3
 * ({@link PedidoEstadoAtomicGauge}) que é mais simples.</p>
 *
 * <p><b>Métrica emitida:</b></p>
 * <ul>
 *   <li>{@code pedidos.estado.dinamico{estado="..."}} — um valor por estado distinto
 *       retornado pela query. O estado {@code SUSPENSO} aparece dinamicamente para
 *       demonstrar a remoção automática de séries.</li>
 * </ul>
 *
 * @see PedidoEstadoAtomicGauge Padrão 3 — Gauge + AtomicLong para dimensões fixas
 * @see <a href="https://quarkus.io/guides/telemetry-micrometer#gauges">Quarkus — Gauges</a>
 */
@ApplicationScoped
public class PedidoEstadoMultiGauge {

    private static final Logger log = Logger.getLogger(PedidoEstadoMultiGauge.class);

    private final MultiGauge pedidosPorEstado;

    public PedidoEstadoMultiGauge(Instance<MeterRegistry> registryInstance) {
        if (registryInstance.isResolvable()) {
            // Registra o MultiGauge — sem dimensões iniciais; as linhas chegam via atualizar()
            this.pedidosPorEstado = MultiGauge.builder("pedidos.estado.dinamico")
                .description("Pedidos por estado atual — dimensões gerenciadas dinamicamente pelo MultiGauge")
                .register(registryInstance.get());
        } else {
            this.pedidosPorEstado = null;
        }
    }

    /**
     * Atualiza o {@code MultiGauge} com o resultado atual por estado.
     *
     * <p>Cada entrada do mapa torna-se uma {@code Row} com a tag {@code estado}.
     * Com {@code overwrite=true}: estados ausentes no mapa têm suas séries
     * removidas do Prometheus — sem "séries zumbi" de estados extintos.</p>
     *
     * <p>Em produção, {@code contagensPorEstado} viria de
     * {@code repository.contarPorEstado()} (ex: {@code SELECT estado, COUNT(*) GROUP BY estado}).</p>
     *
     * @param contagensPorEstado mapa de estado → contagem atual
     */
    public void atualizar(Map<String, Long> contagensPorEstado) {
        if (pedidosPorEstado == null) {
            return;
        }

        // O type witness <MultiGauge.Row<?>> garante List<Row<?>> compatível com register()
        var rows = contagensPorEstado.entrySet().stream()
            .<MultiGauge.Row<?>>map(e -> MultiGauge.Row.of(Tags.of("estado", e.getKey()), e.getValue()))
            .toList();

        // overwrite=true: remove do Prometheus as séries cujo estado sumiu do resultado
        pedidosPorEstado.register(rows, true);
    }

    /**
     * Sincronização periódica do MultiGauge.
     *
     * <p>Demonstra dimensões genuinamente dinâmicas: o estado {@code SUSPENSO} aparece
     * em ~30% das execuções e some nas demais, exercitando a remoção automática de séries
     * pelo {@code overwrite=true}. Em produção, substitua o bloco de simulação por
     * {@code repository.contarPorEstado()}.</p>
     */
    @Scheduled(every = "30s")
    void sincronizarPeriodicamente() {
        try {
            var rng = ThreadLocalRandom.current();

            // Simulação — em produção: repository.contarPorEstado()
            var contagens = new HashMap<String, Long>();
            contagens.put("PENDENTE",   (long) rng.nextInt(10, 50));
            contagens.put("EM_ANALISE", (long) rng.nextInt(1, 15));
            contagens.put("BLOQUEADO",  (long) rng.nextInt(0, 5));

            // Estado dinâmico: aparece apenas ocasionalmente — demonstra remoção automática de série
            if (rng.nextDouble() < 0.3) {
                contagens.put("SUSPENSO", (long) rng.nextInt(1, 3));
            }

            atualizar(contagens);
        } catch (Exception e) {
            log.warnf("Falha ao sincronizar MultiGauge de pedidos: %s", e.getMessage());
        }
    }
}
