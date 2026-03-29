package br.com.vsjr.labs.observability.interceptor;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import br.com.vsjr.labs.observability.CamposMdc;
import br.com.vsjr.labs.observability.ValoresPadrao;
import br.com.vsjr.labs.observability.annotations.Logged;
import br.com.vsjr.labs.observability.context.GerenciadorContextoLog;
import br.com.vsjr.labs.observability.dsl.LOG;
import br.com.vsjr.labs.observability.security.LocalizacaoMetodo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * CDI Interceptor ativado por {@link Logged}.
 *
 * <p>
 * Para cada método interceptado:
 * </p>
 * <ol>
 * <li>Executa o pipeline de enriquecimento do contexto de logging
 * ({@code Chain of Responsibility})</li>
 * <li>Executa o método de negócio</li>
 * <li>Limpa os campos de localização do MDC no {@code finally}</li>
 * </ol>
 *
 * <p>
 * Os campos de correlação da requisição ({@code traceId}, {@code userId})
 * são responsabilidade do
 * {@link br.com.vsjr.labs.observability.filtro.LogContextoFiltro} e
 * permanecem intactos durante toda a execução da requisição.
 * </p>
 *
 * <p>
 * <b>Métricas:</b> registra duração de execução ({@code metodo.execucao}) e
 * falhas por tipo de exceção ({@code metodo.falha}) com isolamento de falha de
 * infraestrutura: qualquer erro de medição é apenas registrado em WARN e nunca
 * interrompe o fluxo de negócio.
 * </p>
 */
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class LogInterceptor {

    
    private final String applicationName;
    private final GerenciadorContextoLog gerenciador;
    private final MeterRegistry meterRegistry;

    public LogInterceptor(
            @ConfigProperty(name = "quarkus.application.name", defaultValue = ValoresPadrao.APPLICATION_PADRAO) String applicationName,
            GerenciadorContextoLog gerenciador, Instance<MeterRegistry> meterRegistryInstance) {
        this.applicationName = applicationName;
        this.gerenciador = gerenciador;
        this.meterRegistry = meterRegistryInstance.isResolvable() ? meterRegistryInstance.get() : null;
    }

    @AroundInvoke
    public Object interceptar(InvocationContext contexto) throws Exception {
        gerenciador.enriquecer(contexto);
        var sample = iniciarAmostra();
        try {
            return contexto.proceed(); 
        } catch (Throwable erro) {
            registrarFalha(contexto, erro);
            if (erro instanceof Exception excecao) {
                throw excecao;
            }
            if (erro instanceof Error falhaGrave) {
                throw falhaGrave;
            }
            throw new RuntimeException(erro);
        } finally {
            registrarExecucao(contexto, sample);
            gerenciador.limparEnriquecimento();
        }
    }

    private Timer.Sample iniciarAmostra() {
        if (meterRegistry == null) {
            return null;
        }
        return Timer.start(meterRegistry);
    }

    private void registrarFalha(InvocationContext contexto, Throwable erro) {
        if (meterRegistry == null) {
            return;
        }
        try {
            var localizacao = LocalizacaoMetodo.extrair(contexto);
            meterRegistry.counter(
                    applicationName + ".metodo.falha",
                    CamposMdc.CLASSE.chave(), localizacao.classeSimples(),
                    CamposMdc.METODO.chave(), localizacao.metodo(),
                    CamposMdc.EXCECAO.chave(), erro.getClass().getSimpleName()).increment();
        } catch (Exception metricaFalhou) {

            LOG.registrando("Registro de métrica de falha")
                    .em(this.getClass(), "registrarFalha")
                    .como("Métrica de falha")
                    .porque(String.format("Falha ao registrar métrica: %s", metricaFalhou.getMessage()))
                    .warn();
        }
    }

    private void registrarExecucao(InvocationContext contexto, Timer.Sample sample) {
        if (meterRegistry == null || sample == null) {
            return;
        }
        try {
            var localizacao = LocalizacaoMetodo.extrair(contexto);
            sample.stop(Timer.builder(applicationName + ".metodo.execucao")
                    .tag(CamposMdc.CLASSE.chave(), localizacao.classeSimples())
                    .tag(CamposMdc.METODO.chave(), localizacao.metodo())
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        } catch (Exception metricaFalhou) {

            LOG.registrando("Registro de métrica de execução")
                    .em(this.getClass(), "registrarExecucao")
                    .como("Métrica de execução")
                    .porque(String.format("Falha ao registrar métrica: %s", metricaFalhou.getMessage()))
                    .warn();
        }
    }

}