# Open Questions and Ambiguities

> This document collects all design decisions that are **not yet resolved** and must be clarified before detailed implementation can begin. Each item includes the context, the specific questions that need answering, and the trade-offs to consider.
>
> Items are tagged with the concept document where the ambiguity was first identified.

---

## Status Legend

| Symbol | Meaning |
| --- | --- |
| ✅ | Resolved — decision recorded below the question |
| 🔴 | Blocks implementation — must be resolved first |
| 🟡 | Important but can be resolved during Phase 1 development |
| 🟢 | Can be resolved later; has reasonable defaults |

---

## 1. Identity Array Semantics  ✅ Resolved — 2026-03-09

**Source:** [5 Ws Framework — Who](FIVE_WS.md#1-who--identity)
**Priority:** 🔴 Blocks implementation

**Context:** Taylor Scott (SolidusConf 2020) recommends using an array for user IDs to track the transition from anonymous visitor to authenticated user, preserving the continuity of the session history. The library needs to implement this in Java/Quarkus.

**Questions to resolve:**

1. After login, should both `user_id` and `visitor_token` be present in **every subsequent log line**, or only in the explicit login/transition event?
2. What is the canonical field representation — a flat structure with two separate fields (`user_id` + `visitor_token`) or an array-valued `principal_chain` field?
3. When a session expires and the user re-authenticates, how is the identity chain updated?
4. If a `visitor_token` is never converted to a `user_id` (unauthenticated session), should `user_id` be omitted or set to `null`?

**Trade-offs:**

- Flat separate fields are simpler to query in Elasticsearch and Kibana but do not capture the chain concept
- An array field captures the chain but complicates single-value queries and Prometheus labels
- Always including both fields adds noise for most log lines that have no relevance to the visitor transition

**Resolved:** 2026-03-09

**Decision:** All clients are authenticated. `visitor_token` and anonymous
session tracking are out of scope. The `ObservabilityContext` carries only
`user_id` as the identity field.

**Rationale:** The system has no unauthenticated access path, making the
visitor-to-authenticated transition concept inapplicable.

**Impact:**

- [`FIVE_WS.md`](FIVE_WS.md) — remove visitor identity subsection
- [`FIELD_NAMES.md`](FIELD_NAMES.md) — remove `visitor_token` row
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — remove `visitor_token` from `ObservabilityContext`
- [`VISION.md`](VISION.md) — remove from abstractions table and open questions list

---

## 2. Sampling Strategy  ✅ Resolved — 2026-03-09

**Source:** [Distributed Tracing](DISTRIBUTED_TRACING.md#sampling)
**Priority:** 🔴 Blocks implementation

**Context:** Recording every span in a high-traffic system requires substantial infrastructure. A sampling strategy must be defined before the tracing module can be shipped to production.

**Questions to resolve:**

1. Should the default sampling strategy be **head-based** (simple, low-overhead, decided at trace start) or **tail-based** (retains error traces, requires buffering infrastructure)?
2. What is the default sampling rate for:
   - Development / local environments?
   - Staging environments?
   - Production environments?
3. Should the library ship a preconfigured tail-based sampler (via OpenTelemetry Collector), or only support head-based sampling in v1?
4. Should error traces always be sampled regardless of the rate (i.e., `always_on` for error traces, sampled for success traces)?

**Trade-offs:**

- Head-based at 100% is simplest to implement and debug but impractical in production at scale
- Tail-based sampling produces better signal-to-noise but adds an OpenTelemetry Collector as a required infrastructure dependency
- Adaptive sampling (error-biased) is the most valuable but most complex to implement

---

✅ **Resolved:** [2026-03-09]    

**Decision:** Tail-based sampling delegated entirely to the OpenTelemetry
Collector. The application SDK is always configured as `always_on` — it sends
100% of spans via gRPC to a local Collector sidecar. The Collector holds a
buffer (`decision_wait`, recommended starting point: 10s, tunable per
environment) and applies the following policy:

| Environment | Sampler (App) | Collector policy |
| --- | --- | --- |
| Local / Dev | `always_on` | 100% — no discard |
| Staging | `always_on` | 50% success / 100% errors |
| Production | `always_on` | 1–5% success / 100% errors |

**Error rule (non-negotiable):** Any trace containing a span with
`StatusCode.ERROR` bypasses the probabilistic rate and is always forwarded
to storage. Applications must set `span.setStatus(StatusCode.ERROR, reason)`
in all exception handlers to activate this rule.

**Log correlation guarantee:** `trace_id` and `span_id` are injected into MDC
on every request. Even when a success trace is discarded by the Collector,
application logs retain the reference, enabling future correlation.

**Rationale:** Keeps the application free of sampling logic. Centralises the
decision in infrastructure. Guarantees zero loss of error traces without
requiring application changes per environment.

**Open caveat:** `decision_wait` must be tuned for long-running operations
(batch jobs, file processing) where spans may outlast the default buffer
window. A fixed 10s value is insufficient for those cases.

**Impact:**

- [`DISTRIBUTED_TRACING.md`](DISTRIBUTED_TRACING.md) — update
  Sampling section; add `always_on` config example
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — add Collector as explicit
  component in the production data flow diagram

---

## 3. Audit Record Immutability  ✅ Resolved — 2026-03-09

**Source:** [Audit Logging](AUDIT_LOGGING.md#immutability-and-tamper-evidence)
**Priority:** 🔴 Blocks implementation

**Context:** For regulatory compliance (LGPD, SOC 2), audit records may need to be immutable at the storage layer — not just at the application layer. The library must decide how much enforcement responsibility it takes on.

**Questions to resolve:**

1. Should the library enforce append-only semantics at the **storage driver level** (e.g., configure the RDBMS user with INSERT-only privileges, configure Kafka topic with immutable retention), or is this the consumer's responsibility?
2. Are **soft-deletes** (marking a record as deleted without removing it) permitted for audit records?
3. Is **hard deletion** of audit records explicitly prohibited by the library, or left as a platform decision?
4. For the `KafkaAuditWriter`: should the library configure the Kafka topic with `cleanup.policy=delete` (allows compaction/expiry) or `cleanup.policy=compact` (preserves latest per key) or reject any configuration that allows data loss?
5. Should the library implement **cryptographic chaining** (each record hashes the previous record) to make tampering detectable?

**Trade-offs:**

- Library-enforced immutability provides stronger compliance guarantees but constrains flexibility
- Consumer-enforced immutability is more flexible but shifts compliance burden to the consumer team
- Cryptographic chaining adds computational overhead and complexity but provides tamper-evidence without trusting the database

---

✅ **Resolved:** [2026-03-09]

**Decision:**

1. **Persistence is the consumer's responsibility.** The library does not
   write audit records to any database, Kafka topic, or external store.
   The `AuditWriter` interface ships no built-in persistence implementations.

2. **Every audit event is logged.** The `@Auditable` interceptor always emits
   a structured log event (`event_type: AUDIT_*`) via `StructuredLogger`,
   carrying the full `AuditRecord` fields as JSON. This log entry is the
   library's guaranteed output. Consumers who need persistence subscribe to
   this log stream through their own pipeline (e.g., Logstash → RDBMS,
   Kafka consumer, etc.) in a separate process.

3. **The library is Kafka-agnostic.** No Kafka dependency is introduced.
   If a consumer wants to publish audit events to Kafka, they consume the
   log stream or implement `AuditWriter` themselves.

4. **No cryptographic chaining.** Tamper-evidence is not enforced at the
   library level. Immutability and retention guarantees are the
   responsibility of the consumer's storage infrastructure.

**Rationale:** Keeps the library free of infrastructure dependencies.
Persistence strategy varies per consumer (regulated vs. non-regulated
contexts). The structured log is already a durable, observable output
that consumers can process asynchronously.

**Known limitation:** The library provides no tamper-evidence guarantee.
For regulated sectors (e.g., financial institutions under BACEN or ANS
oversight) that require cryptographic audit integrity, this must be
implemented at the infrastructure layer by the consuming team.

**Impact:**

- [`AUDIT_LOGGING.md`](AUDIT_LOGGING.md) — remove built-in
  persistence implementations; update AuditWriter section; add Limitations
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — remove RDBMS/Kafka from
  AuditWriter module; update data flow diagram

---

## 4. Field Name Standard Alignment ✅ Resolved — 2026-03-09

**Source:** [Field Name Registry](FIELD_NAMES.md#field-name-standard-alignment)
**Priority:** 🟡 Important

**Context:** Three major standards use different field naming conventions for the same concepts. The library must choose one as primary or provide an adapter layer.

| Standard | trace identifier | span identifier | user identifier |
| --- | --- | --- | --- |
| This registry (flat) | `trace_id` | `span_id` | `user_id` |
| ECS (Elasticsearch) | `trace.id` | `span.id` | `user.id` |
| OpenTelemetry OTLP | `trace_id` | `span_id` | N/A (resource attribute) |
| Datadog | `dd.trace_id` | `dd.span_id` | N/A |

**Questions to resolve:**

1. Does the library adopt flat notation (`trace_id`) or ECS dot-notation (`trace.id`) as the canonical form?
2. Is a **backend adapter layer** in scope for v1 — i.e., can the library remap field names at output time based on the configured target backend?
3. How are naming collisions with fields that Quarkus/OpenTelemetry already inject handled? (e.g., OpenTelemetry already adds `trace_id` to MDC — does OBSERVA4J wrap this or use it directly?)
4. Do nested JSON objects (e.g., `"exception": { "class": "...", "message": "..." }`) use dot-notation keys or nested object keys?

**Trade-offs:**

- Adopting ECS provides out-of-the-box compatibility with Elasticsearch but diverges from OpenTelemetry flat notation
- Flat notation aligns with OpenTelemetry OTLP and is simpler to query in most tools
- An adapter layer adds complexity but enables deployment in heterogeneous environments

**Decision:**

**1. Canonical standard: flat snake_case.**
`trace_id`, `span_id`, `user_id`, `request_id`. ECS dot-notation and
camelCase are prohibited in native output.

**2. Pluggable `FieldNameAdapter`** selected via `observa4j.fields.standard`:
```properties
observa4j.fields.standard=default   # no remapping
observa4j.fields.standard=ecs       # trace_id → trace.id
observa4j.fields.standard=datadog   # trace_id → dd.trace_id
observa4j.fields.standard=graylog   # flattens nested objects
```

Built-in adapters: `default`, `ecs`, `datadog`, `graylog`. Custom adapter
via CDI. Adapter remaps names only — not values (known v1 limitation for
Datadog 64-bit decimal vs OTel 128-bit hex).

**3. 100% compatible with Quarkus/OpenTelemetry.** `trace_id` and `span_id`
are owned by Quarkus via `quarkus-opentelemetry`. OBSERVA4J reads from MDC
but never writes these fields.

**4. Nested object keys for composite fields.** `exception.class`,
`exception.message`, `state_before`, `state_after` use nested JSON objects,
not flat dot-notation keys.

**Rationale:** Flat notation aligns with OTel OTLP. Adapter pattern provides
flexibility without embedding platform logic in core. Nested objects improve
semantic clarity.

**Impact applied:**
- `FIELD_NAMES.md` — standard alignment section updated; nested object fields section added; `graylog` adapter added; prohibited synonyms updated
- `STRUCTURED_LOGGING.md` — MDC ownership note for `trace_id`/`span_id` added
- `ARCHITECTURE.md` — `FieldNameAdapter` added to `observa4j-core` module table

---

**Resolved:** [data] · Owner: [nome]

**Decision:**

**1. Canonical standard: flat notation.**
All field names follow the flat snake_case pattern: `trace_id`, `span_id`,
`user_id`, `request_id`. ECS dot-notation (`trace.id`) and camelCase
(`traceId`) are prohibited in native output.

**2. Pluggable `FieldNameAdapter` via configuration.**
A `FieldNameAdapter` class is provided to remap canonical field names to
platform-specific conventions at output time. The active adapter is selected
via `application.properties`:

```properties
# Default (no remapping)
observa4j.fields.standard=default

# ECS (Elasticsearch Common Schema)
observa4j.fields.standard=ecs

# Datadog
observa4j.fields.standard=datadog
```

Built-in adapters ship for `default`, `ecs`, and `datadog`. Consumers may
provide a custom implementation via CDI.

**Known limitation of the adapter:** field name remapping only — values
are not transformed. Datadog's `dd.trace_id` expects a 64-bit decimal
string while OpenTelemetry uses a 128-bit hex string. This value
transformation is out of scope for v1 and must be handled at the
infrastructure layer if required.

**3. 100% compatible with Quarkus / OpenTelemetry.**
`trace_id` and `span_id` are read directly from the MDC entries injected
by Quarkus via `quarkus-opentelemetry`. The library does **not** register
these fields in MDC independently. Writing to `trace_id` or `span_id` from
within OBSERVA4J is prohibited — doing so risks collision with the
framework-managed values.

**4. Nested object keys for structured sub-objects.**
Composite fields use nested JSON objects, not flat dot-notation keys:

```json
{
  "exception": {
    "class": "java.lang.NullPointerException",
    "message": "Cannot invoke getId() on null",
    "stack_trace": ["..."]
  },
  "state_before": { ... },
  "state_after":  { ... }
}
```

**Platform note:** Elasticsearch index templates must map these as `object`
type, not `flattened`. Logback appenders (e.g., `logstash-logback-encoder`)
must be configured to serialise nested objects correctly. This is a
platform-side configuration concern, not a library concern.

**Rationale:** Flat notation aligns with OpenTelemetry OTLP and avoids
Quarkus MDC conflicts. The adapter pattern provides flexibility without
embedding platform-specific logic in the core library. Nested objects
improve semantic clarity for composite fields.

**Impact:**

- [`STRUCTURED_LOGGING.md`](STRUCTURED_LOGGING.md) — add MDC
  ownership note for `trace_id` / `span_id`
- [`FIELD_NAMES.md`](FIELD_NAMES.md) — update standard alignment section;
  add nested object fields section; update prohibited synonyms
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — add `FieldNameAdapter` to
  `observa4j-core` module table
  
---

## 5. PII Handling

**Source:** [Structured Logging — Security](STRUCTURED_LOGGING.md#security-and-governance)
**Priority:** 🟡 Important

**Context:** Log events must not contain PII (personally identifiable information) in raw form, per LGPD and general data minimisation principles. The library needs a clear policy on how this is enforced.

**Questions to resolve:**

1. Should PII masking be **opt-in** (developer explicitly annotates fields with `@Sensitive`) or **opt-out** (library masks known-sensitive field names by default)?
2. What is the initial list of field names treated as sensitive by default? (candidates: `email`, `cpf`, `phone`, `card_number`, `password`, `token`, `address`)
3. Should the masking strategy be configurable per-field (mask, hash, truncate, omit entirely)?
4. Should the library prevent `user_id` (a pseudonymous identifier) from being treated as PII, given that LGPD treats pseudonymous data differently from anonymous data?
5. Is the `SensitiveDataSanitizer` (referenced in the `loggin3.md` source) the intended implementation mechanism?

**Trade-offs:**

- Opt-out (default masking) provides stronger protection but may mask fields the developer intentionally wants to log
- Opt-in is safer from a developer-experience standpoint but relies on developers remembering to annotate fields
- Field-name-based detection is imprecise — a field named `email` might contain a non-PII string; a field named `data` might contain a raw email address

---

## 6. Audit vs. Log Separation

**Source:** [Audit Logging](AUDIT_LOGGING.md#auditwriter--persistence-agnostic-design)
**Priority:** 🟡 Important

**Context:** Audit records and application log events serve different purposes, have different retention requirements, and have different consumers. The question is whether they should flow through the same channel or separate ones.

**Questions to resolve:**

1. Should audit records be written to the **same JSON log stream** as technical events (differentiated by `event_type = AUDIT_*`) and picked up by the same log aggregation pipeline?
2. Or should audit records be written to a **completely separate persistence channel** (dedicated database table, Kafka topic, or write-once object storage)?
3. If both channels are supported, which is the recommended default for new services?
4. How does the answer interact with the immutability requirement in [Open Question #3](#3-audit-record-immutability)? (Log streams are not inherently immutable.)

**Trade-offs:**

- Same channel: simpler architecture; no additional infrastructure; but log streams are typically not tamper-evident and have short retention
- Separate channel: stronger compliance guarantees; correct retention policies; but adds infrastructure and operational complexity

---

## 7. Background Job Framework Scope

**Source:** [5 Ws Framework — Where: Background Jobs](FIVE_WS.md#44-location-in-background-jobs)
**Priority:** 🟢 Can be deferred

**Context:** Taylor Scott describes `queue_name`, `job_id`, and `worker_class` as essential _Where_ fields for background jobs. In Quarkus, multiple job/messaging frameworks are available.

**Questions to resolve:**

1. Which background job frameworks are in scope for v1 context injection?
   - Quarkus Scheduler (`@Scheduled`)
   - Quarkus Reactive Messaging (Kafka consumers via `@Incoming`)
   - Quarkus Messaging (Kafka via `@Channel`)
   - Plain `ExecutorService` / virtual thread tasks
2. Should context injection for background jobs use the same `ObservabilityContext` mechanism as HTTP requests, or a separate `JobContext` abstraction?
3. For Kafka consumers, is the Kafka message offset and partition part of the _Where_ context?

---

## 8. Exception De-duplication Strategy

**Source:** [Exception Tracking](EXCEPTION_TRACKING.md#fingerprinting-and-de-duplication)
**Priority:** 🟡 Important

**Context:** The fingerprint algorithm determines how exceptions are grouped. A fingerprint that is too broad groups unrelated errors; one that is too narrow creates too many distinct issues.

**Questions to resolve:**

1. What constitutes the fingerprint? Options:
   - Exception class + top stack frame
   - Exception class + top N frames from **your own code** (filtering out framework frames)
   - Exception class + message (for exceptions where the message is stable)
   - Exception class + message + top N frames
2. Should the fingerprint include **user identity** (per-user exception grouping) or be **global** (all users who hit the same bug grouped together)?
3. How many stack frames (`N`) should be included in the fingerprint — enough to distinguish different call sites, but not so many that minor refactoring creates new groups?
4. Should the fingerprint algorithm be **configurable** per-deployment, or fixed in the library?

**Trade-offs:**

- Per-user grouping allows identifying whether an error affects one user or many, but creates more groups
- Global grouping is simpler and better for identifying systemic bugs, but loses per-user diagnostic value
- Including the message in the fingerprint provides more precision but can create split groups when messages contain dynamic values (e.g., `"Order #9912 not found"` vs. `"Order #9913 not found"`)

---

## How to Resolve These Questions

1. Create a GitHub Issue for each item using this document as the description
2. Assign an owner (the team member responsible for driving the decision)
3. Set a target resolution date linked to the relevant roadmap phase
4. Document the resolution directly in the relevant concept document and close the issue
5. Update this document's status to reflect the resolution

---

## See Also

- [Vision Document](VISION.md) — overall scope and roadmap
- [5 Ws Framework](FIVE_WS.md)
- [Structured Logging](STRUCTURED_LOGGING.md)
- [Distributed Tracing](DISTRIBUTED_TRACING.md)
- [Audit Logging](AUDIT_LOGGING.md)
- [Exception Tracking](EXCEPTION_TRACKING.md)
- [Field Name Registry](FIELD_NAMES.md)
