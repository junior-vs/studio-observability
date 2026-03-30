package br.com.vsjr.labs.example.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demonstra o <b>Padrão 5 de Gauge: {@link TimeGauge} para tempo decorrido desde um evento</b>.
 *
 * <p>O {@code TimeGauge} é o instrumento semanticamente correto para durações: converte
 * automaticamente o valor para a unidade esperada pelo backend de métricas. O Prometheus
 * usa segundos — um {@code Gauge} comum com milissegundos hard-coded produziria escalas
 * erradas em dashboards e alertas.</p>
 *
 * <p>A função passada ao {@code TimeGauge.builder} é um <em>observer</em> puro: o
 * Micrometer a invoca a cada scrape, sem necessidade de código de "push". O valor da
 * métrica reflete sempre o estado atual no momento da coleta.</p>
 *
 * <p><b>Quando usar este padrão:</b> tempo desde o último heartbeat, idade da mensagem
 * mais antiga em fila, tempo desde o último backup bem-sucedido, latência de
 * sincronização de cache — qualquer fenômeno onde o valor a observar é uma duração.</p>
 *
 * <p><b>Métrica emitida:</b></p>
 * <ul>
 *   <li>{@code heartbeat.ultima.ha} — segundos desde o último heartbeat bem-sucedido.
 *       Valor próximo a zero indica sistema saudável; valores altos indicam falha.</li>
 * </ul>
 *
 * <p><b>Alerta PromQL sugerido:</b></p>
 * <pre>{@code
 * heartbeat_ultima_ha_seconds > 60
 * }</pre>
 *
 * @see <a href="https://quarkus.io/guides/telemetry-micrometer#gauges">Quarkus — Gauges</a>
 */
@ApplicationScoped
public class HeartbeatTimeGauge {

    private static final Logger log = Logger.getLogger(HeartbeatTimeGauge.class);

    /** Referência forte exigida pelo Micrometer, que usa referência fraca ao objeto observado. */
    private final AtomicLong ultimoHeartbeatEpoch;

    public HeartbeatTimeGauge(Instance<MeterRegistry> registryInstance) {
        this.ultimoHeartbeatEpoch = new AtomicLong(System.currentTimeMillis());

        if (registryInstance.isResolvable()) {
            // A ToDoubleFunction é avaliada pelo Micrometer a cada scrape — modelo pull/observer puro.
            // TimeUnit.MILLISECONDS declara a unidade do valor retornado pela função;
            // o Micrometer converte para a unidade do backend (Prometheus usa segundos)
            // sem alteração no código de instrumentação.
            TimeGauge.builder(
                    "heartbeat.ultima.ha",
                    ultimoHeartbeatEpoch,
                    TimeUnit.MILLISECONDS,
                    epoch -> (double) (System.currentTimeMillis() - epoch.get())
                )
                .description("Tempo decorrido desde o último heartbeat bem-sucedido")
                .register(registryInstance.get());
        }
    }

    /**
     * Registra um heartbeat bem-sucedido.
     *
     * <p>O {@code TimeGauge} refletirá um valor próximo a zero no próximo scrape do Prometheus.
     * Chamar este método ao concluir com sucesso uma operação crítica — verificação de
     * conectividade com banco de dados, resposta de gateway de pagamento, etc.</p>
     */
    public void registrarHeartbeat() {
        ultimoHeartbeatEpoch.set(System.currentTimeMillis());
    }

    /**
     * Simula heartbeat periódico de um componente de infraestrutura.
     *
     * <p>Em 90% das execuções o heartbeat ocorre normalmente — nos 10% restantes, simula
     * falha de conectividade para demonstrar o TimeGauge crescendo além do limiar de alerta.
     * Em produção, substitua por uma verificação real do componente monitorado.</p>
     */
    @Scheduled(every = "10s")
    void simularHeartbeat() {
        if (ThreadLocalRandom.current().nextDouble() < 0.9) {
            registrarHeartbeat();
            log.debug("Heartbeat registrado com sucesso");
        } else {
            log.warn("Heartbeat não registrado — simulando falha de conectividade");
        }
    }
}
