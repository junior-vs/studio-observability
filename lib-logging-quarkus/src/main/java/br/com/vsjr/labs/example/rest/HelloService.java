package br.com.vsjr.labs.example.rest;

import br.com.vsjr.labs.observability.annotations.Logged;
import br.com.vsjr.labs.observability.annotations.Rastreado;
import br.com.vsjr.labs.observability.dsl.LogSistematico;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Serviço de exemplo que demonstra todas as funcionalidades da DSL {@link LogSistematico}.
 *
 * <p>A anotação {@link Logged} na classe ativa o {@code LogInterceptor} em todos os
 * métodos: injeta {@code userId}, {@code traceId}, {@code spanId}, {@code classe} e
 * {@code metodo} no MDC.</p>
 *
 * <p>A anotação {@link Rastreado} cria um Child Span OTel para cada invocação,
 * garantindo correlação com Jaeger/Grafana Tempo.</p>
 *
 * <p>Funcionalidades demonstradas:</p>
 * <ul>
 *   <li>{@code info()} — evento operacional padrão ({@link #sayHello()})</li>
 *   <li>{@code debug()} e {@code warn()} — níveis de diagnóstico ({@link #buscarPedido})</li>
 *   <li>{@code comDetalhe()} com sanitização automática:
 *       {@code token} → {@code "****"}, {@code cpf} → {@code "[PROTEGIDO]"}</li>
 *   <li>{@code erro()} e {@code erroERelanca()} — tratamento de exceções ({@link #divide})</li>
 * </ul>
 */
@Logged
@Rastreado
@ApplicationScoped
public class HelloService {

    /**
     * Demonstra o terminador {@code info()} com as dimensões Why e How.
     */
    public String sayHello() {
        LogSistematico
                .registrando("Serviço Hello executado")
                .em(HelloService.class, "sayHello")
                .porque("Solicitação de saudação recebida")
                .como("API REST - GET /hello/world")
                .info();

        return "Hello World!";
    }

    /**
     * Demonstra {@code debug()}, {@code warn()} e mascaramento automático de dados sensíveis.
     *
     * <ul>
     *   <li>{@code token} → {@code "****"} (credencial)</li>
     *   <li>{@code cpf}   → {@code "[PROTEGIDO]"} (dado pessoal)</li>
     *   <li>{@code pedidoId} → valor real (campo público)</li>
     * </ul>
     *
     * @param pedidoId identificador do pedido; {@code null} ou vazio aciona {@code warn()}
     * @param token    token de autorização — mascarado automaticamente pelo {@code SanitizadorDados}
     * @param cpf      CPF do usuário — substituído por {@code "[PROTEGIDO]"}
     * @return resultado da busca
     */
    public String buscarPedido(String pedidoId, String token, String cpf) {
        if (pedidoId == null || pedidoId.isBlank()) {
            LogSistematico
                    .registrando("Identificador de pedido ausente")
                    .em(HelloService.class, "buscarPedido")
                    .porque("pedidoId nulo ou vazio recebido na requisição")
                    .como("API REST - GET /hello/pedido")
                    .comDetalhe("cpf", cpf)            // → "[PROTEGIDO]"
                    .warn();
            return "pedido não encontrado";
        }

        LogSistematico
                .registrando("Buscando pedido")
                .em(HelloService.class, "buscarPedido")
                .porque("Consulta de pedido solicitada")
                .como("API REST - GET /hello/pedido")
                .comDetalhe("pedidoId", pedidoId)      // → valor real
                .comDetalhe("token", token)            // → "****"
                .comDetalhe("cpf", cpf)                // → "[PROTEGIDO]"
                .debug();

        return "Pedido %s encontrado".formatted(pedidoId);
    }

    /**
     * Demonstra os terminadores {@code erro()} e {@code erroERelanca()}, além de
     * {@code comDetalhe()} com operandos numéricos visíveis.
     *
     * @param a dividendo
     * @param b divisor
     * @return resultado da divisão, ou {@code 0.0} em caso de {@link ArithmeticException}
     * @throws Exception relançada após observability em caso de erro inesperado
     */
    public Double divide(Double a, Double b) throws Exception {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            LogSistematico
                    .registrando("Erro de divisão")
                    .em(HelloService.class, "divide")
                    .porque("Divisão por zero detectada")
                    .como("API REST - POST /hello/divide")
                    .comDetalhe("dividendo", a)
                    .comDetalhe("divisor", b)
                    .erro(e);
            return 0d;
        } catch (Exception e) {
            LogSistematico
                    .registrando("Erro inesperado na divisão")
                    .em(HelloService.class, "divide")
                    .porque("Exceção não tratada durante a operação de divisão")
                    .como("API REST - POST /hello/divide")
                    .comDetalhe("dividendo", a)
                    .comDetalhe("divisor", b)
                    .erroERelanca(e);
            throw e;
        }
    }
}
