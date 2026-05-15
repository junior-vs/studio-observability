package br.com.vsjr.labs.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArquiteturaGovernancaTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Set<String> ARQUIVOS_COM_MDC_AUTORIZADO = Set.of(
            "br/com/vsjr/labs/observability/context/GerenciadorContextoLog.java",
            "br/com/vsjr/labs/observability/context/enriquecedor/MetadadosEnriquecedorContexto.java",
            "br/com/vsjr/labs/observability/context/enriquecedor/SecurityIdentityEnriquecedorContexto.java",
            "br/com/vsjr/labs/observability/dsl/Log.java",
            "br/com/vsjr/labs/observability/interceptor/TracingInterceptor.java",
            "br/com/vsjr/labs/observability/tracing/GerenciadorTracing.java"
    );

    @Test
    void core_nao_deve_usar_saida_de_sistema_ou_stacktrace_direto() throws IOException {
        var violacoes = arquivosJava()
                .stream()
                .filter(path -> conteudo(path).contains("System.out")
                        || conteudo(path).contains("System.err")
                        || conteudo(path).contains(".printStackTrace("))
                .map(ArquiteturaGovernancaTest::normalizar)
                .toList();

        assertTrue(violacoes.isEmpty(), () -> "Uso de saida de sistema no core:\n" + String.join("\n", violacoes));
    }

    @Test
    void core_nao_deve_reintroduzir_contratos_removidos() throws IOException {
        var violacoes = arquivosJava()
                .stream()
                .filter(path -> {
                    var conteudo = conteudo(path);
                    return conteudo.contains("log_canal")
                            || conteudo.contains("@Rastreado")
                            || conteudo.contains("class Rastreado")
                            || conteudo.contains("interface Rastreado");
                })
                .map(ArquiteturaGovernancaTest::normalizar)
                .toList();

        assertTrue(violacoes.isEmpty(), () -> "Contratos removidos encontrados no core:\n" + String.join("\n", violacoes));
    }

    @Test
    void core_nao_deve_conter_pacote_de_exemplo() throws IOException {
        var violacoes = arquivosJava()
                .stream()
                .filter(path -> conteudo(path).contains("package br.com.vsjr.labs.example"))
                .map(ArquiteturaGovernancaTest::normalizar)
                .toList();

        assertTrue(violacoes.isEmpty(), () -> "Exemplos ainda empacotados no core:\n" + String.join("\n", violacoes));
    }

    @Test
    void uso_direto_de_mdc_deve_ficar_restrito_a_componentes_autorizados() throws IOException {
        var violacoes = arquivosJava()
                .stream()
                .filter(path -> conteudo(path).contains("import org.jboss.logging.MDC;"))
                .map(ArquiteturaGovernancaTest::normalizar)
                .filter(path -> !ARQUIVOS_COM_MDC_AUTORIZADO.contains(path))
                .toList();

        assertTrue(violacoes.isEmpty(), () -> "MDC direto fora dos componentes autorizados:\n" + String.join("\n", violacoes));
    }

    @Test
    void core_nao_deve_depender_de_componentes_exclusivos_do_modulo_de_exemplo() throws IOException {
        var pom = Files.readString(Path.of("pom.xml"));
        var dependenciasExemplo = List.of(
                "<artifactId>quarkus-rest-client</artifactId>",
                "<artifactId>quarkus-rest-client-jackson</artifactId>",
                "<artifactId>quarkus-scheduler</artifactId>",
                "<artifactId>quarkus-info</artifactId>",
                "<artifactId>quarkus-observability-devservices</artifactId>",
                "<artifactId>quarkus-jfr</artifactId>",
                "<artifactId>quarkus-hibernate-validator</artifactId>"
        );

        var violacoes = dependenciasExemplo.stream()
                .filter(pom::contains)
                .toList();

        assertTrue(violacoes.isEmpty(), () -> "Dependencias de exemplo no core:\n" + String.join("\n", violacoes));
    }

    private static List<Path> arquivosJava() throws IOException {
        try (var stream = Files.walk(MAIN_JAVA)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static String conteudo(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler " + path, e);
        }
    }

    private static String normalizar(Path path) {
        return MAIN_JAVA.relativize(path).toString().replace('\\', '/');
    }
}
