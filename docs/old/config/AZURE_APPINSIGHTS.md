## Apêndice — Azure Application Insights

> **Documentos relacionados:**
> - [Métricas](METRICS.md) — tipos de medidores, Micrometer e padrões de Gauge
> - [Rastreamento Distribuído](DISTRIBUTED_TRACING.md) — pipeline OTel e `RastreamentoInterceptor`
> - [Padrões de Codificação](CODING_STANDARDS.md) — falhas de infraestrutura de observabilidade não propagam como exceção de negócio

O Azure Application Insights é um backend de observabilidade gerenciado que recebe os três sinais — traces, métricas e logs — e os correlaciona em uma interface unificada. Para microsserviços Quarkus, há três abordagens de integração, com trade-offs distintos de suporte, compatibilidade com GraalVM e controle de configuração.

---

### Comparação das Três Abordagens

| Critério | `quarkus-opentelemetry-exporter-azure` | OTel puro via OTLP | Java Agent Microsoft |
|---|---|---|---|
| **Suporte** | Comunidade Quarkus (Quarkiverse) | Comunidade OTel | Microsoft (suporte oficial) |
| **GraalVM native** | ✅ Suportado | ✅ Suportado | ❌ Não suportado — apenas JVM |
| **Configuração** | `application.properties` | `application.properties` + OTel Collector | `applicationinsights.json` + JVM arg |
| **Cobertura automática** | Traces + Métricas + Logs | Traces + Métricas + Logs | Traces + Métricas + Logs + auto-instrumentação extra |
| **Micrometer** | `quarkus-micrometer-opentelemetry` | `quarkus-micrometer-opentelemetry` | Coletado automaticamente pelo agente |
| **Quando usar** | Quarkus JVM ou native, setup mínimo | Pipeline OTel Collector já existente | JVM apenas, máxima cobertura automática |

> **Recomendação para o projeto:** para aplicações JVM, o Java Agent oferece a maior cobertura automática com mínimo código. Para native image (GraalVM), use `quarkus-opentelemetry-exporter-azure`. Para ambientes com OTel Collector já no pipeline, use a abordagem OTLP pura.

---

### Abordagem 1 — `quarkus-opentelemetry-exporter-azure` (Quarkiverse)

A extensão do Quarkiverse encapsula o exportador OTel para Azure Application Insights diretamente no ciclo de vida do Quarkus — sem OTel Collector intermediário, sem agente externo. É a abordagem recomendada para native image.

<br>

#### 1.1. Dependências Maven

```xml
<!-- ─── OpenTelemetry core + exportador Azure ─────────────────────────────── -->
<dependency>
    <groupId>io.quarkiverse.opentelemetry.exporter</groupId>
    <artifactId>quarkus-opentelemetry-exporter-azure</artifactId>
    <version>3.31.4.0</version>
    <!-- Versão alinhada com Quarkus 3.27+ — verificar compatibilidade em:
         https://docs.quarkiverse.io/quarkus-opentelemetry-exporter/dev/ -->
</dependency>

<!--
    A extensão acima já puxa quarkus-opentelemetry transitivamente.
    Para métricas via Micrometer enviadas ao mesmo pipeline OTel:
-->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-opentelemetry</artifactId>
</dependency>
```

> **`quarkus-micrometer-opentelemetry` vs. `quarkus-micrometer-registry-prometheus`:** as duas extensões são mutuamente exclusivas para o mesmo backend. Use `quarkus-micrometer-opentelemetry` para enviar métricas Micrometer ao Application Insights pelo mesmo pipeline OTel. Use `quarkus-micrometer-registry-prometheus` apenas se precisar manter um endpoint Prometheus local em paralelo — não combine as duas para o mesmo backend.

<br>

#### 1.2. Configuração (`application.properties`)

```properties
# ─── Identidade do serviço — obrigatório para Application Map ─────────────────
# Cloud Role Name: identificador do serviço no mapa de aplicação do App Insights.
# Usa service.namespace + service.name. Se service.namespace não estiver definido,
# usa service.name diretamente.
quarkus.application.name=pedido-service
quarkus.otel.resource.attributes=\
    service.name=pedido-service,\
    service.namespace=plataforma-comercial,\
    service.instance.id=${HOSTNAME:localhost},\
    deployment.environment=${QUARKUS_PROFILE:dev}

# ─── Sinais habilitados ───────────────────────────────────────────────────────
quarkus.otel.enabled=true
quarkus.otel.traces.enabled=true
quarkus.otel.metrics.enabled=true
quarkus.otel.logs.enabled=true          # requer Quarkus 3.16+

# ─── Connection String — duas formas equivalentes ────────────────────────────
# Forma Quarkus (recomendada — integra-se ao sistema de profiles de config):
quarkus.otel.azure.applicationinsights.connection-string=${APPLICATIONINSIGHTS_CONNECTION_STRING}

# Forma Azure (tem precedência sobre a forma Quarkus se ambas estiverem definidas):
# APPLICATIONINSIGHTS_CONNECTION_STRING=InstrumentationKey=...;IngestionEndpoint=...

# ─── Sampling de traces ───────────────────────────────────────────────────────
# parentbased_always_on: respeita decisão do pai; se não houver pai, sempre amostra
# parentbased_traceidratio: respeita decisão do pai; se não houver pai, usa ratio
# Para produção com volume alto, usar parentbased_traceidratio com ratio < 1.0
%dev.quarkus.otel.traces.sampler=always_on
%prod.quarkus.otel.traces.sampler=parentbased_traceidratio
%prod.quarkus.otel.traces.sampler.arg=0.20      # 20% dos traces em produção

# ─── Exportação em batch ──────────────────────────────────────────────────────
# Batch reduz chamadas de rede — padrão adequado para produção.
# Para ambientes serverless onde a instância pode terminar abruptamente,
# considerar quarkus.otel.simple=true (exportação síncrona por span).
quarkus.otel.bsp.schedule.delay=5000ms          # intervalo de flush (padrão: 5s)
quarkus.otel.bsp.max.export.batch.size=512       # spans por batch
quarkus.otel.bsp.export.timeout=30s             # timeout de exportação

# ─── Métricas (Micrometer via OTel) ──────────────────────────────────────────
quarkus.micrometer.enabled=true
quarkus.micrometer.binder.http-server.enabled=true
quarkus.micrometer.binder.http-server.ignore-patterns=/q/health.*,/q/metrics
quarkus.micrometer.binder.jvm=true
# Intervalo de exportação de métricas para o Application Insights (padrão: 60s)
# quarkus.otel.metric.export.interval=60s

# ─── JDBC telemetry ───────────────────────────────────────────────────────────
# Instrumenta automaticamente consultas SQL com spans — requer quarkus-jdbc
quarkus.datasource.jdbc.telemetry=true

# ─── Dev/test: exportação local para console ──────────────────────────────────
%dev.quarkus.otel.logs.exporter=logging
%dev.quarkus.otel.traces.exporter=logging
%test.quarkus.otel.enabled=false
```

<br>

#### 1.3. Variáveis de Ambiente (produção / container)

```bash
# Connection string do Application Insights — obtida no portal Azure:
# Application Insights → Overview → Connection String
APPLICATIONINSIGHTS_CONNECTION_STRING="InstrumentationKey=<key>;IngestionEndpoint=https://<region>.in.applicationinsights.azure.com/;LiveEndpoint=https://<region>.livediagnostics.monitor.azure.com/"

# Cloud Role Instance — identifica a instância no Application Map.
# Em Kubernetes, o nome do pod é o valor natural:
HOSTNAME=<nome-do-pod>
```

<br>

#### 1.4. Suporte a GraalVM Native

A extensão `quarkus-opentelemetry-exporter-azure` é totalmente compatível com compilação nativa. Nenhuma configuração adicional de hints de reflexão é necessária — a extensão já os registra no build-time do Quarkus.

```dockerfile
# Exemplo: Dockerfile para native image com Application Insights
FROM quay.io/quarkus/quarkus-micro-image:2.0
COPY --chown=1001:root target/*-runner /work/application
RUN chmod 775 /work

# A connection string é passada via variável de ambiente — sem JAR de agente
ENV APPLICATIONINSIGHTS_CONNECTION_STRING=""

EXPOSE 8080
USER 1001
ENTRYPOINT ["/work/application", "-Dquarkus.http.host=0.0.0.0"]
```

---

### Abordagem 2 — OpenTelemetry Puro via OTel Collector

Nesta abordagem, a aplicação Quarkus envia telemetria pelo protocolo OTLP para um **OTel Collector** intermediário, que a roteia para o Application Insights. O Collector é um processo separado — um sidecar no pod Kubernetes ou um serviço dedicado.

**Quando preferir esta abordagem:** o OTel Collector já faz parte do pipeline de observabilidade (ex: para rotear traces para Jaeger/Tempo em dev e para Application Insights em produção, sem alterar código); ou quando a organização exige um ponto central de coleta com capacidade de filtrar, enriquecer e rotear para múltiplos backends.

<br>

#### 2.1. Dependências Maven

```xml
<!-- OTel nativo do Quarkus — sem extensão Quarkiverse -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>

<!-- Métricas Micrometer exportadas via OTel -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-opentelemetry</artifactId>
</dependency>
```

<br>

#### 2.2. Configuração Quarkus (`application.properties`)

```properties
# ─── Identidade do serviço ────────────────────────────────────────────────────
quarkus.application.name=pedido-service
quarkus.otel.resource.attributes=\
    service.name=pedido-service,\
    service.namespace=plataforma-comercial,\
    service.instance.id=${HOSTNAME:localhost},\
    deployment.environment=${QUARKUS_PROFILE:dev}

# ─── Sinais ───────────────────────────────────────────────────────────────────
quarkus.otel.enabled=true
quarkus.otel.traces.enabled=true
quarkus.otel.metrics.enabled=true
quarkus.otel.logs.enabled=true

# ─── Endpoint OTLP → OTel Collector ──────────────────────────────────────────
# Endpoint unificado (traces + métricas + logs pelo mesmo receptor):
%prod.quarkus.otel.exporter.otlp.endpoint=http://otel-collector:4317
%prod.quarkus.otel.exporter.otlp.protocol=grpc

# Endpoints por sinal (quando o Collector usa receptores separados):
# %prod.quarkus.otel.exporter.otlp.traces.endpoint=http://otel-collector:4317
# %prod.quarkus.otel.exporter.otlp.metrics.endpoint=http://otel-collector:4317
# %prod.quarkus.otel.exporter.otlp.logs.endpoint=http://otel-collector:4317

# ─── Sampling ─────────────────────────────────────────────────────────────────
%dev.quarkus.otel.traces.sampler=always_on
%prod.quarkus.otel.traces.sampler=parentbased_traceidratio
%prod.quarkus.otel.traces.sampler.arg=0.20

# ─── Métricas ─────────────────────────────────────────────────────────────────
quarkus.micrometer.enabled=true
quarkus.micrometer.binder.http-server.enabled=true
quarkus.micrometer.binder.http-server.ignore-patterns=/q/health.*,/q/metrics
quarkus.micrometer.binder.jvm=true
quarkus.datasource.jdbc.telemetry=true

# ─── Dev: Collector local ─────────────────────────────────────────────────────
%dev.quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
%test.quarkus.otel.enabled=false
```

<br>

#### 2.3. Configuração do OTel Collector (`otel-collector-config.yaml`)

O Collector recebe telemetria via OTLP e exporta para o Application Insights usando o exportador `azuremonitor`:

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:
    # Agrupa spans/métricas/logs antes de exportar — reduz requisições HTTP ao App Insights
    timeout: 5s
    send_batch_size: 512

  memory_limiter:
    # Proteção contra OOM no Collector — descarta dados se memória exceder o limite
    check_interval: 1s
    limit_mib: 512
    spike_limit_mib: 128

  resource:
    # Enriquece todos os sinais com atributos de infraestrutura
    attributes:
      - action: insert
        key: cloud.provider
        value: azure
      - action: insert
        key: cloud.region
        from_attribute: AZURE_REGION     # variável de ambiente do pod/container

exporters:
  azuremonitor:
    connection_string: "${env:APPLICATIONINSIGHTS_CONNECTION_STRING}"
    maxbatchsize: 1000
    maxbatchinterval: 10s

  # Exporter de debug — remover em produção
  debug:
    verbosity: basic

service:
  pipelines:
    traces:
      receivers:  [otlp]
      processors: [memory_limiter, batch, resource]
      exporters:  [azuremonitor]

    metrics:
      receivers:  [otlp]
      processors: [memory_limiter, batch, resource]
      exporters:  [azuremonitor]

    logs:
      receivers:  [otlp]
      processors: [memory_limiter, batch, resource]
      exporters:  [azuremonitor]
```

<br>

#### 2.4. Deployment Kubernetes — Sidecar

```yaml
# Fragmento do Deployment — OTel Collector como sidecar no mesmo pod
spec:
  containers:
    - name: pedido-service
      image: pedido-service:latest
      env:
        - name: QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT
          value: "http://localhost:4317"   # sidecar no mesmo pod → localhost
        - name: QUARKUS_PROFILE
          value: prod

    - name: otel-collector
      image: otel/opentelemetry-collector-contrib:latest
      args: ["--config=/conf/otel-collector-config.yaml"]
      env:
        - name: APPLICATIONINSIGHTS_CONNECTION_STRING
          valueFrom:
            secretKeyRef:
              name: appinsights-secret
              key: connection-string
      volumeMounts:
        - name: otel-collector-config
          mountPath: /conf

  volumes:
    - name: otel-collector-config
      configMap:
        name: otel-collector-config
```

> **Sidecar vs. Collector central:** o sidecar elimina tráfego de rede entre pods para telemetria — cada pod tem seu próprio Collector. Um Collector central (DaemonSet ou Deployment dedicado) centraliza configuração e reduz o número de processos, mas introduz um ponto único de falha para telemetria. Para produção com Kubernetes, o DaemonSet é o padrão mais comum.

---

### Abordagem 3 — Microsoft Application Insights Java Agent (JVM only)

O agente Java da Microsoft (`applicationinsights-agent-3.7.8.jar`) é um agente bytecode que se anexa à JVM antes da inicialização da aplicação. Ele instrumenta automaticamente dezenas de bibliotecas — HTTP clients, JDBC, Kafka, Redis, e o próprio Vert.x usado pelo Quarkus — sem nenhuma linha de código adicional.

**Restrição crítica: não é compatível com GraalVM native image.** O agente depende de manipulação de bytecode em tempo de execução, incompatível com a compilação AOT do GraalVM. Para native image, use a Abordagem 1.

<br>

#### 3.1. Download e Empacotamento do JAR

O agente não é uma dependência de compile/runtime — é um JAR externo que acompanha o deploy. Para gerenciar o download e cópia via Maven:

```xml
<dependency>
    <groupId>com.microsoft.azure</groupId>
    <artifactId>applicationinsights-agent</artifactId>
    <version>3.7.8</version>
    <scope>provided</scope>   <!-- não empacotado no uber-jar — apenas para download -->
</dependency>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>copy-appinsights-agent</id>
            <phase>package</phase>
            <goals><goal>copy</goal></goals>
            <configuration>
                <artifactItems>
                    <artifactItem>
                        <groupId>com.microsoft.azure</groupId>
                        <artifactId>applicationinsights-agent</artifactId>
                        <version>3.7.8</version>
                        <destFileName>applicationinsights-agent.jar</destFileName>
                        <outputDirectory>${project.build.directory}</outputDirectory>
                    </artifactItem>
                </artifactItems>
            </configuration>
        </execution>
    </executions>
</plugin>
```

<br>

#### 3.2. Configuração do Agente (`applicationinsights.json`)

O arquivo deve estar no mesmo diretório que o JAR do agente. Todas as chaves são opcionais exceto `connectionString`:

```json
{
  "connectionString": "${APPLICATIONINSIGHTS_CONNECTION_STRING}",

  "role": {
    "name": "pedido-service",
    "instance": "${HOSTNAME}"
  },

  "sampling": {
    "percentage": 20
  },

  "instrumentation": {
    "logging": {
      "level": "WARN"
    },
    "micrometer": {
      "enabled": true
    },
    "jdbc": {
      "enabled": true
    },
    "kafka": {
      "enabled": true
    },
    "vertx": {
      "enabled": true
    },
    "springScheduling": {
      "enabled": false
    }
  },

  "heartbeat": {
    "intervalSeconds": 900
  },

  "customDimensions": {
    "environment": "${QUARKUS_PROFILE}",
    "version": "${QUARKUS_APPLICATION_VERSION}"
  }
}
```

> **`vertx.enabled: true`:** o Quarkus usa o Vert.x como camada de rede. Sem esta flag, spans de operações assíncronas do Vert.x não aparecem no Application Map. É essencial para visibilidade completa em aplicações Quarkus Reactive.

<br>

#### 3.3. Ativação do Agente

**Linha de comando (JVM direto):**

```bash
java -javaagent:/path/to/applicationinsights-agent.jar \
     -jar target/pedido-service-runner.jar
```

**Quarkus Maven Plugin (dev mode com agente):**

```xml
<plugin>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-maven-plugin</artifactId>
    <configuration>
        <jvmArgs>
            -javaagent:${project.build.directory}/applicationinsights-agent.jar
        </jvmArgs>
    </configuration>
</plugin>
```

**Dockerfile (JVM mode):**

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app

# JAR do agente e arquivo de configuração copiados junto com a aplicação
COPY target/applicationinsights-agent.jar applicationinsights-agent.jar
COPY applicationinsights.json applicationinsights.json
COPY target/quarkus-app/ quarkus-app/

ENV APPLICATIONINSIGHTS_CONNECTION_STRING=""

ENTRYPOINT ["java", \
    "-javaagent:/app/applicationinsights-agent.jar", \
    "-jar", "/app/quarkus-app/quarkus-run.jar"]
```

**Kubernetes — agente via `JAVA_TOOL_OPTIONS`:**

```yaml
env:
  - name: APPLICATIONINSIGHTS_CONNECTION_STRING
    valueFrom:
      secretKeyRef:
        name: appinsights-secret
        key: connection-string
  - name: JAVA_TOOL_OPTIONS
    value: "-javaagent:/app/applicationinsights-agent.jar"
```

> **`JAVA_TOOL_OPTIONS` vs. `-javaagent` no ENTRYPOINT:** `JAVA_TOOL_OPTIONS` é reconhecida pela JVM antes mesmo do comando ser executado — útil em imagens base onde não se controla o ENTRYPOINT. Em imagens próprias, é preferível declarar explicitamente no ENTRYPOINT para tornar a intenção visível no Dockerfile.

<br>

#### 3.4. Integração com Micrometer

O agente coleta automaticamente todas as métricas Micrometer registradas no `MeterRegistry` sem nenhuma configuração adicional. O `quarkus-micrometer-registry-prometheus` pode coexistir — o agente coleta do Micrometer internamente, o Prometheus coleta pelo endpoint `/q/metrics`. Não há conflito.

```properties
# application.properties — nenhuma config OTel necessária com o agente.
# O agente intercepta a JVM antes mesmo da inicialização do Quarkus.

quarkus.micrometer.enabled=true
quarkus.micrometer.binder.http-server.enabled=true
quarkus.micrometer.binder.jvm=true

# JDBC telemetry via OTel do Quarkus — opcional, o agente já instrumenta JDBC
# quarkus.datasource.jdbc.telemetry=true
```

---

### Campos Enviados ao Application Insights

Independente da abordagem, os campos canônicos do projeto mapeiam para campos do Application Insights da seguinte forma:

| Campo canônico (MDC/OTel) | Campo no Application Insights | Localização na UI |
|---|---|---|
| `traceId` | `operation_Id` | Transaction Search → Operation ID |
| `spanId` | `id` (dentro da operação) | End-to-end transaction details |
| `service.name` | `cloud_RoleName` | Application Map → nó do serviço |
| `service.instance.id` | `cloud_RoleInstance` | Application Map → instância |
| `deployment.environment` | Custom dimension `environment` | Logs → customDimensions |
| `userId` | `user_Id` (se mapeado) | Users blade |
| Severidade do log | `severityLevel` | Logs → severityLevel |

> **`traceId` como `operation_Id`:** o Application Insights usa `operation_Id` como chave de correlação entre todos os sinais — um trace, suas métricas relacionadas e seus logs compartilham o mesmo `operation_Id`. Isso é o `traceId` do OTel. A navegação "Logs → Trace → Métricas" no portal Azure funciona por esse campo.

---

### Alertas e Queries KQL

Com qualquer das três abordagens, alertas de SLO são configurados diretamente no portal Azure sobre as métricas recebidas:

```kql
// Taxa de erro do PedidoService.criar nos últimos 5 minutos
customMetrics
| where name == "metodo_falha_total"
| where customDimensions["classe"] == "PedidoService"
| where customDimensions["metodo"] == "criar"
| summarize sum(value) by bin(timestamp, 5m)

// p99 de latência via histograma
customMetrics
| where name startswith "metodo_execucao_seconds_bucket"
| where customDimensions["classe"] == "PedidoService"
| summarize percentile(value, 99) by bin(timestamp, 5m)

// Pedidos em estado PENDENTE (Gauge via Monitor Externo)
customMetrics
| where name == "pedido_estado_pendente"
| summarize avg(value) by bin(timestamp, 1m)

// Navegação log → trace: filtrar logs de um trace específico
traces
| where operation_Id == "<traceId>"
| order by timestamp asc
```

---

### Referências

- [Quarkus OpenTelemetry Exporter for Azure — Quarkiverse Docs](https://docs.quarkiverse.io/quarkus-opentelemetry-exporter/dev/quarkus-opentelemetry-exporter-azure.html)
- [Enable OpenTelemetry in Application Insights — Microsoft Learn](https://learn.microsoft.com/en-us/azure/azure-monitor/app/opentelemetry-enable)
- [Configure OpenTelemetry in Application Insights — Microsoft Learn](https://learn.microsoft.com/en-us/azure/azure-monitor/app/opentelemetry-configuration)
- [Application Insights Java Agent — Configuration Options](https://learn.microsoft.com/en-us/azure/azure-monitor/app/java-standalone-config)
- [Application Insights Java Agent 3.7.8 — Maven Central](https://mvnrepository.com/artifact/com.microsoft.azure/applicationinsights-agent/3.7.8)
- [Observe Quarkus Apps with Azure Application Insights — Microsoft Tech Community](https://techcommunity.microsoft.com/blog/appsonazureblog/observe-quarkus-apps-with-azure-application-insights-using-opentelemetry/4391774)
- [Monitor your Quarkus native application on Azure — Microsoft Java Dev Blog](https://devblogs.microsoft.com/java/monitor-your-quarkus-native-application-on-azure/)
- [OpenTelemetry Collector contrib — Azure Monitor Exporter](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/exporter/azuremonitorexporter)
