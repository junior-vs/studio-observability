# Integration Guide

> **Audience:** Platform engineers and application teams integrating OBSERVA4J into an existing observability infrastructure.
>
> **Scope:** This guide is outside the core OBSERVA4J extension. It documents how to connect the signals emitted by the extension to specific backends. Backend configuration, infrastructure setup, and operational decisions are the responsibility of the consuming team.
>
> For the extension's output contract — what fields it emits, in what format, via which protocols — see [`docs/reference/BACKENDS.md`](../reference/BACKENDS.md).

---

## Prerequisites

OBSERVA4J emits three classes of signal. Before configuring any backend, confirm which signals your infrastructure needs to receive:

| Signal | Transport | Emitted by |
|---|---|---|
| **Logs** (structured JSON) | stdout or GELF | `quarkus-logging-json` + `quarkus-logging-gelf` |
| **Traces** (spans) | OTLP/gRPC | `quarkus-opentelemetry` |
| **Metrics** | Prometheus scrape + OTLP/gRPC | `quarkus-micrometer` + `quarkus-micrometer-opentelemetry` |

All three are active simultaneously. A backend that receives only one signal (e.g., Graylog for logs) does not affect the other two pipelines.

---

## 1. Graylog

**Signals:** Logs only
**Protocol:** GELF (UDP or TCP) — direct from application, no intermediary

### How it works

`quarkus-logging-gelf` sends log events directly to a Graylog GELF Input. There is no Fluentd or Logstash in this path.

```
[Application + OBSERVA4J]
        │
        └──▶ GELF/UDP  ──▶  Graylog Input  ──▶  Graylog Stream
```

### Required application configuration

```properties
# application.properties
quarkus.log.handler.gelf.enabled=true
quarkus.log.handler.gelf.host=<graylog-host>
quarkus.log.handler.gelf.port=12201
quarkus.log.handler.gelf.version=1.1
quarkus.log.handler.gelf.include-full-mdc=true

# Mandatory: flattens nested fields for GELF compatibility
observa4j.fields.standard=graylog
```

> ⚠️ `observa4j.fields.standard=graylog` is **mandatory**. GELF does not support nested JSON objects. Without it, fields like `exception.class` are silently dropped by the GELF transport layer — the application will not fail, but those fields will not appear in Graylog search results.

### Graylog side: Input configuration

In the Graylog UI (System → Inputs):

- Input type: **GELF UDP**
- Port: `12201`
- Bind address: `0.0.0.0`

### Field flattening reference

The `graylog` adapter transforms nested fields before transmission:

| Canonical field (extension output) | GELF field (after adapter) |
|---|---|
| `exception.class` | `exception_class` |
| `exception.message` | `exception_message` |
| `exception.stack_trace` | `exception_stack_trace` |
| `exception.cause` | `exception_cause_class` + `exception_cause_message` |
| `state_before.<key>` | `state_before_<key>` |
| `state_after.<key>` | `state_after_<key>` |

### Expected fields per event (Graylog stream rules)

| GELF field | Type |
|---|---|
| `event_type` | string |
| `trace_id` | string |
| `span_id` | string |
| `request_id` | string |
| `user_id` | string |
| `service_name` | string |
| `hostname` | string |
| `severity` | string |
| `exception_class` | string (when present) |
| `exception_message` | string (when present) |

### Local test stack

```yaml
# docker-compose.yml (Graylog + dependencies)
services:
  mongodb:
    image: mongo:7.0
    volumes:
      - graylog_mongo_data:/data/db

  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
    volumes:
      - graylog_es_data:/usr/share/elasticsearch/data

  graylog:
    image: graylog/graylog:6.1
    environment:
      GRAYLOG_PASSWORD_SECRET: "changeme-at-least-16-chars"
      GRAYLOG_ROOT_PASSWORD_SHA2: "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918"
      GRAYLOG_HTTP_EXTERNAL_URI: "http://localhost:9000/"
      GRAYLOG_ELASTICSEARCH_HOSTS: "http://elasticsearch:9200"
      GRAYLOG_MONGODB_URI: "mongodb://mongodb:27017/graylog"
    ports:
      - "9000:9000"       # Graylog UI
      - "12201:12201/udp" # GELF UDP input
    depends_on:
      - mongodb
      - elasticsearch

volumes:
  graylog_mongo_data:
  graylog_es_data:
```

---

## 2. Elasticsearch + Logstash + Kibana (ELK)

**Signals:** Logs only (Elastic APM handles traces — see section 3)
**Protocol:** GELF → Logstash → Elasticsearch

### How it works

```
[Application + OBSERVA4J]
        │
        └──▶ GELF/UDP  ──▶  Logstash  ──▶  Elasticsearch  ──▶  Kibana
```

Logstash acts as the GELF consumer and transforms events before indexing.

### Required application configuration

```properties
# application.properties
quarkus.log.handler.gelf.enabled=true
quarkus.log.handler.gelf.host=<logstash-host>
quarkus.log.handler.gelf.port=12201
quarkus.log.handler.gelf.version=1.1
quarkus.log.handler.gelf.include-full-mdc=true

# Use graylog adapter for consistent flattening regardless of consumer
observa4j.fields.standard=graylog
```

### Logstash pipeline

```ruby
# logstash.conf
input {
  gelf {
    port    => 12201
    use_udp => true
  }
}

filter {
  mutate {
    rename => { "short_message" => "message" }
  }
  if [exception_class] {
    mutate { add_tag => ["has_exception"] }
  }
}

output {
  elasticsearch {
    hosts => ["elasticsearch:9200"]
    index => "observa4j-logs-%{+YYYY.MM.dd}"
  }
}
```

### Elasticsearch index mapping

Fields used for exact-match queries must be mapped as `keyword`. Apply this mapping template before the first index is created:

```json
{
  "index_patterns": ["observa4j-logs-*"],
  "mappings": {
    "properties": {
      "trace_id":   { "type": "keyword" },
      "span_id":    { "type": "keyword" },
      "request_id": { "type": "keyword" },
      "user_id":    { "type": "keyword" },
      "event_type": { "type": "keyword" },
      "severity":   { "type": "keyword" },
      "message":    { "type": "text" },
      "@timestamp": { "type": "date" }
    }
  }
}
```

> Without `keyword` mapping, `trace_id` is tokenised by the default analyser. Searching for an exact trace ID in Kibana will return no results.

### Kibana saved searches

| Name | Query |
|---|---|
| Errors last 1h | `severity:ERROR AND @timestamp:[now-1h TO now]` |
| Trace drill-down | `trace_id:"<value>"` |
| Audit events | `event_type:AUDIT_*` |
| User activity | `user_id:"<value>" AND @timestamp:[now-24h TO now]` |
| Circuit breaker events | `event_type:CIRCUIT_BREAKER_*` |
| Retry exhausted | `event_type:RETRY_EXHAUSTED` |

### Local test stack

```yaml
# docker-compose.yml (ELK)
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
    ports:
      - "9200:9200"
    volumes:
      - elk_es_data:/usr/share/elasticsearch/data

  logstash:
    image: docker.elastic.co/logstash/logstash:8.17.0
    volumes:
      - ./logstash.conf:/usr/share/logstash/pipeline/logstash.conf
    ports:
      - "12201:12201/udp"
    depends_on:
      - elasticsearch

  kibana:
    image: docker.elastic.co/kibana/kibana:8.17.0
    ports:
      - "5601:5601"
    depends_on:
      - elasticsearch

volumes:
  elk_es_data:
```

---

## 3. Elastic APM

**Signals:** Traces only
**Protocol:** OTLP/gRPC (via OTel Collector)

Elastic APM is the trace visualisation layer of the ELK stack. It is independent of the Logstash log pipeline.

### How it works

```
[Application + OBSERVA4J]
        │
        └──▶ OTLP/gRPC  ──▶  OTel Collector  ──▶  Elastic APM Server  ──▶  Kibana APM UI
```

### Required application configuration

```properties
# application.properties
quarkus.otel.exporter.otlp.traces.endpoint=http://<otel-collector-host>:4317
quarkus.otel.traces.sampler=parentbased_always_on
```

### OTel Collector configuration

```yaml
# otel-collector-config.yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317

exporters:
  otlp/elastic:
    endpoint: "apm-server:8200"
    headers:
      Authorization: "Bearer ${APM_SECRET_TOKEN}"

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlp/elastic]
```

### Log–trace correlation in Kibana

OBSERVA4J emits `trace_id` as a flat string in the log index. Elastic APM stores the same value as `trace.id` (ECS format) in its own index. The Kibana APM UI correlates the two automatically when the log index field is mapped as `keyword`.

If correlation is not working, verify:

1. `trace_id` in the Elasticsearch log index is mapped as `keyword`
2. The value format is 32 lowercase hex characters with no dashes (OTel 128-bit format)
3. The log index is registered as a Kibana data view with the `@timestamp` field as the time field

### Local test stack

```yaml
# docker-compose.yml (Elastic APM addition — append to ELK stack above)
services:
  elastic-apm-server:
    image: docker.elastic.co/apm/apm-server:8.17.0
    ports:
      - "8200:8200"
    environment:
      - output.elasticsearch.hosts=["elasticsearch:9200"]
      - apm-server.secret_token=${APM_SECRET_TOKEN}
    depends_on:
      - elasticsearch

  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.117.0
    command: ["--config=/etc/otel-collector-config.yaml"]
    volumes:
      - ./otel-collector-config.yaml:/etc/otel-collector-config.yaml
    ports:
      - "4317:4317"
    depends_on:
      - elastic-apm-server
```

---

## 4. Grafana (Prometheus + Tempo)

**Signals:** Metrics (Prometheus scrape + OTLP) and Traces (Grafana Tempo)
**Protocol:** HTTP scrape (`/q/metrics`) and OTLP/gRPC

### How it works

```
[Application + OBSERVA4J]
        │
        ├──▶ /q/metrics (HTTP scrape)  ──▶  Prometheus  ──▶  Grafana
        │
        └──▶ OTLP/gRPC  ──▶  OTel Collector  ──▶  Grafana Tempo  ──▶  Grafana
```

Both metric channels (scrape and OTLP) are active simultaneously due to `quarkus-micrometer-opentelemetry`. Avoid configuring both as Grafana data sources for the same metric unless panels explicitly select one source — doing so causes double-counting in dashboards.

### Prometheus scrape configuration

```yaml
# prometheus.yml
scrape_configs:
  - job_name: "observa4j-app"
    static_configs:
      - targets: ["<app-host>:8080"]
    metrics_path: /q/metrics
    scrape_interval: 15s
```

### OTel Collector → Tempo configuration

```yaml
# otel-collector-config.yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317

exporters:
  otlp/tempo:
    endpoint: "tempo:4317"

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlp/tempo]
```

### Grafana data source configuration

```ini
# grafana/provisioning/datasources/datasources.yaml
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090

  - name: Tempo
    type: tempo
    url: http://tempo:3200
```

### Recommended dashboards

| Dashboard | Data source | Key panels |
|---|---|---|
| Service Overview | Prometheus | Request rate, error rate, P50/P95/P99 latency |
| JVM Health | Prometheus | Heap usage, GC pause duration (JFR) |
| Circuit Breaker | Prometheus | `observa4j_circuit_breaker_state_transitions_total` |
| Retry Analysis | Prometheus | `observa4j_retry_attempts_total` histogram |
| Trace Explorer | Tempo | Search by `service.name`, `trace_id`, span status |

### Local test stack

```yaml
# docker-compose.yml (Grafana stack)
services:
  prometheus:
    image: prom/prometheus:v3.1.0
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana-tempo:
    image: grafana/tempo:2.6.0
    command: ["-config.file=/etc/tempo.yaml"]
    volumes:
      - ./tempo.yaml:/etc/tempo.yaml
    ports:
      - "3200:3200"

  grafana:
    image: grafana/grafana:11.4.0
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning
    ports:
      - "3000:3000"
    depends_on:
      - prometheus
      - grafana-tempo

  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.117.0
    command: ["--config=/etc/otel-collector-config.yaml"]
    volumes:
      - ./otel-collector-config.yaml:/etc/otel-collector-config.yaml
    ports:
      - "4317:4317"
    depends_on:
      - grafana-tempo
```

---

## 5. Azure Application Insights

**Signals:** Logs, Traces, and Metrics — unified in a single pipeline
**Protocol:** OTLP/gRPC (direct or via OTel Collector)

### How it works

**Option A — Direct (no Collector)**

```
[Application + OBSERVA4J]
        │
        └──▶ OTLP/gRPC  ──▶  Azure Monitor OTLP endpoint
```

**Option B — Via OTel Collector (required for native image builds)**

```
[Application + OBSERVA4J]
        │
        └──▶ OTLP/gRPC  ──▶  OTel Collector  ──▶  Azure Monitor exporter
```

### Required additional dependency (consumer pom.xml)

```xml
<dependency>
    <groupId>io.quarkiverse.opentelemetry.exporter</groupId>
    <artifactId>quarkus-opentelemetry-exporter-azure</artifactId>
    <version>3.27.2.0</version>
</dependency>
```

> Version convention: `{quarkus-version}.{exporter-patch}`. For Quarkus 3.27.2, use `3.27.2.0`.

### Required application configuration

```properties
# application.properties
quarkus.azure-exporter.connection-string=InstrumentationKey=<key>;IngestionEndpoint=...;LiveEndpoint=...

# When App Insights is the sole OTLP backend, disable the generic OTLP exporters
# to avoid duplicate signal transmission
quarkus.otel.exporter.otlp.traces.endpoint=none
quarkus.otel.exporter.otlp.metrics.endpoint=none
quarkus.otel.exporter.otlp.logs.endpoint=none
```

### Signal mapping

| OBSERVA4J signal | App Insights table | Notes |
|---|---|---|
| Structured log events | `traces` | All custom dimensions preserved with original names |
| Span (HTTP request) | `requests` | Azure Monitor schema; `trace_id` stored as `operation_Id` |
| Span (downstream call) | `dependencies` | Azure Monitor schema |
| Exception | `exceptions` | Linked to parent request via `operation_Id` |
| Micrometer metric | `customMetrics` | |

> `trace_id` is stored both as `operation_Id` (Azure convention, used for correlation) and as `customDimensions["trace_id"]` (canonical name, for direct queries). Both contain the same value.

### Kusto query examples

```kusto
// All log events for a specific trace
traces
| where customDimensions["trace_id"] == "7d2c8e4f1a3b9c2d"
| order by timestamp asc

// Circuit breaker state transitions in the last hour
traces
| where customDimensions["event_type"] startswith "CIRCUIT_BREAKER"
| where timestamp > ago(1h)
| project timestamp,
          tostring(customDimensions["event_type"]),
          tostring(customDimensions["circuit_name"])

// P95 request latency per service, last 24h
requests
| where timestamp > ago(24h)
| summarize percentile(duration, 95) by cloud_RoleName, bin(timestamp, 1h)

// Audit events by user
traces
| where customDimensions["event_type"] startswith "AUDIT_"
| project timestamp,
          tostring(customDimensions["user_id"]),
          tostring(customDimensions["event_type"]),
          tostring(customDimensions["entity_id"])
```

### Known limitations

- **GraalVM native image:** `quarkus-opentelemetry-exporter-azure` does not support native compilation as of Quarkus 3.27.2. Native builds must use Option B (OTel Collector with `azuremonitor` exporter from `opentelemetry-collector-contrib`).
- **Field naming in `requests` and `dependencies` tables:** Azure Monitor uses its own schema for these tables. Only the `traces` table preserves all custom dimensions with their original field names.
- **No GELF support:** App Insights has no GELF input. When using App Insights as the sole backend, disable `quarkus-logging-gelf` or set `quarkus.log.handler.gelf.enabled=false` to avoid silent transmission failures.

---

## Complete Local Stack (all backends)

The following `docker-compose.yml` runs the full set of backends simultaneously for integration testing:

```yaml
version: "3.9"

services:

  # ─── Shared ───────────────────────────────────────────
  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.117.0
    command: ["--config=/etc/otel-collector-config.yaml"]
    volumes:
      - ./otel-collector-config.yaml:/etc/otel-collector-config.yaml
    ports:
      - "4317:4317"   # OTLP gRPC
      - "4318:4318"   # OTLP HTTP

  # ─── Log backends ─────────────────────────────────────
  mongodb:
    image: mongo:7.0
    volumes:
      - graylog_mongo:/data/db

  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.17.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
    volumes:
      - es_data:/usr/share/elasticsearch/data
    ports:
      - "9200:9200"

  logstash:
    image: docker.elastic.co/logstash/logstash:8.17.0
    volumes:
      - ./logstash.conf:/usr/share/logstash/pipeline/logstash.conf
    ports:
      - "12201:12201/udp"
    depends_on: [elasticsearch]

  kibana:
    image: docker.elastic.co/kibana/kibana:8.17.0
    ports:
      - "5601:5601"
    depends_on: [elasticsearch]

  graylog:
    image: graylog/graylog:6.1
    environment:
      GRAYLOG_PASSWORD_SECRET: "changeme-at-least-16-chars"
      GRAYLOG_ROOT_PASSWORD_SHA2: "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918"
      GRAYLOG_HTTP_EXTERNAL_URI: "http://localhost:9000/"
      GRAYLOG_ELASTICSEARCH_HOSTS: "http://elasticsearch:9200"
      GRAYLOG_MONGODB_URI: "mongodb://mongodb:27017/graylog"
    ports:
      - "9000:9000"
      - "12201:12201/udp"
    depends_on: [mongodb, elasticsearch]

  # ─── Trace backends ───────────────────────────────────
  elastic-apm-server:
    image: docker.elastic.co/apm/apm-server:8.17.0
    ports:
      - "8200:8200"
    depends_on: [elasticsearch]

  grafana-tempo:
    image: grafana/tempo:2.6.0
    command: ["-config.file=/etc/tempo.yaml"]
    volumes:
      - ./tempo.yaml:/etc/tempo.yaml
    ports:
      - "3200:3200"

  # ─── Metrics backends ─────────────────────────────────
  prometheus:
    image: prom/prometheus:v3.1.0
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:11.4.0
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning
    ports:
      - "3000:3000"
    depends_on: [prometheus, grafana-tempo]

volumes:
  graylog_mongo:
  es_data:
```

### Port reference

| Port | Service | Protocol |
|---|---|---|
| `4317` | OTel Collector | OTLP gRPC |
| `4318` | OTel Collector | OTLP HTTP |
| `8080` | Application | HTTP |
| `9000` | Graylog UI | HTTP |
| `9090` | Prometheus | HTTP |
| `9200` | Elasticsearch | HTTP |
| `12201/udp` | Graylog / Logstash | GELF |
| `3000` | Grafana | HTTP |
| `3200` | Grafana Tempo | HTTP |
| `5601` | Kibana | HTTP |
| `8200` | Elastic APM Server | HTTP |

---

## Choosing a Configuration

| Context | Recommended setup |
|---|---|
| Local development (minimal) | stdout JSON + Jaeger all-in-one (`jaegertracing/all-in-one`) |
| Local integration testing | Full `docker-compose.yml` above |
| On-premise | Graylog (logs) + Elastic APM (traces) + Prometheus + Grafana |
| Azure cloud | Azure Application Insights (unified — all signals) |
| Multi-backend / cloud-agnostic | OTel Collector as fan-out hub → any combination |

---

## See Also

- [`docs/reference/BACKENDS.md`](../reference/BACKENDS.md) — extension output contract: what fields, formats, and protocols OBSERVA4J guarantees
- [`docs/concepts/STRUCTURED_LOGGING.md`](../concepts/STRUCTURED_LOGGING.md) — GELF output and field adapter behaviour
- [`docs/concepts/DISTRIBUTED_TRACING.md`](../concepts/DISTRIBUTED_TRACING.md) — OTLP exporters and sampling
- [`docs/concepts/TELEMETRY.md`](../concepts/TELEMETRY.md) — Micrometer aggregation models
- [`docs/reference/FIELD_NAMES.md`](../reference/FIELD_NAMES.md) — canonical field names and nested object structures
