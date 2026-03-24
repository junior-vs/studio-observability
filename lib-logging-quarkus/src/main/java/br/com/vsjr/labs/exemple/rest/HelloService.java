package br.com.vsjr.labs.exemple.rest;

import br.com.vsjr.labs.log.annotations.Logged;
import br.com.vsjr.labs.log.dsl.LogSistematico;
import jakarta.enterprise.context.ApplicationScoped;

@Logged
@ApplicationScoped
public class HelloService {

    public String sayHello() {
        LogSistematico
                .registrando("Servico de hello")
                .em(HelloService.class, "sayHello")
                .porque("Solicitação do hello")
                .como("API REST - POST /hello")
                .info();

        return "Hello World!";
    }

    public Double divide(Double a, Double b) throws Exception {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            LogSistematico
                    .registrando("Erro de divisão")
                    .em(HelloService.class, "divide")
                    .porque("Divisão por zero")
                    .como("API REST - POST /divide")
                    .erro(e);
            return 0d;
        } catch (Exception e) {
            LogSistematico
                    .registrando("Erro inesperado")
                    .em(HelloService.class, "divide")
                    .porque("Erro inesperado durante divisão")
                    .como("API REST - POST /divide")
                    .erroERelanca(e);
            throw e; // rethrow after logging
        }
    }
}
