# Full Logging Library para Quarkus

[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.observa4j/quarkus-observa4j?logo=apache-maven&style=flat-square)](https://central.sonatype.com/artifact/io.quarkiverse.observa4j/quarkus-observa4j-parent)

Uma biblioteca Quarkus completa para logging estruturado, rastreamento distribuído e observabilidade baseada nos princípios dos "5 Ws" (Who, What, When, Where, Why).

## 🎯 Características Principais

- **Logging Estruturado com JSON**: Logs em formato JSON para fácil parsing e análise
- **OpenTelemetry Integrado**: Traces e spans distribuídos nativamente
- **Contexto Automático**: Propagação automática de trace ID, span ID e contexto de usuário
- **DSL Fluente**: API intuitiva para construção de logs (`registrando().em(...).porque(...).info()`)
- **Sanitização de Dados**: Mascaramento automático de dados sensíveis
- **Métricas com Micrometer**: Integração com Prometheus para métricas de aplicação
- **Observabilidade Completa**: Stack de observabilidade pronta para uso

## 🚀 Início Rápido

### 1. Adicionar Dependência

```xml
<dependency>
    <groupId>br.com.vsjr.labs</groupId>
    <artifactId>logging-quarkus</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Usar a API

```java
@Inject
LogSistematico log;

public void processarPedido(String pedidoId) {
    log.registrando("pedido")
       .em("processamento")
       .porque("novo pedido recebido")
       .comDetalhe("pedido_id", pedidoId)
       .info();
}
```

📘 **Guia Completo de Início Rápido**: Veja [QUICKSTART.md](QUICKSTART.md) para comandos úteis e fluxos de trabalho.

## 📊 Stack de Observabilidade

Este projeto inclui uma stack completa de observabilidade com Docker Compose:

### Serviços Disponíveis

| Serviço | URL | Credenciais | Descrição |
|---------|-----|-------------|-----------|
| **Grafana** | http://localhost:3000 | admin/admin | Visualização de métricas e logs |
| **Prometheus** | http://localhost:9090 | - | Coleta de métricas |
| **Kibana** | http://localhost:5601 | - | Visualização de logs |
| **Elasticsearch** | http://localhost:9200 | - | Armazenamento de logs |
| **Graylog** | http://localhost:9000 | admin/admin | Gerenciamento de logs |
| **OpenTelemetry** | 4317 (gRPC), 4318 (HTTP) | - | Coleta de telemetria |

### Iniciar a Stack

**Windows (PowerShell):**
```powershell
.\start-observability.ps1
```

**Linux/macOS:**
```bash
chmod +x start-observability.sh
./start-observability.sh
```

**Ou manualmente:**
```bash
docker-compose up -d
```

### Parar a Stack

**Windows (PowerShell):**
```powershell
.\stop-observability.ps1
```

**Linux/macOS:**
```bash
./stop-observability.sh
```

📖 **Documentação completa**: Veja [OBSERVABILITY.md](OBSERVABILITY.md) para guia detalhado.

## 🏗️ Arquitetura

```
┌─────────────────────────────────────┐
│      Aplicação Quarkus              │
│  ┌──────────────────────────────┐   │
│  │   LogSistematico (DSL)       │   │
│  └──────────┬───────────────────┘   │
│             │                        │
│  ┌──────────▼───────────────────┐   │
│  │  GerenciadorContextoLog      │   │
│  │  (MDC + OpenTelemetry)       │   │
│  └──────────┬───────────────────┘   │
│             │                        │
│  ┌──────────▼───────────────────┐   │
│  │  SanitizadorDados            │   │
│  │  (Mascaramento)              │   │
│  └──────────┬───────────────────┘   │
└─────────────┼───────────────────────┘
              │
     ┌────────┴─────────┐
     │                  │
     ▼                  ▼
 JSON Logs      OpenTelemetry
     │           Collector
     │                  │
     ▼                  ▼
Elasticsearch     Prometheus
     │                  │
     ▼                  ▼
  Kibana           Grafana
  Graylog
```

## 📚 Documentação

### Conceitos Principais

- [Arquitetura](concepts/full-logging/ARCHITECTURE.md) - Visão geral da arquitetura
- [5 Ws](concepts/full-logging/FIVE_WS.md) - Princípios dos 5 Ws aplicados ao logging
- [Nomes de Campos](concepts/full-logging/FIELD_NAMES.md) - Convenções de nomenclatura
- [Padrões de Codificação](concepts/full-logging/CODING_STANDARDS.md) - Boas práticas

### Guias de Integração

- [Guia de Integração](concepts/full-logging/INTEGRATION_GUIDE.md) - Como integrar em seus projetos
- [Logging Estruturado](concepts/full-logging/STRUCTURED_LOGGING.md) - Detalhes de logging estruturado
- [Rastreamento Distribuído](concepts/full-logging/DISTRIBUTED_TRACING.md) - OpenTelemetry e traces
- [Telemetria](concepts/full-logging/TELEMETRY.md) - Métricas e observabilidade

### Especificações

- [Requirements](requirements.md) - Requisitos do projeto
- [Design](design.md) - Decisões de design
- [Tasks](tasks.md) - Tarefas e progresso

## 🛠️ Desenvolvimento

### Pré-requisitos

- Java 21+
- Maven 3.8+
- Docker e Docker Compose (para stack de observabilidade)

### Executar em Modo Dev

```bash
cd logging-quarkus
./mvnw quarkus:dev
```

A aplicação estará disponível em:
- http://localhost:8080
- Dev UI: http://localhost:8080/q/dev
- Métricas: http://localhost:8080/q/metrics
- Health: http://localhost:8080/q/health

### Executar Testes

```bash
./mvnw test
```

### Build

```bash
./mvnw clean package
```

### Build Native

```bash
./mvnw package -Dnative
```

## 🤝 Contribuindo

Contribuições são bem-vindas! Por favor:

1. Faça um fork do projeto
2. Crie uma branch para sua feature (`git checkout -b feature/AmazingFeature`)
3. Commit suas mudanças (`git commit -m 'Add some AmazingFeature'`)
4. Push para a branch (`git push origin feature/AmazingFeature`)
5. Abra um Pull Request

## 📝 Licença

Este projeto está sob a licença [LICENSE](LICENSE).

## 🙏 Agradecimentos

- [Quarkus](https://quarkus.io) - Framework supersônico, subatômico
- [OpenTelemetry](https://opentelemetry.io) - Observabilidade padronizada
- [Elastic Stack](https://www.elastic.co) - Busca e análise de logs
- [Grafana](https://grafana.com) - Visualização de métricas
- [Prometheus](https://prometheus.io) - Sistema de monitoramento

---

**Nota**: Este projeto faz parte do Quarkiverse. Veja mais em [Quarkiverse Wiki](https://github.com/quarkiverse/quarkiverse/wiki).

