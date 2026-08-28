-- =====================================================================================
-- V12 - the two readiness columns that have no equivalent anywhere in V6.
--
-- JdbcReadinessRepository read four columns V6 never created: gaps_json,
-- contradictions_json, penalty_points and policy_version_id. Three of those are answered
-- without a migration:
--
--   gaps_json          gaps already have their own table. readiness_gaps is the normalised
--                      home, and the at-risk feed (GET /api/v1/gaps) queries across
--                      merchants by type and severity - which is exactly why it exists and
--                      exactly what a JSON blob on the snapshot could not serve. The
--                      repository now reads gaps back by snapshot_id instead of storing a
--                      second copy that could drift from the first.
--   penalty_points     is penalty_total.
--   policy_version_id  is policy_version.
--
-- The two below have no such answer.
--
--   readiness_gaps.expires_at
--     A gap carries the instant the thing causing it lapses - for EXPIRING_SOON it is the
--     evidence's own expires_at, copied by GapDetector so the at-risk feed can rank by
--     urgency without joining back to evidence. There is no column for it, so every gap
--     was written with its deadline silently dropped. resolved_at is a different fact (when
--     the gap stopped being true), and detected_at is when it started.
--
--   readiness_snapshots.contradictions
--     Contradictions have no table at all - V6 stores only contradiction_count. Unlike
--     gaps, they are not queried across merchants: they are read back with the snapshot
--     that produced them, to show on the transaction and to feed the AI investigation
--     context. So jsonb on the snapshot is the right shape here, matching how the same
--     table already stores `requirements`. If a contradictions feed ever needs to span
--     merchants, that is the point to normalise it - not before.
--
-- Both are nullable with no backfill: existing snapshots genuinely do not have this data,
-- and inventing it would be worse than admitting the gap.
-- =====================================================================================

ALTER TABLE pdei.readiness_gaps
    ADD COLUMN expires_at TIMESTAMPTZ;

ALTER TABLE pdei.readiness_snapshots
    ADD COLUMN contradictions JSONB;

-- The at-risk feed orders by severity then recency, but the genuinely urgent gap is the one
-- about to lapse. Partial: a gap with no deadline never sorts into this index.
CREATE INDEX ix_readiness_gaps_expires_at
    ON pdei.readiness_gaps (expires_at)
    WHERE expires_at IS NOT NULL AND resolved = FALSE;

COMMENT ON COLUMN pdei.readiness_gaps.expires_at IS
    'When the condition behind this gap lapses (for EXPIRING_SOON, the evidence''s own expiry). Distinct from resolved_at.';
COMMENT ON COLUMN pdei.readiness_snapshots.contradictions IS
    'ContradictionView list for this computation. Gaps live in readiness_gaps; contradictions have no table because nothing queries them across merchants.';
