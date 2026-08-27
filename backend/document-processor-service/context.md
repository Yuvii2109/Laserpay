# document-processor-service — module context

> Port **8086** · package `com.laserpay.pdei.docproc` · Spring Boot (worker + web)
> Reload this file first when returning to this module. It is the complete picture of what
> this service does, what it consumes, what it produces, and where it is deliberately unfinished.

---

## 1. Purpose

Turns evidence **artifacts** into **searchable, verifiable text**.

An evidence row in Postgres says a delivery note exists and that its sha256 is `a3f2…`. That is
enough to prove an artifact has not been tampered with, and not nearly enough to answer "does the
delivery note actually name this customer" or "which page is the signature on". This service is
the bridge: it reads the bytes out of MinIO, extracts text and metadata, verifies the hash against
what the evidence row claims, writes the text into `evidence.extracted_text` (which the V10
trigger turns into the FTS `search_vector`), and republishes an EVIDENCE event so readiness
recomputes against a now-searchable document.

Everything it writes is text, metadata and integrity flags. It never touches money, status
transitions, or any other financial state — those belong to `state-builder-worker` and
`evidence-core`.

---

## 2. Responsibilities

| # | Responsibility | Where |
|---|---|---|
| 1 | Consume EVIDENCE events, idempotently | `kafka.EvidenceEventConsumer`, `kafka.IdempotencyGuard` |
| 2 | Fetch the artifact from MinIO through the evidence-core `ObjectStore` | `service.DocumentProcessingService` |
| 3 | Enforce size and time limits before and during parsing | `config.DocProcProperties`, `service.DocumentProcessingService` |
| 4 | Recompute sha256 over exactly the bytes parsed, and compare | `service.DocumentProcessingService` |
| 5 | Select the right parser by content type and filename | `extract.ExtractorRegistry` |
| 6 | Extract text + metadata (Tika / PDFBox / `.eml` / plain text) | `extract.*` |
| 7 | Write `evidence.extracted_text` and `evidence.metadata` | `service.DocumentProcessingService` |
| 8 | Publish an EVIDENCE event so readiness recomputes | `service.DocumentProcessingService` |
| 9 | Quarantine what cannot be used, visibly | `service.QuarantineService` |
| 10 | Serve contract §8.3 (`/extract`, `/reprocess/{id}`, `/stats`) | `controller.DocProcController` |
| 11 | Dead-letter what it cannot parse at the envelope level | `kafka.DeadLetterPublisher` |

---

## 3. File-by-file map

```
document-processor-service/
├── pom.xml                       evidence-core + web + actuator + Tika + PDFBox
├── Dockerfile                    multi-stage; build context is backend/
├── context.md                    this file
└── src/
    ├── main/java/com/laserpay/pdei/docproc/
    │   ├── DocumentProcessorApplication.java   @SpringBootApplication entry point
    │   ├── config/
    │   │   ├── DocProcProperties.java          `pdei.docproc.*` — every guard rail
    │   │   ├── DocProcConfiguration.java       extractor beans + registry ORDER + timeout pool
    │   │   └── DocProcKafkaConfiguration.java  String-valued consumer factory, manual ack
    │   ├── extract/
    │   │   ├── DocumentExtractor.java          the interface; stateless, thread-safe
    │   │   ├── ExtractionRequest.java          bytes + filename + content type + evidenceId
    │   │   ├── ExtractionResult.java           text, metadata, pageCount, sha256, pages, warnings
    │   │   ├── ExtractionFailedException.java  undecodable → quarantine (NOT a PdeiException)
    │   │   ├── ExtractorRegistry.java          first-match-wins selection, order is the contract
    │   │   ├── PdfBoxDocumentExtractor.java    page count, per-page text, document info dict
    │   │   ├── EmlExtractor.java               RFC 5322/2047/2045 reader for customer emails
    │   │   ├── PlainTextExtractor.java         text/*, JSON, CSV, XML — the hot path
    │   │   ├── TikaDocumentExtractor.java      catch-all, bounded by a write limit
    │   │   └── TextNormalizer.java             unicode + whitespace clean-up before the tsvector
    │   ├── kafka/
    │   │   ├── EvidenceEventConsumer.java      @KafkaListener; dedupe, loop-guard, dispatch
    │   │   ├── IdempotencyGuard.java           Redis SETNX + Postgres processed_events
    │   │   └── DeadLetterPublisher.java        → pdei.dlq.v1 as DeadLetterEnvelope
    │   ├── service/
    │   │   ├── DocumentProcessingService.java  the pipeline; the only class that writes evidence
    │   │   ├── ProcessingOutcome.java          EXTRACTED | SKIPPED_* | QUARANTINED | NOT_FOUND
    │   │   ├── QuarantineService.java          reasons, marks, bounded history
    │   │   └── DocProcStats.java               Micrometer + the counters behind /stats
    │   ├── controller/
    │   │   ├── DocProcController.java          contract §8.3
    │   │   ├── ExtractRequestDto.java          {objectKey, bucket?}
    │   │   ├── ExtractResponseDto.java         {text, metadata, pageCount, sha256, …}
    │   │   └── StatsResponseDto.java           counters + extractors + quarantine list
    │   └── web/
    │       └── DocProcExceptionHandler.java    → shared ErrorResponse shape
    ├── main/resources/application.yml          default / local / test profiles
    └── test/java/com/laserpay/pdei/docproc/extract/
        ├── TestDocuments.java                  generates the PDFs and .eml files under test
        ├── PdfBoxDocumentExtractorTest.java    generated-PDF extraction, metadata, no-OCR gap
        ├── EmlExtractorTest.java               headers, encoded words, quoted-printable, HTML
        └── ExtractorRegistryTest.java          selection order and refusal behaviour
```

---

## 4. Inbound contracts

### 4.1 Kafka topics consumed

| Topic | Group | What is handled |
|---|---|---|
| `pdei.evidence.events.v1` | `pdei-document-processor-service` | `EvidenceAdded` |
| `pdei.canonical.events.v1` | `pdei-document-processor-service` | `EvidenceAdded` |

**Why both.** Platform contract §4 lists this service as a consumer of `pdei.canonical.events.v1`,
while `Topics.forEventType(EvidenceAdded)` routes evidence events to `pdei.evidence.events.v1`.
Subscribing to only one would either contradict the contract or never see an `EvidenceAdded`.
Both together are safe because the idempotency claim is keyed on `(eventId, consumerGroup)`, not
on topic: an event appearing on both is processed exactly once.

Only `EvidenceAdded` triggers work. `EvidenceExpired` and `EvidenceInvalidated` are lifecycle
transitions on bytes that have not changed; re-parsing them would burn CPU to write identical
text.

**Idempotency** (contract §4): Redis `SETNX pdei:idem:{eventId}` (TTL 7d) as an advisory fast
path, confirmed against `processed_events` before anything is skipped, then the authoritative
atomic claim `ProcessedEventRepository.markProcessed(eventId, group)`. The claim commits in the
same transaction as the evidence-row update.

**Loop safety.** This service also *produces* to `pdei.evidence.events.v1`. Its own events carry
`payload.emittedBy = "document-processor-service"` and are skipped on sight; the
SKIPPED_UNCHANGED short-circuit is the second line of defence.

### 4.2 REST consumed (contract §8.3) — base `http://localhost:8086/docproc/v1`

```
POST /extract                  {objectKey, bucket?} -> {text, metadata, pageCount, sha256, …}
POST /reprocess/{evidenceId}   ?force=true (default)  -> ProcessingOutcome  (404 when unknown)
GET  /stats                    counters + registered extractors + recent quarantine entries
GET  /actuator/health|prometheus|metrics|info|loggers
```

### 4.3 Tables read / written

| Table | Access | Columns touched |
|---|---|---|
| `pdei.evidence` | read + **write** | `extracted_text`, `metadata`, `content_type`, `size_bytes`, `sha256` (only when previously null), `integrity_ok`, `integrity_verified_at` |
| `pdei.processed_events` | insert | idempotency claim |

`evidence.search_vector` is **never** written directly — the `trg_evidence_search_vector` trigger
from `V10__fts.sql` recomputes it whenever `extracted_text` changes.

### 4.4 Object storage

Bucket `pdei-evidence`, key layout per contract §11, read through
`com.laserpay.pdei.core.storage.ObjectStore`. `stat` before `get`, always, so an oversized object
is rejected without entering the heap.

---

## 5. Outbound contracts

### 5.1 Events produced

Topic `pdei.evidence.events.v1`, one event per successful extraction:

```json
{
  "eventType": "EvidenceAdded",
  "aggregateType": "EVIDENCE",
  "aggregateId": "EV-…",
  "source": "INTERNAL",
  "idempotencyKey": "docproc:text-extracted:{evidenceId}:{sha256}",
  "payload": {
    "emittedBy": "document-processor-service",
    "action": "TEXT_EXTRACTED",
    "evidenceId": "EV-…", "transactionId": "TX-…",
    "evidenceType": "DELIVERY_PROOF", "status": "ACTIVE", "version": 1,
    "sha256": "…", "extractor": "pdfbox", "contentType": "application/pdf",
    "sizeBytes": 48213, "pageCount": 2, "characters": 1841,
    "truncated": false, "warnings": [], "extractedAt": "…Z"
  }
}
```

**Why `EvidenceAdded` and not `EvidenceUpdated`.** `EventType` has no `EvidenceUpdated` member —
the enum is frozen by `docs/SHARED-LIBRARY-API.md` §1.3 and inventing a variant is forbidden.
`EvidenceAdded` carries the correct downstream semantics anyway: contract §7 says *any* EVIDENCE
event triggers readiness recomputation, which is exactly what should happen when an artifact's
text becomes available. The `idempotencyKey` is derived from `(evidenceId, sha256)`, so
re-extraction of unchanged bytes collapses to one logical fact downstream. Consumers that care
about the distinction read `payload.action`.

Topic `pdei.dlq.v1`: a `DeadLetterEnvelope` for a malformed envelope or a handler error, carrying
`topic/partition/offset` so the record can be replayed by the simulator's `REPLAY_EVENTS`.

### 5.2 Metrics (contract §13 names, plus module-local ones)

```
pdei_events_processed_total{service=document-processor-service,type,outcome}
pdei_events_duplicate_total{service=document-processor-service}
pdei_event_processing_latency_seconds{service,type}
pdei_docproc_quarantined_total{reason}        module-local
pdei_docproc_characters_indexed_total         module-local gauge
pdei_docproc_bytes_processed_total            module-local gauge
```

The `outcome` tag stays inside the platform's bounded vocabulary
(`MetricNames.Outcome`: `success` / `failure` / `skipped`) rather than emitting per-service status
names — widening a shared tag is how one cross-service dashboard becomes five incompatible panels.
The finer detail lives on the module-local counter and in `/stats`.

---

## 6. Extraction pipeline, in order

1. **Load the evidence row.** Object key, recorded sha256 and merchant come from Postgres, never
   from the event payload — an event can be replayed from a stale snapshot, the row cannot.
2. **`stat` then size check.** A 2 GB object never enters the heap just to be rejected.
3. **`getBytes` + recompute sha256.** A mismatch means the artifact changed underneath us:
   quarantine `HASH_MISMATCH`, set `integrity_ok = false`, and **do not index the text**. Text from
   a tampered artifact must never enter the search index, because search results become citations
   and citations become representment claims.
4. **Unchanged short-circuit.** If `metadata["docproc.sha256"]` already equals the recomputed hash
   and text exists, skip (`SKIPPED_UNCHANGED`) unless forced.
5. **Extract on the bounded pool, with a timeout.** A hung parser costs one quarantined document,
   not a stalled Kafka partition.
6. **Cap the text** at `max-text-chars` (a single Postgres `tsvector` is capped at 1 MB; an
   oversized document silently fails to index, so truncating here keeps search honest).
7. **Write** `extracted_text` + `docproc.*` metadata + integrity flags.
8. **Publish** the EVIDENCE event.

### Extractor selection order (`ExtractorRegistry`, first match wins)

| Order | Extractor | Claims |
|---|---|---|
| 1 | `pdfbox` | `application/pdf`, `.pdf`, or `%PDF-` magic bytes |
| 2 | `eml` | `message/rfc822`, `.eml`, `.msg` that looks like RFC 822 |
| 3 | `plaintext` | `text/*` (not html), `application/json`/`xml`/`x-ndjson`, `.txt .log .csv .tsv .json .ndjson .xml .md` |
| 4 | `tika` | everything else (the catch-all) |

Selection is never "try parsers until one stops throwing": that turns a corrupt PDF into a mystery
text file and makes the extraction path non-deterministic, which is unacceptable for something
feeding an evidence trail.

### Quarantine reasons

`OVERSIZE`, `OBJECT_MISSING`, `HASH_MISMATCH`, `UNDECODABLE`, `TIMEOUT`, `NO_TEXT`,
`STORAGE_ERROR`.

Nothing is deleted. The object, the row and the version history all survive; what changes is that
the text does not enter the index and `evidence.metadata` gains
`docproc.quarantine.{reason,detail,at}`. The mark is written in a `REQUIRES_NEW` transaction so it
survives a rollback of the surrounding processing transaction — losing it would leave an artifact
silently absent from search with nothing anywhere saying why. `POST /reprocess/{evidenceId}` is
the retry once the cause is fixed; a successful pass clears the marks.

---

## 7. Configuration and environment

All from `pdei.docproc.*` (see `DocProcProperties`) plus the shared `PDEI_*` variables:

| Property | Default | Why |
|---|---|---|
| `max-object-bytes` | 25 MiB | Ceiling before any read |
| `max-text-chars` | 500 000 | Postgres `tsvector` is capped at 1 MB |
| `tika-write-limit-chars` | 600 000 | Decompression-bomb guard inside Tika |
| `max-pdf-pages` | 500 | Page ceiling; exceeding it warns, does not fail |
| `extraction-timeout` | 30s | Wall-clock budget per artifact |
| `extractor-threads` | 4 | Pool that makes the timeout enforceable |
| `quarantine-history-size` | 100 | In-memory entries surfaced by `/stats` |
| `reextract-unchanged` | false | The consumer must not re-parse identical bytes |
| `publish-evidence-events` | true | Emit the EVIDENCE event after extraction |
| `consumer-enabled` | true (`PDEI_DOCPROC_CONSUMER_ENABLED`) | Exercise REST without a broker |
| `redis-dedupe-enabled` | true | Advisory fast path in front of Postgres |
| `idempotency-ttl` | 7d | Matches contract §12 |

Environment variables (contract §15): `PDEI_POSTGRES_URL`, `PDEI_POSTGRES_USER`,
`PDEI_POSTGRES_PASSWORD`, `PDEI_KAFKA_BOOTSTRAP`, `PDEI_REDIS_URL`, `PDEI_MINIO_ENDPOINT`,
`PDEI_MINIO_ACCESS_KEY`, `PDEI_MINIO_SECRET_KEY`, `OTEL_EXPORTER_OTLP_ENDPOINT`,
`OTEL_SERVICE_NAME`. Module-local: `PDEI_FLYWAY_ENABLED` (default `false` — one service should own
migration), `PDEI_DOCPROC_CONSUMER_ENABLED`.

Profiles: `default` (compose hostnames) · `local` (localhost, DEBUG) · `test` (no broker, no
publishing, no Redis dedupe).

**Kafka producer note.** The value serializer is `JsonSerializer`, not `StringSerializer`,
because evidence-core's `KafkaEventPublisher` hands the template a `CanonicalEvent` object rather
than a pre-serialised string. `spring.json.add.type.headers` is off — the envelope is a
cross-language wire contract and a Java class name in a header is noise plus coupling.

---

## 8. Dependencies on other modules

| Module | What is used |
|---|---|
| `evidence-core` | `storage.ObjectStore` + `storage.Buckets` (key layout, metadata keys), `spi.EventPublisherPort`, `config.CoreProperties` (MinIO coordinates) |
| `platform-persistence` | `EvidenceEntity`, `EvidenceRepository`, `ProcessedEventRepository`, and the autoconfigured entity/repository scan |
| `platform-common` | `CanonicalEvent`, `EventType`, `AggregateType`, `EventSource`, `Topics`, `ConsumerGroups`, `EventHeaders`, `Ids`, `Json`, `Hashes`, `Clocks`, `MetricNames`, `ErrorResponse`, `DeadLetterEnvelope` |

Runtime: PostgreSQL, Kafka, Redis (optional — degrades to Postgres-only dedupe), MinIO.

`pdei.core.jdbc.enabled` is **false** here: this service reads and writes evidence through JPA
repositories and does not need the evidence-core JDBC ports.

---

## 9. Build and run

```bash
# from Laserpay/backend
mvn -pl document-processor-service -am clean verify      # build + unit tests
mvn -pl document-processor-service spring-boot:run       # needs Postgres/Kafka/Redis/MinIO

# local profile against localhost infrastructure
SPRING_PROFILES_ACTIVE=local mvn -pl document-processor-service spring-boot:run

# container (build context is backend/, not the module directory)
docker build -f document-processor-service/Dockerfile -t pdei/document-processor-service .
docker run --rm --network pdei-net -p 8086:8086 pdei/document-processor-service
```

Smoke test:

```bash
curl -s localhost:8086/actuator/health
curl -s -XPOST localhost:8086/docproc/v1/extract \
  -H 'content-type: application/json' \
  -d '{"objectKey":"MER-1/TX-9/DELIVERY_PROOF/EV-3/v1/delivery-proof-3.txt"}' | jq .
curl -s localhost:8086/docproc/v1/stats | jq .
```

The simulator uploads real synthetic artifacts to `pdei-evidence`, so
`POST /sim/v1/scenarios/clean-delivery-defendable/run` on port 8088 is the fastest way to give
this service something to extract.

---

## 10. Extension points

- **A new format** — implement `DocumentExtractor`, register the bean, and insert it in
  `DocProcConfiguration.extractorRegistry(...)` *before* `tika`. Nothing else changes.
- **OCR** — a `TesseractExtractor` placed between `pdfbox` and `tika`, claiming `image/*` and PDFs
  whose text layer is empty. `PdfBoxDocumentExtractor.WARNING_NO_TEXT_LAYER` already marks exactly
  those documents, and `QuarantineService.Reason.NO_TEXT` already collects them.
- **Language detection / summarisation** — `ExtractionResult.metadata` is an open map; add
  `docproc.language` and it flows into `evidence.metadata` with no schema change.
- **Per-page citations** — `ExtractionResult.pages` already holds per-page text for PDFs; it is
  not yet persisted (see gaps).
- **Different storage** — swap the `ObjectStore` bean; this module only calls the interface.
- **Chunked/streaming extraction** — `ObjectStore.get(...)` returns an `InputStream`; the size
  ceiling is the only reason the byte-array path is used.

---

## 11. Known gaps and TODOs

1. **No OCR — deliberate and explicit.** Reference §25 puts OCR out of scope for this build: "the
   first prototype should use mostly structured synthetic evidence so that the core system is
   tested rather than an OCR pipeline." A scanned PDF with no text layer produces empty text plus
   `WARNING_NO_TEXT_LAYER` and is quarantined as `NO_TEXT` — a visible gap rather than a silently
   empty index. Images route to Tika, which reports the same thing. See the extension point above.
2. **Per-page text is extracted but not persisted.** `ExtractionResult.pages` exists and is
   correct; there is no column for it. A citation can currently say "in EV-4F2A", not "on page 3
   of EV-4F2A". Needs an `evidence_pages` table or a JSONB column before the AI narrative can cite
   page numbers.
3. **Quarantine history is in-memory and per-instance.** It is lost on restart and not shared
   across replicas. The durable half (the `docproc.quarantine.*` marks on `evidence.metadata`)
   survives; only the convenience list in `/stats` does not. A `quarantine_entries` table would fix
   it, but there is no migration for it and this module does not own the schema.
4. **`/stats` counters are per-instance and reset on restart.** Prometheus is the cross-instance
   answer; `/stats` is the single-worker answer.
5. **Nested multipart emails are skipped, with a warning.** `EmlExtractor` handles one level. A
   `multipart/mixed` containing a `multipart/alternative` records
   `"nested multipart part skipped"` rather than descending. Real mail clients put the text part at
   the first level often enough that this has not been worth the recursion yet.
6. **Email attachments are enumerated, not decoded.** By design: an attached PDF gets its own
   evidence record and its own extraction pass, and decoding it here would duplicate text into two
   FTS documents and break "one artifact, one sha256". The attachment names land in
   `eml.attachmentNames`.
7. **No integration test against real MinIO/Kafka/Postgres.** The extractors are covered by unit
   tests against generated documents; `DocumentProcessingService`, the consumer and the controller
   are not. Testcontainers (already in the reactor's dependency management, and already used by
   `platform-persistence`) is the intended route.
8. **Tika's write-limit exception is matched by simple class name**, not by import, so an upgrade
   that moves the class does not break the build. If Tika ever renames it, truncation would be
   misreported as a parse failure — the test to add is a document that exceeds the limit.
9. **`payloadAs`-style typed projection of the incoming event is not used.** The consumer reads
   only `aggregateId` and the `emittedBy` marker, deliberately: the evidence row is the source of
   truth for everything else, so the payload shape can change without touching this service.
10. **The consumer's short in-container retry (2 attempts, 1s) is a guess**, not a measured value.
    It absorbs a transient MinIO or Postgres blip; anything more persistent is dead-lettered.
