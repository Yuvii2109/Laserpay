-- =====================================================================================
-- V10__fts.sql  |  PostgreSQL full-text search for the evidence explorer
--
-- Search is Postgres FTS on purpose (reference section 24): no OpenSearch until a real
-- workload justifies it. The tsvector columns are maintained by triggers so that any
-- writer -- JPA, raw SQL, Flyway seed data -- keeps the index correct.
--
-- Query side: EvidenceRepository.search(...) uses websearch_to_tsquery('english', :q),
-- which accepts human input ("delivery -refund", quoted phrases) without throwing on
-- malformed syntax.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- evidence.search_vector
--   A = title            (strongest)
--   B = summary, type    (type is de-underscored: DELIVERY_PROOF -> "delivery proof")
--   C = filename, related entity id
--   D = extracted document text (largest, weakest)
-- -------------------------------------------------------------------------------------
ALTER TABLE pdei.evidence ADD COLUMN search_vector tsvector;

CREATE OR REPLACE FUNCTION pdei.fn_evidence_search_vector() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', coalesce(NEW.title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(NEW.summary, '')), 'B') ||
        setweight(to_tsvector('english', replace(coalesce(NEW.type, ''), '_', ' ')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.filename, '')), 'C') ||
        setweight(to_tsvector('english', coalesce(NEW.related_entity_id, '')), 'C') ||
        setweight(to_tsvector('english', coalesce(NEW.extracted_text, '')), 'D');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_evidence_search_vector
    BEFORE INSERT OR UPDATE OF title, summary, filename, extracted_text, type, related_entity_id
    ON pdei.evidence
    FOR EACH ROW EXECUTE FUNCTION pdei.fn_evidence_search_vector();

-- Backfill anything written before this migration (the UPDATE target list must mention a
-- watched column for the trigger to fire).
UPDATE pdei.evidence SET title = title;

CREATE INDEX ix_evidence_search_vector ON pdei.evidence USING GIN (search_vector);

COMMENT ON COLUMN pdei.evidence.search_vector IS
    'Weighted tsvector maintained by trg_evidence_search_vector. Query with websearch_to_tsquery(''english'', :q).';

-- -------------------------------------------------------------------------------------
-- communications.search_vector
-- Customer communications are first-class evidence for GOODS_NOT_RECEIVED and
-- SUBSCRIPTION_CANCELLED disputes, so the same search surface covers them.
--   A = subject, B = sender/recipient, D = body
-- -------------------------------------------------------------------------------------
ALTER TABLE pdei.communications ADD COLUMN search_vector tsvector;

CREATE OR REPLACE FUNCTION pdei.fn_communications_search_vector() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', coalesce(NEW.subject, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(NEW.sender, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.recipient, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.body, '')), 'D');
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_communications_search_vector
    BEFORE INSERT OR UPDATE OF subject, body, sender, recipient
    ON pdei.communications
    FOR EACH ROW EXECUTE FUNCTION pdei.fn_communications_search_vector();

UPDATE pdei.communications SET subject = subject;

CREATE INDEX ix_communications_search_vector ON pdei.communications USING GIN (search_vector);
