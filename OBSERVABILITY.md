# Stack de Observabilidade - Guia de Uso

Este guia descreve como utilizar a stack completa de observabilidade configurada para o projeto `lib-full-logging`.

## Serviços Disponíveis

| Serviço | URL | Credenciais Padrão | Descrição |
|---------|-----|-------------------|-----------|
| **Grafana** | http://localhost:3000 | admin / admin | Visualização de métricas e logs |
| **Prometheus** | http://localhost:9090 | - | Coleta e armazenamento de métricas |
| **Kibana** | http://localhost:5601 | - | Visualização de logs do Elasticsearch |
| **Elasticsearch** | http://localhost:9200 | - | Armazenamento de logs e traces |
| **Graylog** | http://localhost:9000 | admin / admin | Gerenciamento centralizado de logs |
| **OpenTelemetry Collector** | http://localhost:4317 (gRPC)<br>http://localhost:4318 (HTTP) | - | Coleta de telemetria (traces, métricas, logs) |

## Início Rápido

### 1. Iniciar a Stack de Observabilidade

```bash
# No diretório raiz do projeto
docker-compose up -d
```

Aguarde alguns minutos para que todos os serviços inicializem. Você pode verificar o status com:

```bash
docker-compose ps
```

### 2. Iniciar a Aplicação Quarkus

```bash
cd logging-quarkus
./mvnw quarkus:dev
```

Ou no Windows:

```powershell
cd logging-quarkus
.\mvnw.cmd quarkus:dev
```

### 3. Acessar os Dashboards

#### Grafana
1. Acesse http://localhost:3000
2. Faça login com **admin / admin**
3. Na primeira vez, você será solicitado a trocar a senha
4. Os datasources já estão pré-configurados:
   - **Prometheus**: Para métricas da aplicação
   - **Elasticsearch**: Para logs estruturados
   - **Elasticsearch-Traces**: Para traces do OpenTelemetry
   - **Elasticsearch-Metrics**: Para métricas do OpenTelemetry

#### Kibana (Elasticsearch)
1. Acesse http://localhost:5601
2. Navegue até **Management > Stack Management > Index Management**
3. Crie um **Data View** para os índices:
   - `otel-logs*` - Logs da aplicação
   - `otel-traces*` - Traces distribuídos
   - `otel-metrics*` - Métricas
4. Use o **Discover** para explorar logs e traces

#### Graylog
1. Acesse http://localhost:9000
2. Faça login com **admin / admin**
3. Configure inputs para receber logs:
   - **System > Inputs**
   - Selecione **GELF UDP** ou **GELF TCP**
   - Clique em **Launch new input**
   - Configure a porta (12201 por padrão)

#### Prometheus
1. Acesse http://localhost:9090
2. Use o **Graph** ou **Expression Browser** para consultar métricas
3. Exemplos de queries:
   - `up` - Status dos endpoints
   - `http_server_requests_seconds_count` - Contagem de requisições
   - `jvm_memory_used_bytes` - Uso de memória JVM

## Configuração da Aplicação

### Endpoints Expostos pela Aplicação

| Endpoint | Descrição |
|----------|-----------|
| http://localhost:8080/q/metrics | Métricas do Prometheus/Micrometer |
| http://localhost:8080/q/health | Health Checks |
| http://localhost:8080/q/info | Informações da aplicação |
| http://localhost:8080/q/dev | Quarkus Dev UI (apenas em dev mode) |

### Fluxo de Telemetria

```
┌─────────────────┐
│ Aplicação       │
│ Quarkus         │
└────────┬────────┘
         │
         ├─> Logs JSON ──────────────┐
         │                           │
         ├─> Métricas ──────────────┼────> Prometheus ──> Grafana
         │                           │
         └─> Traces/Logs ──────────> OpenTelemetry ──> Elasticsearch ──> Kibana
                                     Collector              │
                                                           └──> Grafana
```

## Criando Dashboards no Grafana

### Dashboard de Métricas da Aplicação

1. Vá para **Dashboards > New Dashboard**
2. Adicione um painel com as seguintes queries:

**Taxa de Requisições HTTP:**
```promql
rate(http_server_requests_seconds_count{application="lib-full-logging"}[5m])
```

**Latência P95:**
```promql
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))
```

**Uso de Memória JVM:**
```promql
jvm_memory_used_bytes{application="lib-full-logging", area="heap"}
```

**Taxa de Erros:**
```promql
rate(http_server_requests_seconds_count{application="lib-full-logging", status=~"5.."}[5m])
```

### Dashboard de Logs

1. Adicione um painel do tipo **Logs**
2. Selecione o datasource **Elasticsearch**
3. Configure a query para buscar logs:
   - Índice: `otel-logs*`
   - Time field: `@timestamp`
   - Filtros: `service.name:lib-full-logging`

## Configurando Alertas

### Prometheus Alerts (Opcional)

Crie um arquivo `observability/prometheus/alerts/app-alerts.yml`:

```yaml
groups:
  - name: app-alerts
    interval: 30s
    rules:
      - alert: HighErrorRate
        expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.05
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Alta taxa de erros na aplicação"
          description: "A taxa de erros está acima de 5% nos últimos 5 minutos"

      - alert: HighLatency
        expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m])) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Alta latência detectada"
          description: "O P95 de latência está acima de 1 segundo"
```

Adicione ao `prometheus.yml`:

```yaml
rule_files:
  - "alerts/*.yml"
```

### Grafana Alerts

1. Em qualquer dashboard, clique em um painel
2. **Edit > Alert**
3. Configure:
   - **Condition**: Define quando o alerta deve disparar
   - **Notification**: Configure canais (Slack, email, etc.)

## Troubleshooting

### Serviços não inicializam

```bash
# Ver logs de um serviço específico
docker-compose logs -f prometheus
docker-compose logs -f grafana
docker-compose logs -f elasticsearch

# Reiniciar um serviço específico
docker-compose restart prometheus
```

### Aplicação não envia telemetria

1. Verifique se o OpenTelemetry Collector está rodando:
   ```bash
   curl http://localhost:8888/metrics
   ```

2. Verifique os logs da aplicação:
   ```bash
   # No terminal onde o Quarkus está rodando
   # Procure por logs relacionados a OpenTelemetry
   ```

3. Teste o endpoint de métricas:
   ```bash
   curl http://localhost:8080/q/metrics
   ```

### Elasticsearch com pouca memória

Se o Elasticsearch não inicializar devido a falta de memória, ajuste no `docker-compose.yml`:

```yaml
environment:
  - "ES_JAVA_OPTS=-Xms256m -Xmx256m"  # Reduzir para 256MB
```

### Graylog não recebe logs

1. Verifique se o input está configurado e rodando
2. Teste a conexão:
   ```bash
   echo '{"version": "1.1","host":"example.org","short_message":"A short message"}' | gzip | nc -u -w 1 localhost 12201
   ```

## Parando os Serviços

```bash
# Parar todos os serviços
docker-compose down

# Parar e remover volumes (CUIDADO: apaga todos os dados)
docker-compose down -v
```

## Perfis de Configuração

### Desenvolvimento (`%dev`)
- Logs JSON formatados (pretty-print)
- 100% de amostragem de traces
- Logs em nível DEBUG

### Produção (`%prod`)
- Logs JSON compactos
- 10% de amostragem de traces (configurável)
- Logs em nível INFO

Para executar em modo produção:

```bash
./mvnw clean package -Dquarkus.package.type=uber-jar
java -jar target/logging-quarkus-1.0.0-SNAPSHOT-runner.jar
```

## Próximos Passos

1. **Personalizar Dashboards**: Crie dashboards específicos para suas necessidades
2. **Configurar Alertas**: Defina alertas para métricas críticas
3. **Otimizar Amostragem**: Ajuste a taxa de amostragem de traces em produção
4. **Retention Policy**: Configure políticas de retenção no Elasticsearch
5. **Backup**: Configure backup dos dados do Elasticsearch e Graylog

## Recursos Adicionais

- [Documentação do Quarkus - OpenTelemetry](https://quarkus.io/guides/opentelemetry)
- [Documentação do Quarkus - Micrometer](https://quarkus.io/guides/micrometer)
- [Prometheus Query Language](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- [Grafana Dashboards](https://grafana.com/grafana/dashboards/)
- [Elasticsearch Query DSL](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl.html)
- [Graylog Documentation](https://go2docs.graylog.org/current/home.htm)
