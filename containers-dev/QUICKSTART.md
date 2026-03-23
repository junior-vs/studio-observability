# Início Rápido - Stack de Observabilidade

## ⚡ TL;DR (Too Long; Didn't Read)

```bash
# 1. Iniciar stack de observabilidade
docker-compose up -d

# 2. Iniciar aplicação (em outro terminal)
cd logging-quarkus
./mvnw quarkus:dev

# 3. Acessar dashboards
# Grafana:    http://localhost:3000 (admin/admin)
# Prometheus: http://localhost:9090
# Kibana:     http://localhost:5601
# Graylog:    http://localhost:9000 (admin/admin)
```

## 🎯 O que você ganha?

✅ **Métricas em tempo real** - Visualize performance e uso de recursos  
✅ **Logs estruturados** - Pesquise e analise logs facilmente  
✅ **Traces distribuídos** - Rastreie requisições através de todo o sistema  
✅ **Alertas** - Configure notificações para problemas  
✅ **Dashboards prontos** - Visualizações pré-configuradas  

## 📦 O que está incluído?

### Grafana (Visualização)
- Dashboards pré-configurados para a aplicação Quarkus
- Integração com Prometheus (métricas) e Elasticsearch (logs)
- Interface unificada para toda telemetria

### Prometheus (Métricas)
- Coleta automática de métricas da aplicação
- Histórico de dados de performance
- Queries poderosas para análise

### Elastic Stack (Logs)
- **Elasticsearch**: Armazenamento e indexação de logs
- **Kibana**: Interface de busca e visualização
- Logs estruturados em JSON para fácil análise

### Graylog (Gerenciamento de Logs)
- Centralização de logs de múltiplas fontes
- Alertas baseados em padrões de logs
- Arquivamento e compliance

### OpenTelemetry (Traces)
- Rastreamento distribuído automático
- Correlação entre logs, métricas e traces
- Padrão aberto e vendor-neutral

## 🚀 Fluxo de Trabalho Típico

### 1. Durante o Desenvolvimento

```bash
# Terminal 1: Inicie a stack
docker-compose up -d

# Terminal 2: Execute sua aplicação
cd logging-quarkus
./mvnw quarkus:dev

# Faça suas alterações e teste
# A aplicação reinicia automaticamente (hot reload)
```

### 2. Visualizando Telemetria

**Para ver métricas:**
1. Abra http://localhost:3000 (Grafana)
2. Vá para **Dashboards > Quarkus Application Overview**
3. Veja requests/sec, latência, uso de memória, etc.

**Para ver logs:**
1. Abra http://localhost:5601 (Kibana)
2. Vá para **Discover**
3. Selecione o índice `otel-logs*`
4. Filtre por `service.name: lib-full-logging`

**Para ver traces:**
1. No Kibana, vá para **Observability > APM**
2. Ou busque no índice `otel-traces*`

### 3. Debugging com Observabilidade

**Problema: Alta latência em uma requisição**

1. **Grafana** → Identifique qual endpoint está lento
   - Dashboard: "HTTP Request Latency (P95)"
   
2. **Kibana** → Veja os logs desse endpoint
   - Filtro: `uri: "/api/pedidos" AND level: ERROR`
   
3. **Traces** → Veja onde está o gargalo
   - Encontre o trace_id no log
   - Visualize o span completo da requisição

**Problema: Uso de memória crescendo**

1. **Grafana** → Monitore JVM Memory Usage
2. **Prometheus** → Query: `jvm_memory_used_bytes{area="heap"}`
3. **Logs** → Busque por OutOfMemoryError

## 🛠️ Comandos Úteis

### Docker Compose

```bash
# Ver logs de todos os serviços
docker-compose logs -f

# Ver logs de um serviço específico
docker-compose logs -f grafana

# Reiniciar um serviço
docker-compose restart prometheus

# Ver status dos serviços
docker-compose ps

# Parar tudo e limpar volumes
docker-compose down -v
```

### Aplicação Quarkus

```bash
# Dev mode com debug ativo
./mvnw quarkus:dev -Ddebug=5005

# Build rápido sem testes
./mvnw clean package -DskipTests

# Ver métricas direto da aplicação
curl http://localhost:8080/q/metrics

# Ver health checks
curl http://localhost:8080/q/health

# Ver informações da aplicação
curl http://localhost:8080/q/info
```

### Queries Úteis do Prometheus

```promql
# Taxa de requisições nos últimos 5 minutos
rate(http_server_requests_seconds_count[5m])

# Latência P95 por endpoint
histogram_quantile(0.95, 
  sum(rate(http_server_requests_seconds_bucket[5m])) 
  by (le, uri)
)

# Taxa de erro (status 5xx)
rate(http_server_requests_seconds_count{status=~"5.."}[5m])

# Uso de CPU da aplicação
process_cpu_usage

# Threads ativas
jvm_threads_live
```

### Queries Úteis do Kibana

```
# Buscar erros nos últimos 15 minutos
level: ERROR AND @timestamp:[now-15m TO now]

# Buscar por trace ID específico
trace_id: "abc123..."

# Buscar por usuário
user_id: "user@example.com"

# Buscar requisições lentas (> 1 segundo)
duration: >1000
```

## 🎓 Próximos Passos

1. **Personalizar Dashboards**
   - Crie visualizações específicas para seu caso de uso
   - Adicione painéis para métricas de negócio

2. **Configurar Alertas**
   - Configure alertas no Grafana para alta latência
   - Configure alertas no Graylog para erros críticos

3. **Otimizar Retenção**
   - Configure políticas de retenção no Elasticsearch
   - Ajuste o período de armazenamento de métricas

4. **Integrar com CI/CD**
   - Use métricas para validar deploys
   - Automatize rollback baseado em alertas

## 📖 Documentação Completa

- [OBSERVABILITY.md](OBSERVABILITY.md) - Guia detalhado completo
- [README.md](README.md) - Visão geral do projeto
- [concepts/full-logging/](concepts/full-logging/) - Conceitos e arquitetura

## 🆘 Ajuda

**Serviços não iniciam?**
```bash
# Verificar logs
docker-compose logs -f [service-name]

# Verificar recursos do Docker
docker system df
docker system prune  # Limpar se necessário
```

**Aplicação não envia telemetria?**
```bash
# Verificar conectividade com OpenTelemetry
curl http://localhost:4318/v1/traces

# Verificar configuração
cat logging-quarkus/src/main/resources/application.properties | grep otel
```

**Grafana não mostra dados?**
- Verifique se os datasources estão conectados: **Configuration > Data Sources**
- Teste a conexão com "Save & Test"
- Verifique se a aplicação está gerando métricas: http://localhost:8080/q/metrics

---

**Dica:** Mantenha os serviços rodando em um terminal separado e monitore os logs com `docker-compose logs -f` para ver a atividade em tempo real.
