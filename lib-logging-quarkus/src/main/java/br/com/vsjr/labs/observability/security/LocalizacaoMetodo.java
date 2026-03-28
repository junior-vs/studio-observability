package br.com.vsjr.labs.observability.security;

import jakarta.interceptor.InvocationContext;

import java.util.Objects;

/**
 * Centraliza o formato de localização técnica usado por logging e tracing.
 */
public final class LocalizacaoMetodo {

    private LocalizacaoMetodo() {
    }

    public static Localizacao extrair(InvocationContext contexto) {
        var metodo = Objects.requireNonNull(contexto, "contexto nao pode ser nulo").getMethod();
        return of(metodo.getDeclaringClass(), metodo.getName());
    }

    public static Localizacao of(Class<?> classe, String metodo) {
        var classeNaoNula = Objects.requireNonNull(classe, "classe nao pode ser nula");
        var metodoNaoNulo = Objects.requireNonNull(metodo, "metodo nao pode ser nulo");
        return new Localizacao(classeNaoNula.getSimpleName(), classeNaoNula.getName(), metodoNaoNulo);
    }

    public record Localizacao(
            String classeSimples,
            String classeQualificada,
            String metodo
    ) {
        public String operacao() {
            return classeSimples + "." + metodo;
        }
    }
}
