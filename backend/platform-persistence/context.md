# platform-persistence — module context

> Reload-context file for `backend/platform-persistence`. Authoritative sources it implements:
> `docs/PLATFORM-CONTRACT.md` §5 (PostgreSQL schema, money rule, time rule, id conventions) and
> `docs/SHARED-LIBRARY-API.md` §2 (entities, repositories, autoconfiguration).
> If this file and those documents ever disagree, **the documents win**.

---

## 1. Purpose

`platform-persistence` owns the **`pdei` PostgreSQL schema** — the operational source of truth for
PDEI — and the JPA/Spring Data layer that maps it. It is a **library module** (never repackaged
into an executable jar). Every Spring service module adds the dependency and immediately gets:

- the Flyway migrations `V1__baseline.sql` … `V10__fts.sql` (schema `pdei`),
- JPA entities for all 28 tables,
- Spring Data repositories for all of them,
- `PersistenceAutoConfiguration`, which wires entity scan + repository scan + Flyway defaults.

Design rules this module enforces mechanically, not by convention:

| Rule | How it is enforced here |
|---|---|
| Money is `(long amountMinor, String currency)` | Every monetary column is `amount_minor BIGINT` + `currency CHAR(3)`, mapped by `MoneyEmbeddable`. There is **no** `NUMERIC`, `REAL`, `DOUBLE PRECISION` or `BigDecimal` anywhere in the schema or the entities — geo coordinates are integer micro-degrees for exactly this reason. |
| Timestamps are `Instant` / `TIMESTAMPTZ`, UTC | Every time column is `TIMESTAMPTZ`; every entity field is `java.time.Instant`. `LocalDateTime` does not appear. |
| Consumers are idempotent | `processed_events (event_id, consumer_group)` + `ProcessedEventRepository.markProcessed(...)` implemented as `INSERT … ON CONFLICT DO NOTHING`. |
| Provenance and history are never overwritten | `evidence_versions`, `policy_versions`, `audit_events` and `ai_admission_log` are append-only; the first three carry `@Immutable` **and** database triggers that reject `UPDATE`/`DELETE`. |
| Audit is tamper-evident | `audit_events.previous_hash` + `hash`, a database-assigned `sequence_no`, plus partial unique indexes that make a forked chain impossible. |
| AI never mutates financial state | The AI-related tables (`investigations`, `investigation_findings`, `ai_admission_log`) only record proposals and gate verdicts; no financial column is writable from them. |

---

## 2. Responsibilities

**In scope**

1. Schema definition and evolution (Flyway; this module is the only place migrations live).
2. Object/relational mapping of every table, including JSONB columns and the money embeddable.
3. Query surface: derived queries, JPQL, and the two native primitives that need real SQL —
   `INSERT … ON CONFLICT DO NOTHING` (idempotency) and `websearch_to_tsquery` (evidence FTS).
4. Autoconfiguration so services need zero persistence boilerplate.

**Out of scope (deliberately)**

- Business logic, scoring, gap detection, policy evaluation → `evidence-core`.
- Kafka consumption/production → the service modules.
- Object storage (MinIO), Redis caching, Temporal → `evidence-core` / service modules.
- REST/DTO shapes → `api-gateway-service` and `evidence-core.model`.

---

## 3. Schema (`pdei`) — table by table

Conventions that apply to every table below:

- **Primary keys** are human-readable prefixed `VARCHAR(64)` (`MER-`, `CUS-`, `TX-`, `PAY-`, `ORD-`,
  `SHP-`, `DLV-`, `REF-`, `COM-`, `EV-`, `POL-`, `DSP-`, `CASE-`, `INV-`, `AUD-`, `SIM-`), enforced by
  `ck_<table>_id_prefix` CHECK constraints. Tables with no contract prefix use a documented derived id
  (`order_lines` = `{orderId}-L{n}`, `evidence_versions` = `{evidenceId}-v{n}`,
  `policy_versions` = `{policyId}-v{n}`) or an opaque UUID string
  (`evidence_relationships`, `evidence_requirements`, `case_evidence`, `readiness_snapshots`,
  `readiness_gaps`, `investigation_findings`, `ai_admission_log`, `chaos_injections`).
- **Mutable tables** carry `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL` and
  `version BIGINT NOT NULL` (JPA optimistic locking). Append-only tables carry only `created_at`.
- **Enum-backed columns** are `VARCHAR` with a CHECK constraint listing the exact values of the
  matching `platform-common` enum. Adding an enum value therefore requires a migration.
- **Percent-like values are integers**: basis points (`*_bps`, 0..10000) for confidences and rates,
  0..100 for scores and priorities.

### `V1__baseline.sql`

- **merchants**: `merchant_id` VARCHAR(64) NOT NULL; `legal_name` VARCHAR(256) NOT NULL; `display_name` VARCHAR(256); `country` VARCHAR(2) NOT NULL; `default_currency` CHAR(3) NOT NULL; `mcc` VARCHAR(8); `status` VARCHAR(32) NOT NULL; `timezone` VARCHAR(64) NOT NULL; `contact_email` VARCHAR(256); `baseline_win_rate_bps` INTEGER; `onboarded_at` TIMESTAMPTZ; `risk_profile` JSONB; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **customers**: `customer_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `external_ref` VARCHAR(128); `display_name` VARCHAR(256); `email` VARCHAR(256); `phone` VARCHAR(64); `country` VARCHAR(2); `first_seen_at` TIMESTAMPTZ; `last_seen_at` TIMESTAMPTZ; `risk_flags` JSONB; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **processed_events**: `event_id` VARCHAR(64) NOT NULL; `consumer_group` VARCHAR(128) NOT NULL; `processed_at` TIMESTAMPTZ NOT NULL

### `V2__transactions.sql`

- **transactions**: `transaction_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `customer_id` VARCHAR(64); `external_ref` VARCHAR(128); `amount_minor` BIGINT NOT NULL; `currency` CHAR(3) NOT NULL; `captured_amount_minor` BIGINT NOT NULL; `captured_currency` CHAR(3) NOT NULL; `refunded_amount_minor` BIGINT NOT NULL; `refunded_currency` CHAR(3) NOT NULL; `status` VARCHAR(32) NOT NULL; `channel` VARCHAR(32); `occurred_at` TIMESTAMPTZ NOT NULL; `observed_at` TIMESTAMPTZ NOT NULL; `readiness_score` INTEGER; `readiness_band` VARCHAR(32); `readiness_computed_at` TIMESTAMPTZ; `last_event_id` VARCHAR(64); `last_event_at` TIMESTAMPTZ; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **payments**: `payment_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `psp` VARCHAR(64); `psp_reference` VARCHAR(128); `method` VARCHAR(32); `card_brand` VARCHAR(32); `card_bin` VARCHAR(8); `card_last4` VARCHAR(4); `amount_minor` BIGINT NOT NULL; `currency` CHAR(3) NOT NULL; `status` VARCHAR(32) NOT NULL; `avs_result` VARCHAR(16); `cvv_result` VARCHAR(16); `three_ds_result` VARCHAR(32); `device_fingerprint` VARCHAR(128); `ip_address` VARCHAR(64); `occurred_at` TIMESTAMPTZ NOT NULL; `authorized_at` TIMESTAMPTZ; `captured_at` TIMESTAMPTZ; `failed_at` TIMESTAMPTZ; `failure_code` VARCHAR(64); `failure_message` VARCHAR(512); `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **orders**: `order_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64); `merchant_id` VARCHAR(64) NOT NULL; `customer_id` VARCHAR(64); `external_ref` VARCHAR(128); `amount_minor` BIGINT NOT NULL; `currency` CHAR(3) NOT NULL; `tax_amount_minor` BIGINT NOT NULL; `tax_currency` CHAR(3) NOT NULL; `shipping_amount_minor` BIGINT NOT NULL; `shipping_currency` CHAR(3) NOT NULL; `status` VARCHAR(32) NOT NULL; `placed_at` TIMESTAMPTZ NOT NULL; `fulfilled_at` TIMESTAMPTZ; `cancelled_at` TIMESTAMPTZ; `shipping_address` JSONB; `billing_address` JSONB; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **order_lines**: `order_line_id` VARCHAR(64) NOT NULL; `order_id` VARCHAR(64) NOT NULL; `line_number` INTEGER NOT NULL; `sku` VARCHAR(128); `description` VARCHAR(512); `quantity` INTEGER NOT NULL; `unit_price_amount_minor` BIGINT NOT NULL; `unit_price_currency` CHAR(3) NOT NULL; `line_total_amount_minor` BIGINT NOT NULL; `line_total_currency` CHAR(3) NOT NULL; `digital_good` BOOLEAN NOT NULL; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **refunds**: `refund_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64) NOT NULL; `payment_id` VARCHAR(64); `merchant_id` VARCHAR(64) NOT NULL; `amount_minor` BIGINT NOT NULL; `currency` CHAR(3) NOT NULL; `reason` VARCHAR(256); `status` VARCHAR(32) NOT NULL; `psp_reference` VARCHAR(128); `requested_at` TIMESTAMPTZ NOT NULL; `processed_at` TIMESTAMPTZ; `failure_message` VARCHAR(512); `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **shipments**: `shipment_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64); `order_id` VARCHAR(64); `merchant_id` VARCHAR(64) NOT NULL; `carrier` VARCHAR(64); `tracking_number` VARCHAR(128); `service_level` VARCHAR(64); `status` VARCHAR(32) NOT NULL; `declared_value_amount_minor` BIGINT NOT NULL; `declared_value_currency` CHAR(3) NOT NULL; `shipped_at` TIMESTAMPTZ; `estimated_delivery_at` TIMESTAMPTZ; `destination_address` JSONB; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **deliveries**: `delivery_id` VARCHAR(64) NOT NULL; `shipment_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64); `merchant_id` VARCHAR(64) NOT NULL; `status` VARCHAR(32) NOT NULL; `delivered_at` TIMESTAMPTZ; `attempts` INTEGER NOT NULL; `recipient_name` VARCHAR(256); `signed_by` VARCHAR(256); `signature_captured` BOOLEAN NOT NULL; `proof_object_key` VARCHAR(512); `geo_lat_micro` INTEGER; `geo_lon_micro` INTEGER; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **communications**: `communication_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64); `merchant_id` VARCHAR(64) NOT NULL; `customer_id` VARCHAR(64); `channel` VARCHAR(32) NOT NULL; `direction` VARCHAR(16) NOT NULL; `subject` VARCHAR(512); `body` TEXT; `sender` VARCHAR(256); `recipient` VARCHAR(256); `occurred_at` TIMESTAMPTZ NOT NULL; `object_key` VARCHAR(512); `sha256` VARCHAR(64); `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL; `search_vector` tsvector *(added in V10)*

### `V3__evidence.sql`

- **evidence**: `evidence_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64); `customer_id` VARCHAR(64); `related_entity_type` VARCHAR(32); `related_entity_id` VARCHAR(64); `type` VARCHAR(48) NOT NULL; `status` VARCHAR(32) NOT NULL; `source` VARCHAR(32) NOT NULL; `current_version` INTEGER NOT NULL; `object_key` VARCHAR(512); `content_type` VARCHAR(128); `size_bytes` BIGINT; `filename` VARCHAR(256); `sha256` VARCHAR(64); `title` VARCHAR(256); `summary` TEXT; `extracted_text` TEXT; `amount_minor` BIGINT; `currency` CHAR(3); `source_event_id` VARCHAR(64); `captured_at` TIMESTAMPTZ; `observed_at` TIMESTAMPTZ NOT NULL; `effective_from` TIMESTAMPTZ; `expires_at` TIMESTAMPTZ; `invalidated_at` TIMESTAMPTZ; `invalidated_reason` VARCHAR(512); `superseded_by` VARCHAR(64); `integrity_verified_at` TIMESTAMPTZ; `integrity_ok` BOOLEAN; `provenance` JSONB; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL; `search_vector` tsvector *(added in V10)*
- **evidence_versions**: `evidence_version_id` VARCHAR(64) NOT NULL; `evidence_id` VARCHAR(64) NOT NULL; `version_number` INTEGER NOT NULL; `parent_version` INTEGER; `sha256` VARCHAR(64) NOT NULL; `object_key` VARCHAR(512) NOT NULL; `content_type` VARCHAR(128); `size_bytes` BIGINT; `filename` VARCHAR(256); `status` VARCHAR(32) NOT NULL; `source` VARCHAR(32) NOT NULL; `source_event_id` VARCHAR(64); `change_reason` VARCHAR(512); `created_by` VARCHAR(128); `observed_at` TIMESTAMPTZ NOT NULL; `created_at` TIMESTAMPTZ NOT NULL; `metadata` JSONB
- **evidence_relationships**: `relationship_id` VARCHAR(64) NOT NULL; `from_evidence_id` VARCHAR(64) NOT NULL; `to_evidence_id` VARCHAR(64) NOT NULL; `relationship_type` VARCHAR(32) NOT NULL; `confidence_bps` INTEGER; `detected_by` VARCHAR(32) NOT NULL; `field` VARCHAR(128); `detail` TEXT; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL

### `V4__policy.sql`

- **policies**: `policy_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `name` VARCHAR(256) NOT NULL; `description` TEXT; `scope` VARCHAR(32) NOT NULL; `reason_code` VARCHAR(48); `current_version` INTEGER NOT NULL; `active` BOOLEAN NOT NULL; `effective_from` TIMESTAMPTZ NOT NULL; `effective_to` TIMESTAMPTZ; `auto_prepare_min_confidence_bps` INTEGER NOT NULL; `max_contradictions` INTEGER NOT NULL; `prohibited_evidence_types` JSONB; `permitted_actions` JSONB; `evidence_ttl_days` INTEGER; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **policy_versions**: `policy_version_id` VARCHAR(64) NOT NULL; `policy_id` VARCHAR(64) NOT NULL; `version_number` INTEGER NOT NULL; `parent_version` INTEGER; `document` JSONB NOT NULL; `auto_prepare_min_confidence_bps` INTEGER NOT NULL; `max_contradictions` INTEGER NOT NULL; `prohibited_evidence_types` JSONB; `permitted_actions` JSONB; `sha256` VARCHAR(64) NOT NULL; `change_reason` VARCHAR(512); `created_by` VARCHAR(128); `effective_from` TIMESTAMPTZ NOT NULL; `effective_to` TIMESTAMPTZ; `created_at` TIMESTAMPTZ NOT NULL
- **evidence_requirements**: `requirement_id` VARCHAR(64) NOT NULL; `policy_id` VARCHAR(64) NOT NULL; `policy_version` INTEGER NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `reason_code` VARCHAR(48); `evidence_type` VARCHAR(48) NOT NULL; `strength` VARCHAR(16) NOT NULL; `weight` INTEGER NOT NULL; `max_age_days` INTEGER; `description` TEXT; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL

### `V5__disputes.sql`

- **disputes**: `dispute_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64) NOT NULL; `customer_id` VARCHAR(64); `psp_dispute_ref` VARCHAR(128); `reason_code` VARCHAR(48) NOT NULL; `status` VARCHAR(32) NOT NULL; `amount_minor` BIGINT NOT NULL; `currency` CHAR(3) NOT NULL; `network` VARCHAR(32); `stage` VARCHAR(32); `source` VARCHAR(32) NOT NULL; `description` TEXT; `opened_at` TIMESTAMPTZ NOT NULL; `deadline_at` TIMESTAMPTZ; `closed_at` TIMESTAMPTZ; `outcome` VARCHAR(32); `last_event_id` VARCHAR(64); `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **dispute_cases**: `case_id` VARCHAR(64) NOT NULL; `dispute_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64) NOT NULL; `status` VARCHAR(32) NOT NULL; `amount_minor` BIGINT NOT NULL; `currency` CHAR(3) NOT NULL; `workflow_id` VARCHAR(128); `run_id` VARCHAR(128); `task_queue` VARCHAR(64) NOT NULL; `assigned_to` VARCHAR(128); `readiness_score` INTEGER; `readiness_band` VARCHAR(32); `recommended_action` VARCHAR(48); `safety_decision` VARCHAR(32); `progress_percent` INTEGER NOT NULL; `opened_at` TIMESTAMPTZ NOT NULL; `deadline_at` TIMESTAMPTZ; `prepared_at` TIMESTAMPTZ; `submitted_at` TIMESTAMPTZ; `closed_at` TIMESTAMPTZ; `package_object_key` VARCHAR(512); `package_version` INTEGER; `package_manifest` JSONB; `approval_actor` VARCHAR(128); `approval_at` TIMESTAMPTZ; `approval_notes` TEXT; `failure_reason` VARCHAR(512); `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **case_evidence**: `case_evidence_id` VARCHAR(64) NOT NULL; `case_id` VARCHAR(64) NOT NULL; `evidence_id` VARCHAR(64) NOT NULL; `evidence_version` INTEGER NOT NULL; `role` VARCHAR(32) NOT NULL; `sha256_at_attach` VARCHAR(64); `display_order` INTEGER NOT NULL; `included_in_package` BOOLEAN NOT NULL; `attached_at` TIMESTAMPTZ NOT NULL; `attached_by` VARCHAR(128); `notes` TEXT; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL

### `V6__readiness.sql`

- **readiness_snapshots**: `snapshot_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `reason_code` VARCHAR(48); `score` INTEGER NOT NULL; `band` VARCHAR(32) NOT NULL; `base_score` INTEGER NOT NULL; `penalty_total` INTEGER NOT NULL; `satisfied_weight` INTEGER NOT NULL; `total_weight` INTEGER NOT NULL; `mandatory_total` INTEGER NOT NULL; `mandatory_satisfied` INTEGER NOT NULL; `recommended_total` INTEGER NOT NULL; `recommended_satisfied` INTEGER NOT NULL; `gap_count` INTEGER NOT NULL; `contradiction_count` INTEGER NOT NULL; `requirements` JSONB; `policy_id` VARCHAR(64); `policy_version` INTEGER; `trigger_event_id` VARCHAR(64); `trigger_reason` VARCHAR(64); `is_current` BOOLEAN NOT NULL; `computed_at` TIMESTAMPTZ NOT NULL; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **readiness_gaps**: `gap_id` VARCHAR(64) NOT NULL; `snapshot_id` VARCHAR(64); `transaction_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `type` VARCHAR(32) NOT NULL; `severity` VARCHAR(16) NOT NULL; `evidence_type` VARCHAR(48); `evidence_id` VARCHAR(64); `related_evidence_id` VARCHAR(64); `requirement_strength` VARCHAR(16); `detail` TEXT; `remediation` TEXT; `penalty_applied` INTEGER NOT NULL; `detected_at` TIMESTAMPTZ NOT NULL; `resolved` BOOLEAN NOT NULL; `resolved_at` TIMESTAMPTZ; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL

### `V7__investigations.sql`

- **investigations**: `investigation_id` VARCHAR(64) NOT NULL; `case_id` VARCHAR(64); `dispute_id` VARCHAR(64); `merchant_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64) NOT NULL; `classification` VARCHAR(32); `confidence_bps` INTEGER; `recommended_action` VARCHAR(48); `safety_decision` VARCHAR(32); `deterministic` BOOLEAN NOT NULL; `reasoning_summary` TEXT; `narrative` TEXT; `supporting_evidence` JSONB; `missing_evidence` JSONB; `contradictions` JSONB; `citations` JSONB; `rejection_reasons` JSONB; `context_snapshot` JSONB; `provider` VARCHAR(32); `model` VARCHAR(128); `prompt_tokens` INTEGER; `completion_tokens` INTEGER; `latency_ms` BIGINT; `attempt` INTEGER NOT NULL; `requested_at` TIMESTAMPTZ NOT NULL; `completed_at` TIMESTAMPTZ; `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **investigation_findings**: `finding_id` VARCHAR(64) NOT NULL; `investigation_id` VARCHAR(64) NOT NULL; `sequence_no` INTEGER NOT NULL; `finding_type` VARCHAR(32) NOT NULL; `evidence_id` VARCHAR(64); `related_evidence_id` VARCHAR(64); `evidence_type` VARCHAR(48); `field` VARCHAR(128); `claim` TEXT; `detail` TEXT; `validated` BOOLEAN NOT NULL; `validation_error` VARCHAR(256); `metadata` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **ai_admission_log**: `admission_id` VARCHAR(64) NOT NULL; `case_id` VARCHAR(64); `dispute_id` VARCHAR(64); `investigation_id` VARCHAR(64); `merchant_id` VARCHAR(64) NOT NULL; `transaction_id` VARCHAR(64); `admitted` BOOLEAN NOT NULL; `priority` INTEGER NOT NULL; `financial_impact_component` INTEGER NOT NULL; `deadline_urgency_component` INTEGER NOT NULL; `ambiguity_component` INTEGER NOT NULL; `deterministic_confidence_component` INTEGER NOT NULL; `amount_minor` BIGINT; `currency` CHAR(3); `short_circuit` VARCHAR(64); `rate_limited` BOOLEAN NOT NULL; `budget_key` VARCHAR(64); `budget_remaining` INTEGER; `reason` VARCHAR(512); `decided_at` TIMESTAMPTZ NOT NULL; `created_at` TIMESTAMPTZ NOT NULL; `metadata` JSONB

### `V8__audit.sql`

- **audit_events**: `audit_id` VARCHAR(64) NOT NULL; `sequence_no` BIGINT identity; `entity_type` VARCHAR(32) NOT NULL; `entity_id` VARCHAR(64) NOT NULL; `merchant_id` VARCHAR(64) NOT NULL; `action` VARCHAR(64) NOT NULL; `actor` VARCHAR(128) NOT NULL; `actor_type` VARCHAR(32) NOT NULL; `occurred_at` TIMESTAMPTZ NOT NULL; `correlation_id` VARCHAR(64); `causation_id` VARCHAR(64); `source_event_id` VARCHAR(64); `before_state` JSONB; `after_state` JSONB; `previous_hash` VARCHAR(64); `hash` VARCHAR(64) NOT NULL; `created_at` TIMESTAMPTZ NOT NULL

### `V9__simulation.sql`

- **simulation_runs**: `run_id` VARCHAR(64) NOT NULL; `seed` BIGINT NOT NULL; `merchant_count` INTEGER NOT NULL; `transaction_count` INTEGER NOT NULL; `days` INTEGER NOT NULL; `dispute_rate_bps` INTEGER NOT NULL; `failure_profile` VARCHAR(64); `scenario_key` VARCHAR(64); `status` VARCHAR(32) NOT NULL; `progress_percent` INTEGER NOT NULL; `events_emitted` BIGINT NOT NULL; `transactions_created` BIGINT NOT NULL; `evidence_created` BIGINT NOT NULL; `disputes_created` BIGINT NOT NULL; `started_at` TIMESTAMPTZ; `finished_at` TIMESTAMPTZ; `requested_by` VARCHAR(128); `error_message` VARCHAR(1024); `params` JSONB; `stats` JSONB; `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL
- **chaos_injections**: `injection_id` VARCHAR(64) NOT NULL; `run_id` VARCHAR(64); `merchant_id` VARCHAR(64); `type` VARCHAR(32) NOT NULL; `status` VARCHAR(32) NOT NULL; `target` JSONB; `delay_ms` BIGINT; `event_count` INTEGER; `actor` VARCHAR(128); `injected_at` TIMESTAMPTZ NOT NULL; `completed_at` TIMESTAMPTZ; `result` JSONB; `error_message` VARCHAR(1024); `created_at` TIMESTAMPTZ NOT NULL; `updated_at` TIMESTAMPTZ NOT NULL; `version` BIGINT NOT NULL

### `V10__fts.sql`

### 3.1 Behaviour that is NOT visible in the column list

| Object | Behaviour |
|---|---|
| `ux_evidence_tx_sha` | Partial unique index on `evidence (transaction_id, sha256)` where both are non-null. Makes re-ingesting the same artifact for the same transaction a no-op, and is why `findByShaAndTransactionId` returns `Optional`. |
| `trg_evidence_search_vector` | `BEFORE INSERT OR UPDATE OF title, summary, filename, extracted_text, type, related_entity_id` on `evidence`; fills `search_vector` with weights A=title, B=summary+type, C=filename+related entity, D=extracted text. GIN index `ix_evidence_search_vector`. |
| `trg_communications_search_vector` | Same idea for `communications` (A=subject, B=sender/recipient, D=body), GIN index `ix_communications_search_vector`. |
| `pdei.fn_reject_mutation()` | Trigger function that raises SQLSTATE 23001 on any `UPDATE`/`DELETE`. Installed on `evidence_versions` (`trg_evidence_versions_immutable`) and `audit_events` (`trg_audit_events_immutable`). `TRUNCATE` bypasses row triggers, which is how tests reset fixtures. |
| `ux_audit_events_genesis` / `ux_audit_events_link` | Partial unique indexes: at most one chain genesis per merchant (`previous_hash IS NULL`) and at most one successor per link. Concurrent appenders therefore serialise on the chain instead of forking it — a unique-violation on insert means "someone else extended the chain, re-read the head and retry". |
| `audit_events.sequence_no` | `BIGINT GENERATED BY DEFAULT AS IDENTITY`, assigned by the database, mapped read-only with Hibernate `@Generated(event = INSERT)`. Chain verification orders by it. |
| `ux_disputes_psp_ref` | Partial unique index on `(merchant_id, psp_dispute_ref)` — replayed PSP webhooks cannot create duplicate disputes. |
| `ux_dispute_cases_workflow` | Partial unique index on `workflow_id` — one Temporal workflow, one case row. |
| Money CHECKs | `ck_evidence_money` and `ck_ai_admission_money` assert `(amount_minor IS NULL) = (currency IS NULL)`: a half-populated `Money` cannot exist. |
| Filtered gap indexes | `ix_readiness_gaps_merchant_sev` / `_type` are `WHERE NOT resolved` — the at-risk feed only ever reads open gaps. |
| `ix_transactions_merchant_band`, `ix_readiness_snapshots_band`, `ix_dispute_cases_merchant_band` | The `(merchant_id, band)` indexes required by the contract for control-tower filtering. |

---

## 4. File-by-file map

```
backend/platform-persistence/
├── pom.xml                                    library module; no spring-boot-maven-plugin
├── context.md                                 this file
└── src/
    ├── main/
    │   ├── java/com/laserpay/pdei/persistence/
    │   │   ├── PdeiSchema.java                schema name + package constants (compile-time)
    │   │   ├── config/
    │   │   │   └── PersistenceAutoConfiguration.java   @EntityScan + @EnableJpaRepositories
    │   │   │                                           + FlywayConfigurationCustomizer
    │   │   ├── entity/
    │   │   │   ├── BaseEntity.java            createdAt/updatedAt + @PrePersist/@PreUpdate
    │   │   │   ├── VersionedEntity.java       adds @Version long version (mutable entities)
    │   │   │   ├── MoneyEmbeddable.java       (amount_minor, currency) <-> common.money.Money
    │   │   │   ├── ProcessedEventId.java      @Embeddable composite key
    │   │   │   ├── ProcessedEventEntity.java  processed_events
    │   │   │   ├── MerchantEntity.java        merchants
    │   │   │   ├── CustomerEntity.java        customers
    │   │   │   ├── TransactionEntity.java     transactions
    │   │   │   ├── PaymentEntity.java         payments
    │   │   │   ├── OrderEntity.java           orders
    │   │   │   ├── OrderLineEntity.java       order_lines (idFor(orderId, lineNumber))
    │   │   │   ├── RefundEntity.java          refunds
    │   │   │   ├── ShipmentEntity.java        shipments
    │   │   │   ├── DeliveryEntity.java        deliveries (geo as integer micro-degrees)
    │   │   │   ├── CommunicationEntity.java   communications
    │   │   │   ├── EvidenceEntity.java        evidence (+ buildObjectKey() for MinIO layout)
    │   │   │   ├── EvidenceVersionEntity.java evidence_versions (@Immutable, idFor(...))
    │   │   │   ├── EvidenceRelationshipEntity.java  evidence_relationships (+ edge-type constants)
    │   │   │   ├── PolicyEntity.java          policies
    │   │   │   ├── PolicyVersionEntity.java   policy_versions (@Immutable, idFor(...))
    │   │   │   ├── EvidenceRequirementEntity.java   evidence_requirements
    │   │   │   ├── DisputeEntity.java         disputes
    │   │   │   ├── DisputeCaseEntity.java     dispute_cases (+ workflowIdFor(caseId))
    │   │   │   ├── CaseEvidenceEntity.java    case_evidence (+ role constants)
    │   │   │   ├── ReadinessSnapshotEntity.java     readiness_snapshots (+ trigger-reason constants)
    │   │   │   ├── ReadinessGapEntity.java    readiness_gaps
    │   │   │   ├── InvestigationEntity.java   investigations
    │   │   │   ├── InvestigationFindingEntity.java  investigation_findings (+ finding-type constants)
    │   │   │   ├── AiAdmissionLogEntity.java  ai_admission_log (@Immutable, + short-circuit constants)
    │   │   │   ├── AuditEventEntity.java      audit_events (@Immutable, @Generated sequence_no)
    │   │   │   ├── SimulationRunEntity.java   simulation_runs (+ status constants)
    │   │   │   └── ChaosInjectionEntity.java  chaos_injections (+ status constants)
    │   │   └── repository/                    one <Entity>Repository per entity (28 interfaces)
    │   └── resources/
    │       ├── db/migration/V1__baseline.sql … V10__fts.sql
    │       └── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    └── test/
        ├── java/com/laserpay/pdei/persistence/
        │   ├── PersistenceTestApplication.java          @SpringBootApplication for the test context
        │   ├── AbstractPostgresIntegrationTest.java     Testcontainers postgres:16-alpine + truncateAll()
        │   └── repository/
        │       ├── ProcessedEventRepositoryIntegrationTest.java   idempotency, incl. 8-thread race
        │       └── EvidencePersistenceIntegrationTest.java        money/JSON round-trip, FTS, immutability
        └── resources/application.properties
```

### 4.1 Repository highlights (the non-obvious ones)

| Repository | Method | Why it exists |
|---|---|---|
| `ProcessedEventRepository` | `markProcessed(eventId, consumerGroup) -> boolean` | The canonical idempotency primitive. `default` method delegating to the `@Modifying` native `INSERT … ON CONFLICT DO NOTHING`; returns `true` only for the first sighting. Also `wasProcessed`, `findConsumerGroupsFor`, `deleteProcessedBefore` (retention pruning). |
| `EvidenceRepository` | `findByShaAndTransactionId(sha256, txId)` | Name is fixed by the shared-library contract but does not match the `sha256` property, so it is declared with an explicit `@Query` instead of being derived. |
| `EvidenceRepository` | `search(tsQuery, merchantId, Pageable)` | Native FTS with `websearch_to_tsquery('english', …)` + `ts_rank`. Both parameters are nullable (`CAST(:x AS text) IS NULL OR …`). **Pass an unsorted `Pageable`** — Spring Data cannot apply dynamic sort to native queries. |
| `EvidenceRelationshipRepository` | `findByTransactionId`, `countContradictionsForTransaction` | Native joins through `evidence`, because edges do not carry `transaction_id`. |
| `ReadinessSnapshotRepository` | `markPreviousAsHistorical(txId)` | Flips `is_current` before a new snapshot is appended. |
| `ReadinessGapRepository` | `resolveOpenGaps(txId, at)` | Bulk-closes gaps when new evidence arrives. |
| `AuditEventRepository` | `findChainHead(merchantId)`, `streamChain(merchantId)` | Chain append and `GET /audit/chain/verify` / NDJSON export without materialising the chain. |
| `PolicyRepository` | `findApplicable(merchantId, reasonCode, at)` | Specificity ordering REASON_CODE > MERCHANT > GLOBAL, time-boxed by `effective_from/to`. |
| `EvidenceRequirementRepository` | `findBaselineProfile(merchantId)` | The "no reason code supplied" path of the readiness formula. |

---

## 5. Inbound contracts (what this module consumes)

- **`platform-common`** (compile dependency), used verbatim:
  - `com.laserpay.pdei.common.money.Money`
  - `…common.domain.{EvidenceType, EvidenceStatus, EvidenceSource, DisputeReasonCode, DisputeStatus,
    CaseStatus, ReadinessBand, RequirementStrength, GapType, GapSeverity,
    InvestigationClassification, RecommendedAction, SafetyDecision, ChaosType}`
  - `…common.event.{AggregateType, EventSource, ActorType}`
- **PostgreSQL 16** with the `pdei` database/user (dev credentials `pdei`/`pdei`).
- **Spring Boot 3.3.5 / Hibernate 6.5 / Flyway 10**, versions managed by the `pdei-backend` parent.
- No Kafka, no HTTP, no MinIO, no Redis: this module has no network dependencies of its own.

## 6. Outbound contracts (what this module produces)

- **The `pdei` schema itself.** Every other module reads and writes it exclusively through the
  entities/repositories here. Schema changes are additive migrations `V11__*.sql` onward — never edit
  an applied migration; Flyway checksums are validated on startup.
- **Entity + repository API** listed in `docs/SHARED-LIBRARY-API.md` §2, consumed by `evidence-core`
  and by all nine service modules.
- **Autoconfiguration** `com.laserpay.pdei.persistence.config.PersistenceAutoConfiguration`, exported
  via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. It:
  - `@EntityScan` + `@EnableJpaRepositories` on `com.laserpay.pdei` (the whole base package, so a
    service's own entities/repositories still get picked up — declaring `@EnableJpaRepositories` here
    makes Spring Boot's own repository autoconfiguration back off);
  - contributes a `FlywayConfigurationCustomizer` that defaults `schemas`/`defaultSchema` to `pdei`
    and forces `createSchemas(true)`, only where the application has not configured them.

## 7. Configuration

Services must supply the datasource; everything else has a working default.

```properties
spring.datasource.url=${PDEI_POSTGRES_URL:jdbc:postgresql://localhost:5432/pdei}
spring.datasource.username=${PDEI_POSTGRES_USER:pdei}
spring.datasource.password=${PDEI_POSTGRES_PASSWORD:pdei}

# Flyway: schema defaults are supplied by PersistenceAutoConfiguration; override only if needed.
spring.flyway.enabled=true            # default; set false in services that must not migrate
# spring.flyway.schemas=pdei
# spring.flyway.default-schema=pdei

spring.jpa.hibernate.ddl-auto=none    # REQUIRED: Flyway owns the schema, Hibernate never touches DDL
spring.jpa.open-in-view=false         # workers have no view layer; avoid holding connections
```

Environment variables (contract §15): `PDEI_POSTGRES_URL`, `PDEI_POSTGRES_USER`,
`PDEI_POSTGRES_PASSWORD`. No others are read by this module.

**Migration ownership in a multi-service deployment:** all nine services ship the same migrations.
Flyway takes a lock on `pdei.flyway_schema_history`, so concurrent startups are safe; if you prefer a
single migrator, set `spring.flyway.enabled=false` everywhere except one service (or run
`scripts/` migration tooling) — the entities do not care who applied the migrations.

## 8. Dependencies on other modules

- Depends on: `platform-common` only.
- Depended on by: `evidence-core`, `api-gateway-service`, `ingestion-service`,
  `normalization-worker`, `state-builder-worker`, `readiness-worker`, `case-orchestrator-service`,
  `document-processor-service`, `audit-service`, `simulator-service`.
- Parent: `pdei-backend` `0.1.0-SNAPSHOT` (all dependency versions come from its
  `dependencyManagement`; this module pins nothing).

## 9. Build and run

```bash
# from backend/
mvn -pl platform-persistence -am install          # build + install (compiles platform-common first)
mvn -pl platform-persistence test                 # runs the Testcontainers integration tests
```

The integration tests need a running Docker daemon; without one they are **skipped**, not failed
(`@EnabledIf(... AbstractPostgresIntegrationTest#dockerAvailable)`). They start
`postgres:16-alpine`, apply the real migrations, and assert:

1. all 10 migrations applied and the expected tables exist;
2. `markProcessed` claims once per `(eventId, consumerGroup)`, including under an 8-thread race;
3. retention pruning re-opens the dedupe window;
4. money round-trips as `(amount_minor, currency)` with the physical columns verified through JDBC;
5. evidence FTS ranking, phrase queries, negation and the null-query fallback;
6. version history is append-only — the database rejects `UPDATE`/`DELETE` on `evidence_versions`.

Applying migrations manually against a running Postgres:

```bash
psql "postgresql://pdei:pdei@localhost:5432/pdei" -f src/main/resources/db/migration/V1__baseline.sql   # …through V10
```

## 10. Extension points

1. **New table** → add `V11__*.sql` (never edit an applied file), add the entity under `entity/`
   extending `VersionedEntity` (mutable) or a plain `@Entity` + `@Immutable` (append-only), add the
   repository. Autoconfiguration picks both up with no further wiring.
2. **New enum value** → the CHECK constraints are intentionally strict: add the value to the
   `platform-common` enum *and* ship a migration that drops/recreates the constraint.
3. **New money column** → always the pair `x_amount_minor BIGINT` + `x_currency CHAR(3)`, mapped with
   `@Embedded @AttributeOverrides` onto `MoneyEmbeddable`. Never a single column, never `NUMERIC`.
4. **New searchable text** → extend `pdei.fn_evidence_search_vector()` in a new migration and
   backfill by touching a watched column (`UPDATE pdei.evidence SET title = title;`).
5. **New idempotency scope** → reuse `processed_events` with a distinct `consumer_group` string
   (`ConsumerGroups.*` in `platform-common`); do not invent a second dedupe table.
6. **Projections/DTOs** → prefer Spring Data interface projections on the existing repositories over
   new entities; the entities are the storage model, not the API model.

## 11. Known gaps and TODOs

1. **No seed/reference data.** The requirement matrix (`evidence_requirements`) and default policies
   are not seeded by a migration; `simulator-service` or a future `V*__seed.sql` must create them.
   Until then `ReadinessEngine` will score against an empty requirement set.
2. **Hibernate schema validation is not enabled** (`ddl-auto=none` in the tests). Entity/DDL parity is
   instead asserted behaviourally by the integration tests plus the money/JSON round-trip. Turning
   `spring.jpa.hibernate.ddl-auto=validate` on locally is a useful extra check, but Hibernate's
   validator is known to be fussy about `jsonb` columns, so it is not the default.
3. **Assigned ids are the caller's job.** `save()` on an entity whose `@Id` is null fails: ids come
   from `com.laserpay.pdei.common.id.Ids`. The convenience helpers `EvidenceVersionEntity.idFor(...)`,
   `PolicyVersionEntity.idFor(...)` and `OrderLineEntity.idFor(...)` exist for the derived-id tables;
   the `@PrePersist` fallbacks in those two version entities should not be relied on.
4. **No entity associations.** Foreign keys are stored as plain `String` ids, not `@ManyToOne`
   graphs — deliberate for an event-driven system (no lazy-loading traps, no cascade surprises), but
   it means joins are explicit in queries and there is no `evidence.getTransaction()`.
5. **`audit_events` chain writes are serialised per merchant** by the partial unique indexes. A
   high-volume merchant is therefore a write hotspot; `audit-service` must retry on unique violation
   (re-read `findChainHead`, recompute `previousHash`). Sharding the chain (per merchant + entity type)
   is the escape hatch if that ever becomes a real bottleneck — measure first.
6. **FTS is English-only** (`to_tsvector('english', …)`). Multi-language merchants would need a
   per-row regconfig column; deliberately deferred (reference §24: no OpenSearch until a workload
   justifies it).
7. **No partitioning / retention policy** for `processed_events`, `audit_events` or
   `readiness_snapshots`. `deleteProcessedBefore(...)` exists but nothing schedules it; a nightly job
   in a service module should call it with a 7-day cutoff (matching the Redis `pdei:idem:` TTL).
8. **`readiness_snapshots.is_current` is maintained by the writer**, not by a trigger. A writer that
   appends a snapshot without calling `markPreviousAsHistorical(...)` will leave two "current" rows;
   the repository reads that use `computed_at DESC` are unaffected.
9. **Parent version assumption**: this module declares parent `pdei-backend:0.1.0-SNAPSHOT` to match
   `backend/pom.xml`. If the reactor version changes, this pom must change with it.
10. **No `Money` `AttributeConverter`**, by design: a single-column converter cannot express the
   mandated two-column layout. Conversion is `MoneyEmbeddable.of(Money)` / `toMoney()` plus the
   per-entity `get…AsMoney()` / `set…FromMoney()` helpers.
