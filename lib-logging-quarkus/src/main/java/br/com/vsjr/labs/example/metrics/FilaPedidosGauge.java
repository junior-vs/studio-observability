package br.com.vsjr.labs.example.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Demonstra o <b>Padrão 1 de Gauge: observador de coleção</b>.
 *
 * <p>Usa {@code MeterRegistry.gaugeCollectionSize} e {@code gaugeMapSize} — os atalhos de
 * conveniência do Micrometer para registrar um Gauge sobre o tamanho de uma coleção ou mapa
 * <em>sem nenhum código de "set"</em>. O Micrometer lê {@code Collection::size} e
 * {@code Map::size} automaticamente a cada scrape do Prometheus.</p>
 *
 * <p><b>Quando usar este padrão:</b> quando a coleção ou mapa já é o estado canônico
 * gerenciado pelo bean — o Gauge é um observador passivo que não interfere nas operações
 * da coleção.</p>
 *
 * <p><b>Métricas emitidas:</b></p>
 * <ul>
 *   <li>{@code fila.pedidos.pendentes{etapa="entrada"}} — pedidos aguardando processamento</li>
 *   <li>{@code fila.pedidos.processando{etapa="execucao"}} — pedidos em execução simultânea</li>
 * </ul>
 *
 * <p><b>Referência forte:</b> o bean {@code @ApplicationScoped} mantém as referências às
 * coleções — necessário porque o Micrometer usa referência <em>fraca</em> ao objeto observado.
 * Se a referência forte não existir, o GC coletará as coleções e o Gauge retornará
 * {@code NaN} silenciosamente.</p>
 *
 * @see <a href="https://quarkus.io/guides/telemetry-micrometer#gauges">Quarkus — Gauges</a>
 */
@Startup
@ApplicationScoped
public class FilaPedidosGauge {

    /** Referência forte exigida pelo Micrometer, que usa referência fraca ao objeto observado. */
    private final List<String> pendentes;
    private final Map<String, String> emProcessamento;

    public FilaPedidosGauge(Instance<MeterRegistry> registryInstance) {
        if (registryInstance.isResolvable()) {
            var registry = registryInstance.get();

            // gaugeCollectionSize: registra Gauge que lê Collection::size a cada scrape
            // e retorna a própria coleção instrumentada — atribuição direta ao campo
            this.pendentes = registry.gaugeCollectionSize(
                "fila.pedidos.pendentes",
                Tags.of("etapa", "entrada"),
                new CopyOnWriteArrayList<>()
            );

            // gaugeMapSize: equivalente para Map — lê Map::size a cada scrape
            this.emProcessamento = registry.gaugeMapSize(
                "fila.pedidos.processando",
                Tags.of("etapa", "execucao"),
                new ConcurrentHashMap<>()
            );
        } else {
            this.pendentes = new CopyOnWriteArrayList<>();
            this.emProcessamento = new ConcurrentHashMap<>();
        }
    }

    /**
     * Adiciona pedido à fila.
     *
     * <p>O Gauge {@code fila.pedidos.pendentes} refletirá automaticamente o novo tamanho
     * no próximo scrape — nenhum código adicional de notificação é necessário.</p>
     *
     * @param pedidoId identificador do pedido a enfileirar
     */
    public void enfileirar(String pedidoId) {
        pendentes.add(pedidoId);
    }

    /**
     * Move pedido da fila de entrada para a etapa de execução.
     *
     * <p>Os dois Gauges se ajustam automaticamente no próximo scrape:
     * {@code pendentes} decresce; {@code emProcessamento} cresce.</p>
     *
     * @param pedidoId identificador do pedido a iniciar
     */
    public void iniciarProcessamento(String pedidoId) {
        pendentes.remove(pedidoId);
        emProcessamento.put(pedidoId, "EM_PROCESSAMENTO");
    }

    /**
     * Conclui o processamento de um pedido.
     *
     * <p>O Gauge {@code fila.pedidos.processando} decresce automaticamente
     * no próximo scrape — nenhuma chamada explícita ao registry é necessária.</p>
     *
     * @param pedidoId identificador do pedido concluído
     */
    public void concluir(String pedidoId) {
        emProcessamento.remove(pedidoId);
    }

    /** @return contagem instantânea de pedidos pendentes */
    public int quantidadePendentes() {
        return pendentes.size();
    }

    /** @return contagem instantânea de pedidos em processamento */
    public int quantidadeEmProcessamento() {
        return emProcessamento.size();
    }
}
