package br.com.vsjr.labs.observability.context;

import br.com.vsjr.labs.observability.CamposMdc;
import br.com.vsjr.labs.observability.ValoresPadrao;
import br.com.vsjr.labs.observability.context.enriquecedor.EnriquecedorContexto;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.interceptor.InvocationContext;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para {@link GerenciadorContextoLog}.
 *
 * <p>Cobre os comportamentos do contexto HTTP que seriam acionados pelo
 * {@code LogContextoFiltro}: inicialização com usuário autenticado,
 * coerção de {@code null} para {@code "anonimo"}, e limpeza do MDC
 * após a resposta ser enviada.</p>
 *
 * <p>O construtor recebe {@code Instance<EnriquecedorContexto>}; para os
 * testes de {@code inicializar()} e {@code limpar()} esse parâmetro é passado
 * como {@code null} pois não é acessado nesses fluxos.</p>
 */
class GerenciadorContextoLogTest {

    private static final String APP_TEST = "test-app";

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    // ── inicializar() ─────────────────────────────────────────────────────────

    @Test
    void inicializar_comUsuarioAutenticado_devePopularUserId() {
        var gerenciador = new GerenciadorContextoLog(APP_TEST, null);

        gerenciador.inicializar("user-42");

        assertEquals("user-42", MDC.get(CamposMdc.USER_ID.chave()),
                "userId autenticado deve ser gravado no MDC");
    }

    @Test
    void inicializar_comUsuarioAutenticado_devePopularApplicationName() {
        var gerenciador = new GerenciadorContextoLog(APP_TEST, null);

        gerenciador.inicializar("user-42");

        assertEquals(APP_TEST, MDC.get(CamposMdc.APPLICATION_NAME.chave()),
                "applicationName deve ser gravado no MDC");
    }

    @Test
    void inicializar_comUsuarioNulo_deveUsarAnonimo() {
        var gerenciador = new GerenciadorContextoLog(APP_TEST, null);

        var contexto = gerenciador.inicializar(null);

        assertEquals(ValoresPadrao.USUARIO_ANONIMO, MDC.get(CamposMdc.USER_ID.chave()),
                "userId null deve ser coercido para 'anonimo' no MDC");
        assertEquals(ValoresPadrao.USUARIO_ANONIMO, contexto.userId(),
                "LogContexto deve refletir 'anonimo'");
    }

    @Test
    void inicializar_deveRetornarLogContextoComValoresCorrespondentes() {
        var gerenciador = new GerenciadorContextoLog(APP_TEST, null);

        var contexto = gerenciador.inicializar("user-99");

        assertEquals("user-99", contexto.userId());
        assertEquals(APP_TEST, contexto.applicationName());
    }

    // ── limpar() ──────────────────────────────────────────────────────────────

    @Test
    void limpar_deveRemoverTodosOsCamposDoMdc() {
        var gerenciador = new GerenciadorContextoLog(APP_TEST, null);
        gerenciador.inicializar("user-42");

        gerenciador.limpar();

        assertNull(MDC.get(CamposMdc.USER_ID.chave()),
                "userId deve ser removido do MDC após limpar()");
        assertNull(MDC.get(CamposMdc.APPLICATION_NAME.chave()),
                "applicationName deve ser removido do MDC após limpar()");
        // Verifica via SLF4J (visão de string do MDC) que não restou nada
        var mapa = org.slf4j.MDC.getCopyOfContextMap();
        assertTrue(mapa == null || mapa.isEmpty(), "MDC deve estar vazio após limpar()");
    }

    @Test
    void limpar_deveFuncionarQuandoMdcJaEstaVazio() {
        var gerenciador = new GerenciadorContextoLog(APP_TEST, null);

        assertDoesNotThrow(gerenciador::limpar,
                "limpar() não deve lançar exceção quando o MDC já está vazio");
    }

    // ── LogContexto.VAZIO ─────────────────────────────────────────────────────

    @Test
    void logContextoVazio_deveConterValoresPadrao() {
        var vazio = GerenciadorContextoLog.LogContexto.VAZIO;

        assertEquals(ValoresPadrao.USUARIO_ANONIMO, vazio.userId());
        assertEquals(ValoresPadrao.LOCALIZACAO_DESCONHECIDA, vazio.applicationName());
    }

    // ── enriquecer() / limparEnriquecimento() — com enriquecedores reais ─────

    @Test
    void enriquecer_deveInvocarCadaEnriquecedorOrdenadoPorPrioridade() {
        var ordem = new java.util.ArrayList<Integer>();
        var enriquecedor1 = criarEnriquecedorComPrioridade(10, ordem);
        var enriquecedor2 = criarEnriquecedorComPrioridade(5, ordem);
        var gerenciador = new GerenciadorContextoLog(APP_TEST, instanceOf(enriquecedor1, enriquecedor2));

        gerenciador.enriquecer(null);

        assertEquals(java.util.List.of(5, 10), ordem,
                "Enriquecedores devem ser chamados em ordem crescente de prioridade");
    }

    @Test
    void escopo_de_enriquecimento_deve_restaurar_contexto_externo_apos_chamada_aninhada() {
        var chave = CamposMdc.CLASSE.chave();
        var gerenciador = new GerenciadorContextoLog(APP_TEST, instanceOf(enriquecedorQueDefine(chave, "interno")));
        MDC.put(chave, "externo");

        try (var escopo = gerenciador.abrirEscopoEnriquecimento(null)) {
            assertEquals("interno", MDC.get(chave));
        }

        assertEquals("externo", MDC.get(chave));
    }

    @Test
    void escopo_de_enriquecimento_deve_reverter_alteracoes_parciais_quando_enriquecedor_falhar() {
        var chave = CamposMdc.METODO.chave();
        var gerenciador = new GerenciadorContextoLog(APP_TEST, instanceOf(enriquecedorQueFalha(chave)));
        MDC.put(chave, "externo");

        assertDoesNotThrow(() -> {
            try (var escopo = gerenciador.abrirEscopoEnriquecimento(null)) {
                assertEquals("parcial", MDC.get(chave));
            }
        });

        assertEquals("externo", MDC.get(chave));
    }

    @Test
    void enriquecer_deve_relancar_falha_fatal() {
        var gerenciador = new GerenciadorContextoLog(APP_TEST, instanceOf(enriquecedorQueFalhaFatal()));

        assertThrows(LinkageError.class, () -> gerenciador.enriquecer(null));
    }

    @Test
    void limpar_deve_preservar_campos_que_nao_sao_da_biblioteca() {
        var gerenciador = new GerenciadorContextoLog(APP_TEST, null);
        MDC.put("integracao.externa", "preservar");
        gerenciador.inicializar("user-42");

        gerenciador.limpar();

        assertEquals("preservar", MDC.get("integracao.externa"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private EnriquecedorContexto criarEnriquecedorComPrioridade(int prioridade, java.util.List<Integer> ordemCaptura) {
        return new EnriquecedorContexto() {
            @Override
            public int prioridade() {
                return prioridade;
            }
            @Override
            public void enriquecer(InvocationContext ctx) {
                ordemCaptura.add(prioridade);
            }
            @Override
            public java.util.Set<String> chavesMdc() {
                return java.util.Set.of();
            }
        };
    }

    private EnriquecedorContexto enriquecedorQueDefine(String chave, String valor) {
        return new EnriquecedorContexto() {
            @Override public void enriquecer(InvocationContext ctx) { MDC.put(chave, valor); }
            @Override public java.util.Set<String> chavesMdc() { return java.util.Set.of(chave); }
            @Override public int prioridade() { return 10; }
        };
    }

    private EnriquecedorContexto enriquecedorQueFalha(String chave) {
        return new EnriquecedorContexto() {
            @Override public void enriquecer(InvocationContext ctx) {
                MDC.put(chave, "parcial");
                throw new IllegalStateException("falha no enriquecedor");
            }
            @Override public java.util.Set<String> chavesMdc() { return java.util.Set.of(chave); }
            @Override public int prioridade() { return 10; }
        };
    }

    private EnriquecedorContexto enriquecedorQueFalhaFatal() {
        return new EnriquecedorContexto() {
            @Override public void enriquecer(InvocationContext ctx) { throw new LinkageError("fatal"); }
            @Override public java.util.Set<String> chavesMdc() { return java.util.Set.of(); }
            @Override public int prioridade() { return 10; }
        };
    }

    /**
     * Cria um {@code Instance<EnriquecedorContexto>} simples para uso em testes.
     * Apenas o método {@code stream()} é utilizado pelo {@code GerenciadorContextoLog}.
     */
    @SuppressWarnings("unchecked")
    private Instance<EnriquecedorContexto> instanceOf(EnriquecedorContexto... enriquecedores) {
        return new Instance<>() {
            @Override
            public Instance<EnriquecedorContexto> select(Annotation... qualifiers) { return this; }
            @Override
            public <U extends EnriquecedorContexto> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { return (Instance<U>) this; }
            @Override
            public <U extends EnriquecedorContexto> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { return (Instance<U>) this; }
            @Override
            public boolean isUnsatisfied() { return false; }
            @Override
            public boolean isAmbiguous() { return false; }
            @Override
            public boolean isResolvable() { return true; }
            @Override
            public void destroy(EnriquecedorContexto instance) {}
            @Override
            public Handle<EnriquecedorContexto> getHandle() { return null; }
            @Override
            public Iterable<? extends Handle<EnriquecedorContexto>> handles() { return java.util.List.of(); }
            @Override
            public EnriquecedorContexto get() { return enriquecedores[0]; }
            @Override
            public Iterator<EnriquecedorContexto> iterator() { return java.util.Arrays.asList(enriquecedores).iterator(); }
            @Override
            public Stream<EnriquecedorContexto> stream() { return java.util.Arrays.stream(enriquecedores); }
        };
    }
}
