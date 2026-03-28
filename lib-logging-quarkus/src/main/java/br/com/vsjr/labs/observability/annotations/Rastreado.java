package br.com.vsjr.labs.observability.annotations;

import br.com.vsjr.labs.observability.interceptor.TracingInterceptor;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.*;

/**
 * Ativa rastreamento distribuído para um bean ou método CDI.
 *
 * <p>Quando aplicada, o {@link TracingInterceptor}
 * cria um Child Span OTel para cada invocação e atualiza o MDC com o {@code spanId}
 * do filho — garantindo que os logs emitidos durante a execução referenciem
 * o span correto no Jaeger/Grafana Tempo.</p>
 *
 * <p>Pode ser combinada com {@link Logged} para obter rastreamento distribuído
 * e enriquecimento de contexto de logging no mesmo método. Nesse caso,
 * {@code @Rastreado} executa primeiro ({@code Priority APPLICATION - 10}) e o {@code spanId} do filho já
 * está no MDC quando o {@link br.com.vsjr.labs.observability.interceptor.LogInterceptor}
 * registrar a localização do método.</p>
 *
 * <pre>{@code
 * // Apenas rastreamento
 * @ApplicationScoped
 * @Rastreado
 * public class IntegracaoFiscalClient { ... }
 *
 * // Rastreamento + contexto de logging
 * @ApplicationScoped
 * @Logged
 * @Rastreado
 * public class PagamentoService { ... }
 * }</pre>
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Rastreado {
}
