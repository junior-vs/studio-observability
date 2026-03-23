# Health Check API

> Source pattern: [Health Check API — microservices.io](https://microservices.io/patterns/observability/health-check-api.html)

---

## Overview

A service instance can be running but incapable of handling requests. For example, it may have exhausted its database connection pool. Without a health check, the load balancer or service registry will continue routing traffic to the failing instance, degrading the user experience silently.

> **Pattern definition (microservices.io):** A service has a health check API endpoint (e.g., HTTP `/health`) that returns the health of the service. The API endpoint handler performs various checks such as the status of connections to infrastructure services, the status of the host, and application-specific logic.

---

## Quarkus Health Endpoint

Quarkus exposes health endpoints at:

| Endpoint | Purpose |
| --- | --- |
| `GET /q/health` | Combined liveness and readiness |
| `GET /q/health/live` | Liveness probe |
| `GET /q/health/ready` | Readiness probe |
| `GET /q/health/started` | Startup probe (Kubernetes) |

---

## Liveness vs. Readiness

These two probes serve different purposes and must not be conflated:

| Probe | Question | Failure action |
| --- | --- | --- |
| **Liveness** | Is the JVM process alive and not deadlocked? | Container is **restarted** |
| **Readiness** | Is this instance ready to receive traffic? | Traffic is **rerouted** to healthy instances |

A service can be **live** but **not ready** — for example, during startup while waiting for database migrations to complete. In this case, the liveness probe passes (the JVM is running), but the readiness probe fails (the service is not yet capable of handling requests).

---

## Built-in Health Indicators

OBSERVA4J provides pre-built health contributors for common infrastructure. Each contributor is injected automatically when the relevant extension is present:

| Contributor | Checks | Trigger |
| --- | --- | --- |
| `DatabaseHealthContributor` | Active connections vs. pool maximum; query timeout | `quarkus-jdbc-*` or `quarkus-hibernate-orm` |
| `KafkaHealthContributor` | Broker reachability; consumer group lag | `quarkus-kafka-*` |
| `ExternalApiHealthContributor` | HTTP GET to configured health URLs | Manual configuration |
| `DiskSpaceHealthContributor` | Available disk space vs. configured threshold | Always active |
| `JvmMemoryHealthContributor` | Heap usage percentage vs. configured threshold | Always active |

---

## Response Format

Health checks return a structured JSON response compatible with Kubernetes probes and load balancers:

```json
{
  "status": "DOWN",
  "checks": [
    {
      "name": "database",
      "status": "UP",
      "data": {
        "active_connections": 8,
        "max_connections": 20
      }
    },
    {
      "name": "payment-gateway",
      "status": "DOWN",
      "data": {
        "error": "Connection timeout after 5000ms",
        "url": "https://pay.example.com/v1/health",
        "last_successful_check": "2026-03-09T14:28:01.000Z"
      }
    }
  ]
}
```

The overall `status` is `UP` only if **all** checks pass. A single `DOWN` check causes the overall status to be `DOWN`.

---

## Custom Health Contributors

Services implement the `HealthContributor` interface to add application-specific health logic:

```java
@ApplicationScoped
@Readiness
public class OrderQueueHealthContributor implements HealthCheck {

    @Inject
    OrderQueueService orderQueueService;

    @Override
    public HealthCheckResponse call() {
        long queueDepth = orderQueueService.getCurrentDepth();
        long maxDepth   = orderQueueService.getMaxDepth();

        if (queueDepth > maxDepth * 0.9) {
            return HealthCheckResponse.named("order-queue")
                .down()
                .withData("depth", queueDepth)
                .withData("max", maxDepth)
                .withData("utilisation_pct", (queueDepth * 100) / maxDepth)
                .build();
        }

        return HealthCheckResponse.named("order-queue").up().build();
    }
}
```

---
## Deployment Integration

The extension exposes the following endpoints for use by load balancers,
service registries, and container orchestrators:

| Endpoint | Probe type | Recommended use |
| --- | --- | --- |
| `GET /q/health/live` | Liveness | Restart the container on deadlock or unrecoverable JVM state |
| `GET /q/health/ready` | Readiness | Remove from rotation on dependency failure; do not restart |
| `GET /q/health/started` | Startup | Delay traffic until the application has fully initialised |

The extension does not prescribe how these endpoints are wired into the
orchestration platform. Kubernetes probe configuration, OpenShift
DeploymentConfig settings, and load balancer health check rules are
the responsibility of the consuming team.

For reference probe configuration examples, see the
[Integration Guide](../guides/INTEGRATION_GUIDE.md).
---

## Health Checks and Observability

Health check results should be emitted as structured log events and as metrics, so that health degradation is visible in dashboards and triggers alerts:

```json
{
  "@timestamp": "2026-03-09T14:32:01.123Z",
  "event_type": "HEALTH_CHECK_FAILED",
  "check_name": "payment-gateway",
  "status": "DOWN",
  "error": "Connection timeout",
  "hostname": "app-node-03",
  "trace_id": null
}
```

---

## Limitations

Per microservices.io: _"The health check might not be sufficiently comprehensive, or the service instance might fail between health checks, so requests might still be routed to a failed service instance."_

Health checks are periodic, not continuous. A service can pass its health check at 14:32:00 and fail at 14:32:05 — before the next check at 14:32:10. This is acceptable for most failure modes, but high-frequency failures require short check intervals or circuit-breaker patterns at the consumer side.

---

## See Also

- [Telemetry & Metrics](TELEMETRY.md) — health check results feed infrastructure metrics
- [Exception Tracking](EXCEPTION_TRACKING.md) — health check failures should also trigger exception reports
- [microservices.io: Health Check API Pattern](https://microservices.io/patterns/observability/health-check-api.html)
- [Quarkus SmallRye Health Guide](https://quarkus.io/guides/smallrye-health)
