package br.com.vsjr.labs.observability.interceptor;

import br.com.vsjr.labs.observability.CamposMdc;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-004: verifica que falhas de infraestrutura de métricas não propagam para o
 * fluxo de negócio.
 *
 * <p>Quando o método lança exceção, o interceptor registra tanto a métrica de falha
 * quanto a de execução. Se alguma dessas operações falhar internamente, a exceção
 * original do método de negócio ainda DEVE propagar normalmente para o chamador.</p>
 *
 * <p>Este teste verifica o contrato end-to-end: a exceção original {@code NullPointerException}
 * propagada por {@code HelloService.divide(null, 1d)} é lançada ao chamador, E ambas as
 * métricas (timer + counter) são registradas — demonstrando que o interceptor não engole
 * a exceção de negócio mesmo com múltiplas operações de métricas no caminho.</p>
 */
@QuarkusTest
@TestProfile(MetricsEnabledTestProfile.class)
class LogInterceptorFaultIsolationTest {

    @Inject
    br.com.vsjr.labs.example.rest.HelloService helloService;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "quarkus.application.name")
    String applicationName;

    @Test
    void devePropagarExcecaoOriginalMesmoComMetricasAtivas() {
        var excecao = assertThrows(NullPointerException.class,
                () -> helloService.divide(null, 1d));

        assertNotNull(excecao);

        var timer = meterRegistry.find(applicationName + ".metodo.execucao")
                .tags(CamposMdc.CLASSE.chave(), "HelloService", CamposMdc.METODO.chave(), "divide")
                .timer();
        assertNotNull(timer, "Timer de execução deve ser registrado mesmo quando o método falha");
        assertTrue(timer.count() >= 1d);

        var counter = meterRegistry.find(applicationName + ".metodo.falha")
                .tags(CamposMdc.CLASSE.chave(), "HelloService", CamposMdc.METODO.chave(), "divide",
                        CamposMdc.EXCECAO.chave(), "NullPointerException")
                .counter();
        assertNotNull(counter, "Counter de falha deve ser registrado");
        assertTrue(counter.count() >= 1d);
    }

    @Test
    void deveRetornarResultadoCorretoQuandoMetodoComMetricasNaoFalha() throws Exception {
        var resultado = helloService.divide(10d, 2d);

        assertEquals(5d, resultado);

        var timer = meterRegistry.find(applicationName + ".metodo.execucao")
                .tags(CamposMdc.CLASSE.chave(), "HelloService", CamposMdc.METODO.chave(), "divide")
                .timer();
        assertNotNull(timer);
    }
}
