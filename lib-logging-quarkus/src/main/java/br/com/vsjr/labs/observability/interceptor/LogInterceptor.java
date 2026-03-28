package br.com.vsjr.labs.observability.interceptor;


import br.com.vsjr.labs.observability.annotations.Logged;
import br.com.vsjr.labs.observability.context.GerenciadorContextoLog;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;



/**
 * CDI Interceptor ativado por {@link Logged}.
 *
 * <p>Para cada método interceptado:</p>
 * <ol>
 *   <li>Executa o pipeline de enriquecimento do contexto de logging ({@code Chain of Responsibility})</li>
 *   <li>Executa o método de negócio</li>
 *   <li>Limpa os campos de localização do MDC no {@code finally}</li>
 * </ol>
 *
 * <p>Os campos de correlação da requisição ({@code traceId}, {@code userId})
 * são responsabilidade do {@link br.com.vsjr.labs.observability.filtro.LogContextoFiltro} e
 * permanecem intactos durante toda a execução da requisição.</p>
 *
 * <p><b>Métricas</u>:</b> serão implementadas em módulo separado, mantendo
 * a responsabilidade única deste interceptor.</p>
 */
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LogInterceptor {

    GerenciadorContextoLog gerenciador;

    public LogInterceptor(GerenciadorContextoLog gerenciador) {
        this.gerenciador = gerenciador;
    }

    @AroundInvoke
    public Object interceptar(InvocationContext contexto) throws Exception {
        gerenciador.enriquecer(contexto);
        try {
            return contexto.proceed();
        } finally {
            gerenciador.limparEnriquecimento();
        }
    }
}