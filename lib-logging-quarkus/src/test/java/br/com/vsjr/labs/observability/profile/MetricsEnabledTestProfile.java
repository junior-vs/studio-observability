package br.com.vsjr.labs.observability.profile;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Perfil de teste que habilita o Micrometer para testes de métricas automáticas.
 *
 * <p>A ser usado com {@code @TestProfile(MetricsEnabledTestProfile.class)} nos testes
 * de {@code @Logged} que precisam verificar timers e counters. Mantém a exportação
 * Prometheus desabilitada para não requerer binding de porta durante os testes.</p>
 *
 * <pre>{@code
 * @QuarkusTest
 * @TestProfile(MetricsEnabledTestProfile.class)
 * class LogInterceptorMetricsTest {
 *     @Inject MeterRegistry meterRegistry;
 *     // ...
 * }
 * }</pre>
 */
public class MetricsEnabledTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.micrometer.enabled", "true",
                "quarkus.micrometer.export.prometheus.enabled", "false",
                "quarkus.micrometer.binder.jvm", "false",
                "quarkus.micrometer.binder.system", "false",
                "quarkus.micrometer.binder.http-server.enabled", "false"
        );
    }
}
