
## Concepts

### The Three Pillars of Observability

Observability is the ability to understand the internal state of a system by examining its external outputs. Unlike monitoring, which answers "is the system working?", observability answers "why is the system not working?" Three complementary signal types form the foundation.

```text
                          Observability
                    ┌─────────┼─────────┐
                    │         │         │
                  Logs     Metrics    Traces
                    │         │         │
              What happened  How much   Where did
              in detail?     of what?   time go?
```

- *Logs* — Discrete, timestamped records of events. Logs tell you what happened. They are the most familiar signal and the easiest to produce, but the hardest to query at scale without structure.

- *Metrics* — Numeric measurements aggregated over time. Metrics tell you how much or how fast. They are cheap to store, fast to query, and ideal for dashboards and alerting. Examples: request count, error rate, p99 latency, CPU usage.

- *Traces* — Records of a request's journey through multiple services. Traces tell you where time was spent and which service caused a failure. Each trace consists of spans, and each span represents a unit of work.

### Common Mistakes & Pitfalls

- Logging everything, querying nothing — Teams instrument exhaustively but never build dashboards or alerts. Terabytes of logs accumulate in S3, costing thousands per month in storage, while engineers still SSH into production boxes to debug. Observability only has value if it is actively consumed.

- Unstructured log messages — log::info!("Something went wrong with order {}", id) cannot be queried, filtered, or aggregated. Once unstructured logging is in production, migrating to structured logging is a multi-month effort across teams. Start structured from day one.

- Alert fatigue — Setting thresholds too low or alerting on causes (CPU > 80%) instead of symptoms (latency > target) produces hundreds of alerts per week. On-call engineers start ignoring all alerts, including the critical ones. Every alert should require a specific human action; if it does not, it is not an alert, it is a log line.

- Missing context propagation — Instrumenting each service individually but failing to propagate trace context between services. Without propagation, you get isolated spans per service but cannot reconstruct the full request journey. This makes distributed tracing useless for cross-service debugging.

- Cardinality explosion — Using unbounded values (user ID, request ID, full URL path) as metric labels in Prometheus. A label with 1 million unique values creates 1 million time series per metric, consuming enormous amounts of memory and storage. Use high-cardinality values in logs and traces, not in metrics.

- No baseline, no anomaly detection — Alerting requires knowing what "normal" looks like. Teams that set alert thresholds based on guesses instead of measured baselines either miss real incidents or drown in false positives. Record at least two weeks of baseline data before setting production alerts.

### Key Takeaways

- Observability is not monitoring. Monitoring tells you something is broken. Observability tells you why it is broken by letting you ask arbitrary questions about system state.

- Start with structured logging on day one. Migrating from println! to structured logging in a running system is painful.

- Metrics are for alerting and dashboards; logs are for debugging; traces are for understanding request flow. Use all three -- they answer different questions and are not interchangeable.

- Alert on symptoms (user-facing impact) not causes (internal metrics). An alert should require a specific human action. If the correct response is "wait and see," it should not be an alert.

- Use OpenTelemetry for instrumentation. It is the vendor-neutral standard, supported by every major observability vendor, and it decouples your code from your backend choice.

- Cardinality matters. Put high-cardinality data (user IDs, request IDs) in logs and traces. Keep metric labels bounded (HTTP method, status code class, service name).

- The best observability investment is correlating signals. Being able to go from an alert to the relevant traces to the specific log lines in under 60 seconds is the difference between a 5-minute incident and a 5-hour incident.


## References

- Taylor Scott — _"Logging in Production"_, SolidusConf 2020
- Chris Richardson — [Microservices.io: Application Logging](https://microservices.io/patterns/observability/application-logging.html)
- Chris Richardson — [Microservices.io: Distributed Tracing](https://microservices.io/patterns/observability/distributed-tracing.html)
- Chris Richardson — [Microservices.io: Exception Tracking](https://microservices.io/patterns/observability/exception-tracking.html)
- Chris Richardson — [Microservices.io: Audit Logging](https://microservices.io/patterns/observability/audit-logging.html)
- Iluwatar — [java-design-patterns: microservices-log-aggregation](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-log-aggregation)
- Iluwatar — [java-design-patterns: microservices-distributed-tracing](https://github.com/iluwatar/java-design-patterns/tree/master/microservices-distributed-tracing)

- Chris Richardson — [Microservices.io: Application Metrics](https://microservices.io/patterns/observability/application-metrics.html)
- Chris Richardson — [Microservices.io: Health Check API](https://microservices.io/patterns/observability/health-check-api.html)

- [OpenTelemetry Specification](https://opentelemetry.io/docs/specs/)
- [W3C TraceContext Recommendation](https://www.w3.org/TR/trace-context/)
- [Quarkus 3.x — OpenTelemetry Guide](https://quarkus.io/guides/opentelemetry)
- [Elasticsearch Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/)


### Articles & Papers:

- [Google SRE Book - Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/) -- The foundational chapter on symptom-based alerting
- [Dapper, a Large-Scale Distributed Systems Tracing Infrastructure](https://research.google/pubs/pub36356/) -- Google's seminal paper on distributed tracing
- [Monitoring and Observability](https://copyconstruct.medium.com/monitoring-and-observability-8417d1952e1c) -- Cindy Sridharan's influential article distinguishing the two concepts


### Books:

- [Site Reliability Engineering](https://sre.google/sre-book/) -- Betsy Beyer, Chris Jones, et al. (2016) -- Google's SRE practices, especially chapters on monitoring and alerting
- [Observability Engineering](https://www.oreilly.com/library/view/observability-engineering/9781492076430/) -- Charity Majors, Liz Fong-Jones, George Miranda (2022) -- Modern observability principles, high-cardinality debugging
- [The Art of Monitoring](https://www.oreilly.com/library/view/the-art-of/9781491984587/) -- James Turnbull (2018) -- Practical guide to building monitoring infrastructure
- [Distributed Systems Observability](https://www.oreilly.com/library/view/distributed-systems-observability/9781492035404/) -- Cindy Sridharan (2018) -- Concise guide to the three pillars, free from O'Reilly


### Tools:

- [Prometheus](https://prometheus.io/) -- Pull-based metrics collection and alerting
- [Grafana](https://grafana.com/) -- Dashboarding and visualization
- [Jaeger](https://www.jaegertracing.io/) -- Open-source distributed tracing backend
- [Grafana Tempo](https://grafana.com/oss/tempo/) -- Scalable, cost-effective trace storage
    OpenTelemetry Collector -- Vendor-agnostic telemetry pipeline
