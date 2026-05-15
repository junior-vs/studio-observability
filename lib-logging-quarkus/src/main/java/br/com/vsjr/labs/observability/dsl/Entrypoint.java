package br.com.vsjr.labs.observability.dsl;

/**
 * Contrato publico para pontos de entrada canonicos da DSL de logging.
 *
 * <p>Aplicacoes consumidoras podem criar seus proprios enums implementando esta
 * interface quando os entrypoints padrao da biblioteca nao forem suficientes.</p>
 */
public interface Entrypoint {

    String getEntrypoint();
}
