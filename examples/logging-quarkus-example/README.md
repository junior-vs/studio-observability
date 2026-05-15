# logging-quarkus-example

Aplicacao Quarkus de exemplo para `lib-logging-quarkus`.

Este modulo demonstra:

- endpoints REST com `@Logged` e `@Traced`;
- DSL `Log.registrando(Event).em(...).como(Entrypoint)`;
- mascaramento automatico em `.comDetalhe(...)`;
- gauges Micrometer demonstrativos;
- enriquecedores CDI para MDC e tracing.

## Executar

```bash
mvn -pl examples/logging-quarkus-example quarkus:dev
```

Endpoints uteis:

```bash
curl http://localhost:8080/hello/world
curl "http://localhost:8080/hello/pedido?pedidoId=123&token=secret&cpf=000.000.000-00"
curl -X POST "http://localhost:8080/hello/divide?va=10&vb=2"
```

As configuracoes deste modulo sao demonstrativas e nao sao empacotadas no artefato principal da biblioteca.
