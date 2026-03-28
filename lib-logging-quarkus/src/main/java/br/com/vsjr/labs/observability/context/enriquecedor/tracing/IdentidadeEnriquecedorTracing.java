package br.com.vsjr.labs.observability.context.enriquecedor.tracing;

import io.opentelemetry.api.trace.Span;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;

/**
 * Enriquecedor opcional — identidade do usuário autenticado.
 *
 * <p>Prioridade {@code 20}: executa após {@link MetadadosEnriquecedorTracing}.</p>
 *
 * <p>Atributos adicionados (OTel semantic conventions):</p>
 * <ul>
 *   <li>{@code enduser.id} — nome do principal autenticado;
 *       omitido quando a requisição é anônima.</li>
 * </ul>
 *
 * <p>A injeção de {@link SecurityIdentity} é segura mesmo sem extensão de segurança
 * configurada — o Quarkus provê uma identidade anônima nesse caso, nunca {@code null}.</p>
 */
@ApplicationScoped
public class IdentidadeEnriquecedorTracing implements EnriquecedorTracing {

    SecurityIdentity identidade;

    public IdentidadeEnriquecedorTracing(SecurityIdentity identidade) {
        this.identidade = identidade;
    }

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        if (!identidade.isAnonymous()) {
            span.setAttribute("enduser.id", identidade.getPrincipal().getName());
        }
    }

    @Override
    public int prioridade() {
        return 20;
    }
}
