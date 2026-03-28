package br.com.vsjr.labs.observability.context.enriquecedor.logs;

import br.com.vsjr.labs.observability.context.GerenciadorContextoLog;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enriquecedor opcional — perfis do usuário autenticado.
 *
 * <p>Prioridade {@code 20}: executa após {@link LocalizacaoEnriquecedorContexto}.</p>
 *
 * <p>Campo adicionado:</p>
 * <ul>
 *   <li>{@code usuario.perfis} — lista de papéis (roles) separada por vírgula;
 *       omitido quando a requisição é anônima ou quando o usuário não possui papéis.</li>
 * </ul>
 *
 * <p>Complementa o {@code userId} registrado pelo {@link GerenciadorContextoLog}
 * no nível da requisição HTTP, adicionando a dimensão de <em>autorização</em>
 * ao observability: não apenas quem executou, mas com quais permissões.</p>
 *
 * <p>A injeção de {@link SecurityIdentity} é segura mesmo sem extensão de
 * segurança configurada — o Quarkus provê identidade anônima, nunca {@code null}.</p>
 */
@ApplicationScoped
public class UsuarioEnriquecedorContexto implements EnriquecedorContexto {

    SecurityIdentity identidade;

    public UsuarioEnriquecedorContexto(SecurityIdentity identidade) {
        this.identidade = identidade;
    }

    @Override
    public void enriquecer(InvocationContext contexto) {
        if (identidade.isAnonymous()) {
            return;
        }
        var perfis = identidade.getRoles().stream()
                .sorted()
                .collect(Collectors.joining(","));
        if (!perfis.isBlank()) {
            MDC.put("usuario.perfis", perfis);
        }
    }

    @Override
    public Set<String> chavesMdc() {
        return Set.of("usuario.perfis");
    }

    @Override
    public int prioridade() {
        return 20;
    }
}
