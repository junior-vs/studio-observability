# Audit Logging

> Source pattern: [Audit Logging — microservices.io](https://microservices.io/patterns/observability/audit-logging.html)

---

## Overview

Audit logging is the practice of recording user actions on business entities in a persistent, queryable store. Unlike application logs — which capture technical events for debugging — audit records capture **what a user did**, for compliance, customer support, and security investigation.

> **Pattern definition (microservices.io):** Record user activity in a database.

---

## Why Audit Logging Is Different from Application Logging

| Dimension | Application Log | Audit Record |
| --- | --- | --- |
| **Purpose** | Debugging, incident response | Compliance, accountability, dispute resolution |
| **Consumer** | Engineers, SRE | Legal, security, customer support, regulators |
| **Retention** | Days to weeks | Months to years (regulatory requirements) |
| **Mutability** | Append-only log stream | Must be immutable and tamper-evident |
| **Granularity** | Technical events (errors, latency) | Business actions (who changed what, to what value) |

Both are necessary. They are complementary, not interchangeable.

---

## What an Audit Record Contains

Every audit record must answer:

| Field | Description |
| --- | --- |
| `actor_id` | The user who performed the action (`user_id` or system identity) |
| `actor_ip` | Source IP address of the request |
| `session_id` | Session identifier (links to authentication event) |
| `action` | The type of action: `CREATE`, `UPDATE`, `DELETE`, `READ` (for sensitive data), `LOGIN`, `LOGOUT` |
| `entity_type` | The type of the affected entity (e.g., `UserProfile`, `Order`, `PaymentMethod`) |
| `entity_id` | The identifier of the affected entity |
| `state_before` | A snapshot of the entity's relevant state before the action |
| `state_after` | A snapshot of the entity's relevant state after the action |
| `@timestamp` | UTC timestamp with millisecond precision |
| `trace_id` | Correlation with the distributed trace for this request |
| `outcome` | `SUCCESS` or `FAILURE` (with reason if failed) |

---

## The `@Auditable` Annotation

OBSERVA4J provides a CDI interceptor binding annotation that triggers automatic audit record creation without coupling audit logic to business code:

```java
@Auditable(action = "UPDATE", entity = "UserProfile")
public void updateEmail(Long userId, String newEmail) {
    // pure business logic only — no audit code here
    userRepository.updateEmail(userId, newEmail);
}
```

The interceptor:

1. Captures the entity state **before** the method executes
2. Allows the method to proceed
3. Captures the entity state **after** the method completes
4. Writes an immutable `AuditRecord` to the `AuditWriter`
5. Does **not** interfere with the method's result or exceptions

Per microservices.io, the challenge with audit logging is that "the auditing code is intertwined with the business logic, which makes the business logic more complicated." The `@Auditable` interceptor directly addresses this.

---

## AuditWriter — Persistence-Agnostic Design

The `AuditWriter` is an injectable interface with a single responsibility:
emit the `AuditRecord` as a structured log event via `StructuredLogger`.

**The library does not persist audit records.** There are no built-in
implementations for RDBMS, MongoDB, or Kafka. Persistence is the
consumer's responsibility, implemented in a separate process that
subscribes to the log stream.

```text
@Auditable interceptor
        │
        ▼
  AuditWriter (interface)
        │
        ▼
  StructuredLogger
        │
        ▼
  JSON log stream  ──▶  consumer pipeline (external, separate process)
                        e.g. Logstash → RDBMS, Kafka consumer, S3, etc.
```

Every audit event is emitted with `event_type: AUDIT_*` and carries the
full `AuditRecord` fields. This log entry is the library's guaranteed
output contract.

Consumers who need persistence implement their own subscriber outside the
application process. The library imposes no opinion on the storage
technology or topology.

---

## Limitations

- **No persistence.** The library emits audit events to the log stream only.
  Durable storage requires a consumer-side pipeline.

- **No tamper-evidence.** The library does not guarantee that emitted audit
  records cannot be altered or deleted after the fact. This guarantee must
  be enforced by the storage infrastructure chosen by the consuming team.

- **No retention policy.** How long audit records are kept is entirely
  outside the library's boundary. For LGPD compliance, the consuming team
  must define and enforce retention and deletion policies on their storage.

- **Separate process requirement.** Any audit persistence pipeline must run
  as a separate process, not inside the same Quarkus instance. This prevents
  audit infrastructure failures from affecting application availability.

---

## Immutability and Tamper-Evidence

Tamper-evidence and immutability are **not enforced by the library**.
These are infrastructure responsibilities delegated to the consuming team.

The `AuditRecord` is an immutable Java record at the application layer —
it cannot be modified after creation within the JVM. However, once emitted
to the log stream, the library has no control over how that stream is
stored or retained.

Recommended strategies at the infrastructure layer (outside library scope):

| Strategy | Mechanism |
| --- | --- |
| **Append-only database** | `INSERT`-only privileges for the consumer process |
| **Kafka topic** | Retention policy and ACLs managed by the platform team |
| **Write-once object storage** | S3 / GCS with Object Lock for regulatory retention |

No cryptographic chaining is implemented. If cryptographic tamper-evidence
is required (e.g., regulated sectors under BACEN or ANS oversight), it must
be implemented at the infrastructure layer by the consuming team.

---

## Use Cases

### Compliance

Regulations such as LGPD (Lei Geral de Proteção de Dados) require that organisations be able to demonstrate what actions were taken on personal data, by whom, and when. An audit log provides this evidence on demand.

### Customer Support

When a customer disputes a transaction or account change, the audit trail provides a factual record: `"Your email address was changed from a@x.com to b@x.com by user USR-445 from IP 200.x.x.x on 2026-03-09T14:32:01Z."`

### Security Investigation

If a security incident is suspected, the audit log answers: which accounts were accessed, what data was read, and from which IP addresses — even if application logs have already been rotated.

### Third-Party Dispute Resolution

When an external API (payment gateway, logistics provider) claims your service sent incorrect data, the audit record of the outgoing request — including the payload, timestamp, and response — provides irrefutable technical evidence.

---

## What Must Be Audited

The following actions should always generate an audit record:

- Authentication events: `LOGIN`, `LOGIN_FAILED`, `LOGOUT`, `PASSWORD_CHANGED`
- Authorisation decisions: `ACCESS_DENIED` (for sensitive resources)
- Data mutations on sensitive entities: `CREATE`, `UPDATE`, `DELETE` on User, Order, Payment, Account
- Administrative actions: role changes, configuration updates, bulk operations
- Data exports: any action that causes personal data to leave the system

---

## See Also

- [5 Ws Framework](FIVE_WS.md) — the _Who_ and _What_ dimensions are central to every audit record
- [Distributed Tracing](DISTRIBUTED_TRACING.md) — `trace_id` links audit records to the full request trace
- [Open Questions](OPEN_QUESTIONS.md) — PII handling (#5), audit vs. log separation (#6)
- [microservices.io: Audit Logging Pattern](https://microservices.io/patterns/observability/audit-logging.html)
- [microservices.io: Event Sourcing Pattern](https://microservices.io/patterns/data/event-sourcing.html)