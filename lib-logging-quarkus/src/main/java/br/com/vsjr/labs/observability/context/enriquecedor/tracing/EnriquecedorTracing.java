package br.com.vsjr.labs.observability.context.enriquecedor.tracing;

import br.com.vsjr.labs.observability.context.GerenciadorTracing;
import br.com.vsjr.labs.observability.context.enriquecedor.Priorizavel;
import io.opentelemetry.api.trace.Span;
import jakarta.interceptor.InvocationContext;

/**
 * Contrato do Pipeline de Enriquecimento de span.
 *
 * <p>Cada implementação contribui com atributos OTel ao span no momento da criação.
 * O {@link GerenciadorTracing} executa todos os enriquecedores descobertos via
 * CDI em ordem crescente de {@link #prioridade()}, sem interrupção funcional
 * da execução da cadeia.</p>
 *
 * <p><b>Acesso a parâmetros de negócio:</b> {@code contexto.getParameters()} expõe
 * os argumentos reais da invocação. Enriquecedores de negócio podem inspecioná-los
 * para extrair atributos relevantes (ex: ID de pedido, valor de pagamento).
 * Implemente de forma defensiva — a assinatura varia por método interceptado.</p>
 *
 * <p><b>Convenções de nomes de atributos:</b> use as
 * <a href="https://opentelemetry.io/docs/specs/semconv/">OTel Semantic Conventions</a>
 * para atributos técnicos e prefixos de domínio para atributos de negócio
 * (ex: {@code pagamento.valor}, {@code pedido.id}).</p>
 *
 * <p><b>Implementação mínima:</b></p>
 * <pre>{@code
 * @ApplicationScoped
 * public class EnriquecedorPagamento implements EnriquecedorSpan {
 *
 *     @Override
 *     public void enriquecer(Span span, InvocationContext contexto) {
 *         var parametros = contexto.getParameters();
 *         if (parametros != null && parametros.length > 0
 *                 && parametros[0] instanceof PagamentoRequest req) {
 *             span.setAttribute("pagamento.valor", req.valor().toString());
 *         }
 *     }
 *
 *     @Override
 *     public int prioridade() { return 100; }
 * }
 * }</pre>
 */
public interface EnriquecedorTracing extends Priorizavel {

    /**
     * Enriquece o span com atributos OTel.
     *
     * <p>Chamado após o span ser criado e marcado como ativo ({@code Span.makeCurrent()}).
     * Implemente de forma defensiva — verifique nulidade e tipos antes de acessar
     * {@code contexto.getParameters()}.</p>
     *
     * @param span     span ativo a ser enriquecido; nunca {@code null}
     * @param contexto contexto CDI da invocação; expõe método, parâmetros e target bean
     */
    void enriquecer(Span span, InvocationContext contexto);
}
