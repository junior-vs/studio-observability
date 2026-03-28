package br.com.vsjr.labs.observability.security;

import java.util.Set;

/**
 * Mascara valores associados a chaves sensíveis antes do registro.
 *
 * <p>Aplica três graus de tratamento segundo o princípio de <em>data minimization</em>:</p>
 * <ul>
 *   <li><b>Credenciais</b> (senha, token): substituídas por {@code "****"}</li>
 *   <li><b>Dados pessoais</b> (CPF, e-mail): substituídos por {@code "[PROTEGIDO]"}</li>
 *   <li><b>Demais valores</b>: registrados normalmente</li>
 * </ul>
 *
 * <p>Usa <em>pattern matching</em> com {@code switch} do Java 21 para
 * distinguir categorias sem cadeia de {@code if-else}.</p>
 */
public final class SanitizadorDados {

    private static final Set<String> CHAVES_CREDENCIAIS = Set.of(
            "password", "senha", "secret",
            "token", "accesstoken", "refreshtoken",
            "authorization", "apikey", "cvv"
    );

    private static final Set<String> CHAVES_DADOS_PESSOAIS = Set.of(
            "cpf", "rg", "email", "celular",
            "cardnumber", "numerocartao"
    );

    private SanitizadorDados() {}

    /**
     * Retorna o valor sanitizado conforme a sensibilidade da chave.
     *
     * @param chave nome do campo
     * @param valor valor original
     * @return valor seguro para registro em observability
     */
    public static Object sanitizar(String chave, Object valor) {
        if (valor == null) return null;

        // Java 21: pattern matching com switch — categoriza por tipo de sensibilidade
        return switch (classificar(chave)) {
            case CREDENCIAL    -> "****";
            case DADO_PESSOAL  -> "[PROTEGIDO]";
            case PUBLICO       -> valor;
        };
    }

    // ── Tipos de sensibilidade ────────────────────────────────────────────────

    private enum Sensibilidade { CREDENCIAL, DADO_PESSOAL, PUBLICO }

    private static Sensibilidade classificar(String chave) {
        var chaveLower = chave.toLowerCase();
        if (CHAVES_CREDENCIAIS.contains(chaveLower))   return Sensibilidade.CREDENCIAL;
        if (CHAVES_DADOS_PESSOAIS.contains(chaveLower)) return Sensibilidade.DADO_PESSOAL;
        return Sensibilidade.PUBLICO;
    }
}