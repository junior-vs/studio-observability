package br.com.vsjr.labs.observability.context.enriquecedor.logs;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;

import java.util.Set;

/**
 * Enriquecedor obrigatório — localização técnica da invocação interceptada.
 *
 * <p>Prioridade {@code 10}: executa primeiro na cadeia, garantindo que os campos
 * de localização estejam no MDC antes de qualquer enriquecedor de negócio.</p>
 *
 * <p>Campos adicionados:</p>
 * <ul>
 *   <li>{@code classe} — nome simples da classe interceptada</li>
 *   <li>{@code metodo} — nome do método interceptado</li>
 * </ul>
 */
@ApplicationScoped
public class LocalizacaoEnriquecedorContexto implements EnriquecedorContexto {

    @Override
    public void enriquecer(InvocationContext contexto) {
        var metodo = contexto.getMethod();
        MDC.put("classe", metodo.getDeclaringClass().getSimpleName());
        MDC.put("metodo", metodo.getName());
    }

    @Override
    public Set<String> chavesMdc() {
        return Set.of("classe", "metodo");
    }

    @Override
    public int prioridade() {
        return 10;
    }
}
