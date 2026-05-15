package br.com.vsjr.labs.example.tracing;

import br.com.vsjr.labs.observability.tracing.enriquecedor.EnriquecedorTracing;
import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.interceptor.InvocationContext;

/**
 * Enriquecedor de negócio — captura os operandos da operação de divisão como atributos do span.
 *
 * <p>Demonstra o acesso a {@link InvocationContext#getParameters()} para extrair
 * argumentos reais da invocação e expô-los como atributos OTel observáveis no
 * Jaeger/Grafana Tempo.</p>
 *
 * <p>Prioridade {@code 100}: executa após os enriquecedores obrigatórios de
 * infra ({@code 10} e {@code 20}), garantindo que {@code application.name} e
 * {@code enduser.id} já estejam presentes antes dos atributos de negócio.</p>
 *
 * <p>Atua <b>apenas</b> no método {@code divide} — o guard na assinatura do
 * método evita atribuição incorreta de atributos em outros pontos de entrada.</p>
 *
 * <p><b>Saída esperada no span:</b></p>
 * <pre>{@code
 * operacao.dividendo = "10.0"
 * operacao.divisor   = "2.0"
 * operacao.risco     = "false"      // true quando divisor == 0
 * }</pre>
 */
@ApplicationScoped
public class EnriquecedorOperacao implements EnriquecedorTracing {

    @Override
    public void enriquecer(Span span, InvocationContext contexto) {
        if (!"divide".equals(contexto.getMethod().getName())) {
            return;
        }

        var parametros = contexto.getParameters();
        if (parametros == null || parametros.length < 2) {
            return;
        }

        if (parametros[0] instanceof Double dividendo && parametros[1] instanceof Double divisor) {
            span.setAttribute("operacao.dividendo", dividendo.toString());
            span.setAttribute("operacao.divisor", divisor.toString());
            span.setAttribute("operacao.risco", divisor == 0.0);
        }
    }

    @Override
    public int prioridade() {
        return 100;
    }
}
