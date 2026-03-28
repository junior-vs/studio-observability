package br.com.vsjr.labs.exemple.context;

import br.com.vsjr.labs.observability.context.enriquecedor.logs.EnriquecedorContexto;
import br.com.vsjr.labs.observability.context.enriquecedor.logs.LocalizacaoEnriquecedorContexto;
import br.com.vsjr.labs.observability.context.enriquecedor.logs.UsuarioEnriquecedorContexto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

import java.util.Set;

/**
 * Exemplo de {@link EnriquecedorContexto} de negócio — adiciona ao MDC o nome
 * qualificado da operação em execução.
 *
 * <p>Demonstra como estender o pipeline de enriquecimento do MDC com contexto
 * de domínio, complementando os enriquecedores obrigatórios de infraestrutura
 * ({@link LocalizacaoEnriquecedorContexto} com prioridade {@code 10} e
 * {@link UsuarioEnriquecedorContexto} com prioridade {@code 20}).</p>
 *
 * <p>Campo adicionado ao MDC:</p>
 * <ul>
 *   <li>{@code operacao.nome} — nome composto {@code "Classe.metodo"}, visível
 *       em todos os logs emitidos durante a execução do método interceptado.</li>
 * </ul>
 *
 * <p>Prioridade {@code 100}: executa após os enriquecedores de infraestrutura,
 * garantindo que {@code classe} e {@code metodo} já estejam no MDC quando
 * este enriquecedor for ativado.</p>
 *
 * <p>A chave {@code operacao.nome} é removida automaticamente pelo
 * {@link br.com.vsjr.labs.observability.context.GerenciadorContextoLog#limparEnriquecimento()}
 * ao término do método interceptado — sem risco de contaminação entre threads.</p>
 *
 * <p><b>Registro automático:</b> como bean {@code @ApplicationScoped}, é descoberto
 * pelo CDI e incorporado ao pipeline sem nenhuma alteração no
 * {@link br.com.vsjr.labs.observability.context.GerenciadorContextoLog}.</p>
 */
@ApplicationScoped
public class EnriquecedorContextoOperacao implements EnriquecedorContexto {

    @Override
    public void enriquecer(InvocationContext contexto) {
        var metodo = contexto.getMethod();
        var nome = metodo.getDeclaringClass().getSimpleName() + "." + metodo.getName();
        MDC.put("operacao.nome", nome);
    }

    @Override
    public Set<String> chavesMdc() {
        return Set.of("operacao.nome");
    }

    @Override
    public int prioridade() {
        return 100;
    }
}
