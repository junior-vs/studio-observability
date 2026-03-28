package br.com.vsjr.labs.observability.filtro;

import java.security.Principal;

import br.com.vsjr.labs.observability.context.GerenciadorContextoLog;
import br.com.vsjr.labs.observability.context.GerenciadorTracing;
import br.com.vsjr.labs.observability.core.LogSistematico;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;

/**
 * Filtro JAX-RS que gerencia o contexto de logging para requisições HTTP.
 *
 * <p>{@code @Provider} registra automaticamente o filtro no runtime do Quarkus —
 * sem necessidade de declaração em XML ou configuração adicional.</p>
 *
 * <p>Executa em duas fases:</p>
 * <ol>
 *   <li><b>Request</b>: extrai o usuário autenticado, inicializa o MDC com
 *       {@code userId} e {@code applicationName} via {@link GerenciadorContextoLog}, e
 *       sincroniza {@code traceId} e {@code spanId} do span OTel ativo via
 *       {@link GerenciadorTracing} — mantendo as responsabilidades separadas.</li>
 *   <li><b>Response</b>: limpa o MDC — obrigatório para evitar vazamento de
 *       contexto entre requisições no pool de threads do Vert.x.</li>
 * </ol>
 *
 * <p>Para ambientes reativos (RESTEasy Reactive + Mutiny), o MDC e o span OTel
 * são propagados automaticamente pelo SmallRye Context Propagation — habilitado
 * via {@code quarkus-smallrye-context-propagation} no pom.xml.</p>
 */
@Provider
public class LogContextoFiltro implements ContainerRequestFilter, ContainerResponseFilter {


    GerenciadorContextoLog gerenciador;
    GerenciadorTracing gerenciadorTracing;

    public LogContextoFiltro(GerenciadorContextoLog gerenciador,
                              GerenciadorTracing gerenciadorTracing) {
        this.gerenciador = gerenciador;
        this.gerenciadorTracing = gerenciadorTracing;
    }

    /**
     * Fase de requisição: inicializa o MDC antes de qualquer código de negócio.
     */
    @Override
    public void filter(ContainerRequestContext requestContext) {
        var userId = resolverUsuario(requestContext);
        var contexto = gerenciador.inicializar(userId);
        gerenciadorTracing.sincronizarMdcRequisicao();

        LogSistematico
                .registrando("Contexto de observability inicializado")
                .em(LogContextoFiltro.class, "filter")
                .comDetalhe("userId", contexto.userId())
                .debug();
    }

    /**
     * Fase de resposta: limpa o MDC independente de sucesso ou exceção.
     */
    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        gerenciador.limpar();
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    private String resolverUsuario(ContainerRequestContext requestContext) {

        return switch (requestContext.getSecurityContext()) {
            case null -> "anonimo";
            case SecurityContext sc when sc.getUserPrincipal() instanceof Principal p
                    && p.getName() != null -> p.getName();
            default -> "anonimo";
        };
    }
}