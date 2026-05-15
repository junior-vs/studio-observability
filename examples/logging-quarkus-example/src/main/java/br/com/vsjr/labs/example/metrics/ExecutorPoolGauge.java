package br.com.vsjr.labs.example.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Demonstra o <b>Padrão 2 de Gauge: observador de objeto arbitrário</b>.
 *
 * <p>Usa {@code Gauge.builder} com {@code ToDoubleFunction} para observar múltiplas
 * propriedades numéricas de um mesmo objeto — neste caso, um {@link ThreadPoolExecutor}.
 * O Micrometer invoca a função a cada scrape do Prometheus sem que o código de negócio
 * precise "empurrar" os valores explicitamente.</p>
 *
 * <p><b>Quando usar este padrão:</b> quando o objeto a observar já expõe getters
 * numéricos — pool de conexões, cache, executor assíncrono, cliente de API externa.
 * O mesmo objeto pode ser observado por múltiplos Gauges, cada um extraindo uma
 * propriedade diferente, sem custo extra.</p>
 *
 * <p><b>Métricas emitidas:</b></p>
 * <ul>
 *   <li>{@code executor.tarefas.ativas} — threads ativamente executando tarefas</li>
 *   <li>{@code executor.fila.tamanho} — tarefas aguardando em fila</li>
 *   <li>{@code executor.pool.tamanho} — threads no pool (ativas + ociosas)</li>
 *   <li>{@code executor.pool.maximo} — capacidade máxima configurada</li>
 * </ul>
 *
 * <p><b>Referência forte:</b> o campo {@code executor} do bean mantém a referência
 * necessária — o Micrometer usa referência fraca e o GC poderia coletar o objeto
 * sem ela, fazendo os Gauges retornarem {@code NaN}.</p>
 *
 * @see <a href="https://quarkus.io/guides/telemetry-micrometer#gauges">Quarkus — Gauges</a>
 */
@Startup
@ApplicationScoped
public class ExecutorPoolGauge {

    /** Referência forte exigida pelo Micrometer, que usa referência fraca ao objeto observado. */
    private final ThreadPoolExecutor executor;

    public ExecutorPoolGauge(Instance<MeterRegistry> registryInstance) {
        this.executor = new ThreadPoolExecutor(
            2, 10,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100)
        );

        if (registryInstance.isResolvable()) {
            var registry = registryInstance.get();

            // Múltiplos Gauges sobre o mesmo objeto — cada um registra uma série independente.
            // As method references são chamadas pelo Micrometer a cada scrape — modelo observer puro.
            Gauge.builder("executor.tarefas.ativas", executor, ThreadPoolExecutor::getActiveCount)
                .description("Threads do pool ativamente executando tarefas")
                .register(registry);

            Gauge.builder("executor.fila.tamanho", executor, e -> (double) e.getQueue().size())
                .description("Tarefas aguardando execução na fila do pool")
                .register(registry);

            Gauge.builder("executor.pool.tamanho", executor, ThreadPoolExecutor::getPoolSize)
                .description("Número atual de threads no pool, incluindo ociosas")
                .register(registry);

            Gauge.builder("executor.pool.maximo", executor, ThreadPoolExecutor::getMaximumPoolSize)
                .description("Capacidade máxima configurada do pool de threads")
                .register(registry);
        }
    }

    /**
     * Submete uma tarefa ao pool.
     *
     * <p>As métricas {@code executor.tarefas.ativas} e {@code executor.fila.tamanho}
     * refletem a mudança automaticamente no próximo scrape — nenhum código de
     * instrumentação adicional é necessário.</p>
     *
     * @param tarefa tarefa a executar assincronamente
     */
    public void submeter(Runnable tarefa) {
        executor.submit(tarefa);
    }

    @PreDestroy
    void desligar() {
        executor.shutdown();
    }
}
