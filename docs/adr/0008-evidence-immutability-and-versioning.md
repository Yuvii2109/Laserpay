# ADR-0008 - Evidence is append-only and content-addressed

**Status:** Accepted

## Context
Reference doc section 12 and rule 39.8: historical versions must not be silently overwritten,
and provenance must be preserved for every artifact. A dispute reviewed months later must be
able to establish what the evidence looked like at the time of the transaction.

## Decision
- Every artifact is hashed with SHA-256 at write time; the hash is stored in Postgres and as
  MinIO object metadata.
- A new version never overwrites its parent. The parent moves to `SUPERSEDED` and stays
  retrievable; `parent_version` links the chain; `evidence_versions` retains every generation.
- Object keys embed the version:
  `{merchantId}/{transactionId}/{type}/{evidenceId}/v{n}/{filename}`. The bucket has
  versioning enabled as a second line of defence.
- Every artifact records `source`, `sourceEventId`, `createdAt` and `observedAt`.
- `EvidenceIntegrityService` re-hashes on demand; a mismatch forces `INVALIDATED`, emits
  `EvidenceInvalidated`, and writes an audit entry. It never silently repairs.

## Consequences
- Storage grows monotonically. Accepted - evidence artifacts are small and the retention
  window is bounded by dispute rules, not by us.
- "Delete evidence" as a chaos injection is a state transition, not a destruction, which is
  what makes the chaos demo safe to run repeatedly.
- Integrity is verifiable by anyone holding the hash, including the merchant.
