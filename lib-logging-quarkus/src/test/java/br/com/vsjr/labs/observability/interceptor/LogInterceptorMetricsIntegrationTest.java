package br.com.vsjr.labs.observability.interceptor;

import br.com.vsjr.labs.example.rest.HelloService;
import br.com.vsjr.labs.observability.CamposMdc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(MetricsEnabledTestProfile.class)
class LogInterceptorMetricsIntegrationTest {

    @Inject
    HelloService helloService;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "quarkus.application.name")
    String applicationName;

    @TestHTTPResource("/q/metrics")
    URI metricsUri;

    @Test
    void deveRegistrarTimerQuandoMetodoInterceptadoExecutaComSucesso() {
        var timerAntes = encontrarTimerExecucao();
        var contagemAntes = timerAntes == null ? 0d : timerAntes.count();

        var resultado = helloService.sayHello();

        assertEquals("Hello World!", resultado);

        var timerDepois = encontrarTimerExecucao();
        assertNotNull(timerDepois);
        assertEquals(contagemAntes + 1d, timerDepois.count());
    }

    @Test
    void deveRegistrarFalhaQuandoMetodoInterceptadoLancaExcecao() {
        var contadorAntes = encontrarContadorFalha();
        var contagemAntes = contadorAntes == null ? 0d : contadorAntes.count();

        assertThrows(NullPointerException.class, () -> helloService.divide(null, 1d));

        var contadorDepois = encontrarContadorFalha();
        assertNotNull(contadorDepois);
        assertEquals(contagemAntes + 1d, contadorDepois.count());
    }

    @Test
    void deveExporMetricasNoEndpointPrometheus() throws IOException, InterruptedException {
        helloService.sayHello();
        assertThrows(NullPointerException.class, () -> helloService.divide(null, 1d));

        var corpo = buscarCorpoMetrics();
        var nomePrometheus = applicationName.replaceAll("[^A-Za-z0-9_]", "_");

        assertTrue(corpo.contains(nomePrometheus + "_metodo_execucao_seconds_count"));
        assertTrue(corpo.contains(nomePrometheus + "_metodo_falha_total"));
    }

    /** AC-005: métricas de negócio customizadas aparecem em /q/metrics. */
    @Test
    void deveExporMetricaCustomizadaNoEndpointPrometheus() throws IOException, InterruptedException {
        meterRegistry.counter("test.custom.metric", "tipo", "validacao").increment();

        var corpo = buscarCorpoMetrics();

        assertTrue(corpo.contains("test_custom_metric_total"));
    }

    /** AC-006: publishPercentileHistogram gera buckets para histogram_quantile. */
    @Test
    void deveExporBucketsDeHistogramaParaTimerExecucao() throws IOException, InterruptedException {
        helloService.sayHello();

        var corpo = buscarCorpoMetrics();
        var nomePrometheus = applicationName.replaceAll("[^A-Za-z0-9_]", "_");

        assertTrue(corpo.contains(nomePrometheus + "_metodo_execucao_seconds_bucket"));
    }

    /** AC-008: cada método gera entrada de métrica independente em classe anotada com @Logged. */
    @Test
    void deveRegistrarMetricasIndependentesPorMetodo() {
        helloService.sayHello();
        helloService.buscarPedido("123", "tok", "00000000000");

        var timerSayHello = meterRegistry.find(applicationName + ".metodo.execucao")
                .tags(CamposMdc.CLASSE.chave(), "HelloService", CamposMdc.METODO.chave(), "sayHello")
                .timer();
        var timerBuscar = meterRegistry.find(applicationName + ".metodo.execucao")
                .tags(CamposMdc.CLASSE.chave(), "HelloService", CamposMdc.METODO.chave(), "buscarPedido")
                .timer();

        assertNotNull(timerSayHello);
        assertNotNull(timerBuscar);
        assertTrue(timerSayHello.count() >= 1d);
        assertTrue(timerBuscar.count() >= 1d);
    }

    private Timer encontrarTimerExecucao() {
        return meterRegistry.find(applicationName + ".metodo.execucao")
                .tags(CamposMdc.CLASSE.chave(), "HelloService", CamposMdc.METODO.chave(), "sayHello")
                .timer();
    }

    private Counter encontrarContadorFalha() {
        return meterRegistry.find(applicationName + ".metodo.falha")
                .tags(CamposMdc.CLASSE.chave(), "HelloService", CamposMdc.METODO.chave(), "divide", CamposMdc.EXCECAO.chave(), "NullPointerException")
                .counter();
    }

    private String buscarCorpoMetrics() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(metricsUri).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }
}
