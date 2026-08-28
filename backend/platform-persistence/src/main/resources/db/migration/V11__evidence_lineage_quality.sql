-- =====================================================================================
-- V11 - the three evidence columns the contract always required and V3 never created.
--
-- PLATFORM-CONTRACT sections 6 and 7 describe behaviour that cannot be implemented without
-- them, and evidence-core was written against them: JdbcEvidenceRepository selected all three
-- by name, so every read and write of pdei.evidence failed at runtime with
--
--     BadSqlGrammarException ... column "id" does not exist
--
-- and the platform held zero evidence. That is the whole product, so these are not optional
-- extras - they are the missing half of V3.
--
-- Why each one, and why nothing existing substitutes for it:
--
--   parent_evidence_id  The BACKWARD pointer of the version chain. EvidenceLineageService
--                       walks parent -> parent to the root; EvidenceGraphService renders the
--                       same edge as SUPERSEDES. V3 has superseded_by, which is the FORWARD
--                       pointer ("this row was replaced by that one"). They are complements:
--                       the forward pointer alone cannot be followed upward without a reverse
--                       scan of the whole table, which is exactly what an indexed column is
--                       for. Correctness property 4 (evidence immutability) depends on it.
--
--   provenance_verified Raises GapType.UNVERIFIABLE_PROVENANCE, which carries the -20 penalty
--                       in the readiness formula (contract section 7). V3's integrity_ok is a
--                       different fact: integrity is "the bytes still hash to what we recorded",
--                       provenance is "we can prove where this came from". An artifact can be
--                       byte-perfect and still have no defensible origin.
--
--   quality_score       Raises GapType.LOW_QUALITY. POST /api/v1/evidence already accepts and
--                       validates it as 0.0-1.0, so the API surface has always assumed it.
--                       DOUBLE PRECISION, not minor units: the no-floating-point rule is about
--                       MONEY, and a quality score is never summed, compared to an amount, or
--                       rendered as currency. Readiness rounds once, at the end, on the integer
--                       score - a double here cannot make that non-deterministic.
--
-- Backfill: every existing row predates the columns, so there is nothing to derive them from.
-- provenance_verified defaults to FALSE rather than TRUE deliberately - unverified is the
-- honest state for evidence whose provenance was never checked, and the readiness penalty it
-- triggers is the correct signal. quality_score and parent_evidence_id stay NULL, meaning
-- "unknown" and "no parent" respectively.
-- =====================================================================================

ALTER TABLE pdei.evidence
    ADD COLUMN parent_evidence_id  VARCHAR(64),
    ADD COLUMN quality_score       DOUBLE PRECISION,
    ADD COLUMN provenance_verified BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN status_reason       VARCHAR(512);

-- status_reason, and why it is not invalidated_reason.
-- EvidenceRepositoryPort.updateStatus(id, status, at, reason) is called with four statuses -
-- SUPERSEDED ("superseded by EV-..."), EXPIRED, EXPIRING ("expiry window entered") and
-- INVALIDATED - so V3's invalidated_reason can record one transition in four and drops the
-- other three on the floor. status_reason holds the reason for the LATEST transition, whatever
-- it was. invalidated_reason keeps its narrower meaning and is still written (alongside
-- invalidated_at) when the status actually becomes INVALIDATED, so EvidenceEntity's mapping of
-- it stays truthful. Backfilled from it here so no existing invalidation loses its reason.
UPDATE pdei.evidence
   SET status_reason = invalidated_reason
 WHERE invalidated_reason IS NOT NULL;

-- Same shape as fk_evidence_superseded: a parent is another evidence row, and it must exist.
ALTER TABLE pdei.evidence
    ADD CONSTRAINT fk_evidence_parent
        FOREIGN KEY (parent_evidence_id) REFERENCES pdei.evidence (evidence_id);

-- 0.0-1.0 is enforced by the API (@DecimalMin/@DecimalMax on EvidenceUploadRequest); enforce it
-- here too so a direct writer cannot introduce a score the policy floor comparison would
-- silently misread. NULL passes: "not scored" is distinct from "scored zero".
ALTER TABLE pdei.evidence
    ADD CONSTRAINT ck_evidence_quality_score
        CHECK (quality_score IS NULL OR (quality_score >= 0.0 AND quality_score <= 1.0));

-- The lineage walk is "find the parent of this row" repeated to the root, so the index that
-- matters is on the pointer column itself.
CREATE INDEX ix_evidence_parent_evidence_id
    ON pdei.evidence (parent_evidence_id)
    WHERE parent_evidence_id IS NOT NULL;

-- GapDetector scans a merchant's evidence for rows below the policy quality floor and for
-- unverified provenance. Both are selective predicates over a merchant's rows, not the table.
CREATE INDEX ix_evidence_quality_gaps
    ON pdei.evidence (merchant_id, quality_score)
    WHERE quality_score IS NOT NULL;

CREATE INDEX ix_evidence_unverified_provenance
    ON pdei.evidence (merchant_id)
    WHERE provenance_verified = FALSE;

COMMENT ON COLUMN pdei.evidence.parent_evidence_id IS
    'Backward pointer of the version chain; complement of superseded_by. NULL at the root.';
COMMENT ON COLUMN pdei.evidence.quality_score IS
    'Extraction/capture quality 0.0-1.0. NULL means never scored. Below the policy floor raises GapType.LOW_QUALITY.';
COMMENT ON COLUMN pdei.evidence.provenance_verified IS
    'Whether this artifact''s origin was verified. FALSE raises GapType.UNVERIFIABLE_PROVENANCE (-20, contract section 7).';
COMMENT ON COLUMN pdei.evidence.status_reason IS
    'Reason for the most recent status transition (any status). invalidated_reason is the INVALIDATED-only subset.';
