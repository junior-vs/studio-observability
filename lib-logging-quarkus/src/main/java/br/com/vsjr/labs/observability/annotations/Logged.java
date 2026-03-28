package br.com.vsjr.labs.observability.annotations;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.*;

/**
 * Ativa interceptação automática de logging para um bean ou método CDI.
 *
 * <p>Quando aplicada, o {@link br.com.vsjr.labs.observability.interceptor.LogInterceptor}
 * injeta no MDC: {@code userId}, {@code traceId}, {@code spanId}, {@code classe}
 * e {@code metodo}. Ao término, registra a duração da execução como métrica
 * Micrometer exposta em {@code /q/metrics}.</p>
 *
 * <p>Pode ser aplicada na classe (todos os métodos) ou em métodos específicos:</p>
 *
 * <pre>{@code
 * // Toda a classe
 * @ApplicationScoped
 * @Logged
 * public class PedidoService { ... }
 *
 * // Apenas um método
 * @ApplicationScoped
 * public class RelatorioService {
 *
 *     @Logged
 *     public Relatorio gerar(Long id) { ... }
 * }
 * }</pre>
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface Logged {
}