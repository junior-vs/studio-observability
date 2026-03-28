package br.com.vsjr.labs.exemple.rest;

import br.com.vsjr.labs.observability.annotations.Logged;
import br.com.vsjr.labs.observability.annotations.Rastreado;
import br.com.vsjr.labs.observability.core.LogSistematico;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/**
 * Recurso REST de exemplo que demonstra o uso de {@link Logged} e {@link Rastreado}
 * em nível de método — em contraste com {@link HelloService}, que os aplica na classe.
 *
 * <p>O uso por método permite controle granular: apenas as operações anotadas
 * recebem interceptação de logging e rastreamento distribuído. Métodos sem
 * anotação (ex: {@link #divide}) executam sem overhead de interceptor.</p>
 *
 * <p>Funcionalidades demonstradas:</p>
 * <ul>
 *   <li>{@code @Logged} e {@code @Rastreado} em nível de método</li>
 *   <li>{@code info()} no fluxo principal ({@link #hello()})</li>
 *   <li>{@code debug()} com {@code comDetalhe()} e sanitização automática ({@link #buscarPedido})</li>
 *   <li>{@code erro()} na camada de recurso ({@link #divide})</li>
 * </ul>
 */
@Path("/hello")
public class HellowrdResource {

    HelloService helloService;

    public HellowrdResource(HelloService helloService) {
        this.helloService = helloService;
    }

    /**
     * Demonstra {@code @Logged} e {@code @Rastreado} em nível de método e o terminador {@code info()}.
     */
    @Logged
    @Rastreado
    @Path("/world")
    @GET
    public String hello() {
        LogSistematico
                .registrando("Recurso Hello World invocado")
                .em(HellowrdResource.class, "hello")
                .porque("Solicitação de saudação recebida")
                .como("API REST - GET /hello/world")
                .info();
        return helloService.sayHello();
    }

    /**
     * Demonstra {@code comDetalhe()} com mascaramento automático de dados sensíveis:
     *
     * <ul>
     *   <li>{@code token}    → {@code "****"} (credencial)</li>
     *   <li>{@code cpf}      → {@code "[PROTEGIDO]"} (dado pessoal)</li>
     *   <li>{@code pedidoId} → valor real (campo público)</li>
     * </ul>
     *
     * @param pedidoId identificador do pedido
     * @param token    token de autorização — mascarado no observability
     * @param cpf      CPF do solicitante — protegido no observability
     */
    @Logged
    @Rastreado
    @Path("/pedido")
    @GET
    public Response buscarPedido(
            @QueryParam("pedidoId") String pedidoId,
            @QueryParam("token") String token,
            @QueryParam("cpf") String cpf) {

        LogSistematico
                .registrando("Consulta de pedido recebida")
                .em(HellowrdResource.class, "buscarPedido")
                .porque("Chamada à API de consulta de pedido")
                .como("API REST - GET /hello/pedido")
                .comDetalhe("pedidoId", pedidoId)   // → valor real
                .comDetalhe("token", token)         // → "****"
                .comDetalhe("cpf", cpf)             // → "[PROTEGIDO]"
                .debug();

        return Response.ok(helloService.buscarPedido(pedidoId, token, cpf)).build();
    }

    /**
     * Método sem {@code @Logged} nem {@code @Rastreado} — demonstra que o controle
     * é por método. O observability é emitido diretamente via DSL, sem interceptação CDI.
     *
     * @param a dividendo
     * @param b divisor
     * @return resultado da divisão, ou {@code 0.0} em caso de erro
     */
    @Path("/divide")
    @POST
    public Double divide(@QueryParam("va") double a, @QueryParam("vb") double b) {
        try {
            return helloService.divide(a, b);
        } catch (Exception e) {
            LogSistematico
                    .registrando("Falha na operação de divisão")
                    .em(HellowrdResource.class, "divide")
                    .porque("Erro propagado do serviço de divisão")
                    .como("API REST - POST /hello/divide")
                    .comDetalhe("dividendo", a)
                    .comDetalhe("divisor", b)
                    .erro(e);
            return 0d;
        }
    }
}
