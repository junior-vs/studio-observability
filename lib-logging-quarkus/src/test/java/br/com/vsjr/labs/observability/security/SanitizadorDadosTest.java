package br.com.vsjr.labs.observability.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para {@link SanitizadorDados}.
 *
 * <p>Valida as três categorias de sensibilidade (credencial, dado pessoal e público)
 * e os casos de borda: chave nula, chave vazia, valor nulo e case-insensitive.</p>
 */
class SanitizadorDadosTest {

    // ── Credenciais ──────────────────────────────────────────────────────────

    @Test
    void token_deveMascarar() {
        assertEquals("****", SanitizadorDados.sanitizar("token", "meu-token-secreto"));
    }

    @Test
    void senha_deveMascarar() {
        assertEquals("****", SanitizadorDados.sanitizar("senha", "s3cr3t"));
    }

    @Test
    void password_deveMascarar() {
        assertEquals("****", SanitizadorDados.sanitizar("password", "abc123"));
    }

    @Test
    void apikey_deveMascarar() {
        assertEquals("****", SanitizadorDados.sanitizar("apikey", "xyz-key-456"));
    }

    @Test
    void credencial_emMaiuscula_deveMascarar() {
        assertEquals("****", SanitizadorDados.sanitizar("TOKEN", "valor-secreto"));
    }

    @Test
    void credencial_comEspacos_deveMascarar() {
        assertEquals("****", SanitizadorDados.sanitizar("  token  ", "valor"));
    }

    // ── Dados Pessoais ───────────────────────────────────────────────────────

    @Test
    void email_deveProteger() {
        assertEquals("[PROTEGIDO]", SanitizadorDados.sanitizar("email", "joao@exemplo.com"));
    }

    @Test
    void cpf_deveProteger() {
        assertEquals("[PROTEGIDO]", SanitizadorDados.sanitizar("cpf", "123.456.789-00"));
    }

    @Test
    void celular_deveProteger() {
        assertEquals("[PROTEGIDO]", SanitizadorDados.sanitizar("celular", "+5511999999999"));
    }

    // ── Valores Públicos ─────────────────────────────────────────────────────

    @Test
    void pedidoId_naoDeveMascarar() {
        assertEquals("ABC-123", SanitizadorDados.sanitizar("pedidoId", "ABC-123"));
    }

    @Test
    void valorNumerico_naoDeveMascarar() {
        assertEquals(42, SanitizadorDados.sanitizar("quantidade", 42));
    }

    // ── Casos de Borda ───────────────────────────────────────────────────────

    @Test
    void valorNulo_deveRetornarNulo() {
        assertNull(SanitizadorDados.sanitizar("token", null));
    }

    @Test
    void chaveNula_deveRetornarValorSemMascarar() {
        assertEquals("algumValor", SanitizadorDados.sanitizar(null, "algumValor"));
    }

    @Test
    void chaveVazia_deveRetornarValorSemMascarar() {
        assertEquals("algumValor", SanitizadorDados.sanitizar("", "algumValor"));
    }

    @Test
    void chaveApenasEspacos_deveRetornarValorSemMascarar() {
        assertEquals("algumValor", SanitizadorDados.sanitizar("   ", "algumValor"));
    }
}
