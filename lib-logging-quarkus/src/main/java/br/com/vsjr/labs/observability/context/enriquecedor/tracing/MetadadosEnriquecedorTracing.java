package br.com.vsjr.labs.observability.context.enriquecedor.tracing;

import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Enriquecedor obrigatório — metadados técnicos da invocação.
 *
 * <p>Prioridade {@code 10}: executa primeiro na cadeia, garantindo que os
 * atributos de identificação básica estejam presentes antes de qualquer
 * enriquecedor de negócio.</p>
 *
 * <p>Atributos adicionados seguindo as
 * <a href="https://opentelemetry.io/docs/specs/semconv/code/">OTel Code Semantic Conventions</a>:</p>
 * <ul>
 *   <li>{@code application.name} — nome da aplicação ({@code quarkus.application.name})</li>
 *   <li>{@code code.namespace} — nome qualificado da classe interceptada</li>
 *   <li>{@code code.function}  — nome do método interceptado</li>
 * </ul>
 */
@ApplicationScoped
public class MetadadosEnriquecedorTracing implements EnriquecedorTracing {

    String applicationName;

    public MetadadosEnriquecedorTracing(
            @ConfigProperty(name = "quarkus.application.name", defaultValue = "servico-desconhecido") String applicationName) {
        this.applicationName = applicationName;
    }

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        var metodo = contexto.getMethod();
        span.setAttribute("application.name", applicationName);
        span.setAttribute("code.namespace", metodo.getDeclaringClass().getName());
        span.setAttribute("code.function", metodo.getName());
    }

    @Override
    public int prioridade() {
        return 10;
    }
}
