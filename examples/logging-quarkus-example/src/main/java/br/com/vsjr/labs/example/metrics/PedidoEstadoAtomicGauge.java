package br.com.vsjr.labs.example.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demonstra o <b>Padrão 3 de Gauge: imperativo com {@link AtomicLong}</b>.
 *
 * <p>Usa um {@code AtomicLong} como suporte (<em>backing store</em>) do Gauge.
 * O código de negócio chama {@code AtomicLong.set(valor)} para atualizar o estado;
 * o Gauge lê o {@code AtomicLong} a cada scrape do Prometheus. O bean
 * {@code @ApplicationScoped} mantém a referência forte, protegendo os {@code AtomicLong}
 * de coleta pelo GC.</p>
 *
 * <p><b>Quando usar este padrão:</b> quando o valor a observar é calculado por código
 * em momentos específicos — resultado de query ao banco, chamada a API externa ou
 * cálculo periódico. Não use quando o valor pode ser derivado diretamente de um
 * objeto existente (prefira o Padrão 2 nesse caso).</p>
 *
 * <p>Um {@code @Scheduled} aciona a sincronização periódica — em produção, substitua
 * o bloco de simulação por {@code repository.contarPorEstado()}.</p>
 *
 * <p><b>Métricas emitidas (uma série por estado):</b></p>
 * <ul>
 *   <li>{@code pedidos.por.estado{estado="PENDENTE"}}</li>
 *   <li>{@code pedidos.por.estado{estado="EM_ANALISE"}}</li>
 *   <li>{@code pedidos.por.estado{estado="BLOQUEADO"}}</li>
 * </ul>
 *
 * <p><b>Por que não Counter:</b> contagens de pedidos por estado <em>podem diminuir</em>
 * — pedidos transitam entre estados e saem do conjunto. O Counter é monotônico por
 * definição; o Gauge com {@code AtomicLong} é o instrumento correto para grandezas
 * que oscilam.</p>
 *
 * @see PedidoEstadoMultiGauge Padrão 4 — MultiGauge para dimensões dinâmicas
 * @see <a href="https://quarkus.io/guides/telemetry-micrometer#gauges">Quarkus — Gauges</a>
 */
@ApplicationScoped
public class PedidoEstadoAtomicGauge {

    private static final Logger log = Logger.getLogger(PedidoEstadoAtomicGauge.class);

    /** Referências fortes exigidas pelo Micrometer, que usa referência fraca ao objeto observado. */
    private final AtomicLong pedidosPendentes  = new AtomicLong(0);
    private final AtomicLong pedidosEmAnalise  = new AtomicLong(0);
    private final AtomicLong pedidosBloqueados = new AtomicLong(0);

    public PedidoEstadoAtomicGauge(Instance<MeterRegistry> registryInstance) {
        if (registryInstance.isResolvable()) {
            var registry = registryInstance.get();

            // Cada Gauge.builder observa um AtomicLong distinto com a tag que identifica o estado.
            // As dimensões são estáticas e conhecidas em tempo de compilação — use MultiGauge
            // (Padrão 4) quando as dimensões são dinâmicas.
            Gauge.builder("pedidos.por.estado", pedidosPendentes, AtomicLong::get)
                .tag("estado", "PENDENTE")
                .description("Pedidos atualmente no estado PENDENTE")
                .register(registry);

            Gauge.builder("pedidos.por.estado", pedidosEmAnalise, AtomicLong::get)
                .tag("estado", "EM_ANALISE")
                .description("Pedidos atualmente em análise de fraude")
                .register(registry);

            Gauge.builder("pedidos.por.estado", pedidosBloqueados, AtomicLong::get)
                .tag("estado", "BLOQUEADO")
                .description("Pedidos bloqueados aguardando revisão manual")
                .register(registry);
        }
    }

    /**
     * Atualiza os Gauges com as contagens atuais de pedidos por estado.
     *
     * <p>Em produção, {@code contagensPorEstado} viria de
     * {@code repository.contarPorEstado()} (ex: {@code SELECT estado, COUNT(*) GROUP BY estado}).
     * Também pode ser chamado diretamente após um evento de domínio que muda o estado de um pedido.</p>
     *
     * @param contagensPorEstado mapa de estado → contagem atual
     */
    public void sincronizar(Map<String, Long> contagensPorEstado) {
        pedidosPendentes.set(contagensPorEstado.getOrDefault("PENDENTE",   0L));
        pedidosEmAnalise.set(contagensPorEstado.getOrDefault("EM_ANALISE", 0L));
        pedidosBloqueados.set(contagensPorEstado.getOrDefault("BLOQUEADO", 0L));
    }

    /**
     * Sincronização periódica dos Gauges.
     *
     * <p>O intervalo {@code 30s} é compatível com o scrape padrão do Prometheus (15s),
     * garantindo que os valores estejam razoavelmente atuais a cada coleta.</p>
     *
     * <p><b>Simulação:</b> substitua o bloco {@code Map.of(...)} por
     * {@code repository.contarPorEstado()} em produção.</p>
     */
    @Scheduled(every = "30s")
    void sincronizarPeriodicamente() {
        try {
            var rng = ThreadLocalRandom.current();

            // Simulação — em produção: repository.contarPorEstado()
            sincronizar(Map.of(
                "PENDENTE",   (long) rng.nextInt(10, 50),
                "EM_ANALISE", (long) rng.nextInt(1, 15),
                "BLOQUEADO",  (long) rng.nextInt(0, 5)
            ));
        } catch (Exception e) {
            // Falha de sincronização não interrompe o negócio — Gauge mantém o último valor conhecido
            log.warnf("Falha ao sincronizar métricas de estado de pedidos: %s", e.getMessage());
        }
    }
}
