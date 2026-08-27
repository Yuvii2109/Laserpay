-- =====================================================================================
-- V3__evidence.sql  |  evidence, evidence_versions, evidence_relationships
--
-- Evidence bytes live in MinIO (bucket pdei-evidence); PostgreSQL owns the metadata,
-- the integrity hash and the lineage. Version history is append-only and is NEVER
-- overwritten (contract / reference section 12).
-- =====================================================================================

-- Guard function reused by every append-only table in this schema.
CREATE OR REPLACE FUNCTION pdei.fn_reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION
        'pdei.% is append-only: % is not permitted (use TRUNCATE for test fixtures)',
        TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

COMMENT ON FUNCTION pdei.fn_reject_mutation() IS
    'Blocks UPDATE/DELETE on append-only tables (evidence_versions, audit_events). TRUNCATE bypasses row triggers.';

-- -------------------------------------------------------------------------------------
-- evidence  (id prefix EV-)  -- current state of one evidence item
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.evidence (
    evidence_id           VARCHAR(64) NOT NULL,
    merchant_id           VARCHAR(64) NOT NULL,
    transaction_id        VARCHAR(64),
    customer_id           VARCHAR(64),

    -- optional link to the specific aggregate the item evidences (ORDER/SHIPMENT/...)
    related_entity_type   VARCHAR(32),
    related_entity_id     VARCHAR(64),

    type                  VARCHAR(48) NOT NULL,
    status                VARCHAR(32) NOT NULL,
    source                VARCHAR(32) NOT NULL,

    current_version       INTEGER     NOT NULL DEFAULT 1,
    object_key            VARCHAR(512),
    content_type          VARCHAR(128),
    size_bytes            BIGINT,
    filename              VARCHAR(256),
    sha256                VARCHAR(64),

    title                 VARCHAR(256),
    summary               TEXT,
    extracted_text        TEXT,

    -- optional monetary value asserted by the artifact (invoice total, refund amount, ...)
    amount_minor          BIGINT,
    currency              CHAR(3),

    source_event_id       VARCHAR(64),
    captured_at           TIMESTAMPTZ,
    observed_at           TIMESTAMPTZ NOT NULL,
    effective_from        TIMESTAMPTZ,
    expires_at            TIMESTAMPTZ,
    invalidated_at        TIMESTAMPTZ,
    invalidated_reason    VARCHAR(512),
    superseded_by         VARCHAR(64),

    integrity_verified_at TIMESTAMPTZ,
    integrity_ok          BOOLEAN,

    provenance            JSONB,
    metadata              JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_evidence PRIMARY KEY (evidence_id),
    CONSTRAINT fk_evidence_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT fk_evidence_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_evidence_customer    FOREIGN KEY (customer_id)    REFERENCES pdei.customers (customer_id),
    CONSTRAINT fk_evidence_superseded  FOREIGN KEY (superseded_by)  REFERENCES pdei.evidence (evidence_id),
    CONSTRAINT ck_evidence_id_prefix CHECK (evidence_id LIKE 'EV-%'),
    CONSTRAINT ck_evidence_type CHECK (type IN (
        'PAYMENT_PROOF', 'INVOICE', 'ORDER_RECORD', 'SHIPPING_RECORD',
        'DELIVERY_PROOF', 'REFUND_RECEIPT', 'CUSTOMER_COMMUNICATION',
        'MERCHANT_POLICY', 'TERMS_OF_SERVICE', 'AVS_CVV_RESULT',
        'DEVICE_FINGERPRINT', 'PRIOR_TRANSACTION_HISTORY', 'SIGNED_CONTRACT')),
    CONSTRAINT ck_evidence_status CHECK (status IN (
        'PENDING', 'ACTIVE', 'EXPIRING', 'EXPIRED', 'INVALIDATED', 'SUPERSEDED')),
    CONSTRAINT ck_evidence_source CHECK (source IN (
        'PSP_ADAPTER', 'ORDER_SYSTEM', 'LOGISTICS', 'CRM', 'DOCUMENT_UPLOAD',
        'MERCHANT_PORTAL', 'SIMULATOR', 'INTERNAL_DERIVED')),
    CONSTRAINT ck_evidence_related_entity_type CHECK (related_entity_type IS NULL OR related_entity_type IN (
        'MERCHANT', 'CUSTOMER', 'TRANSACTION', 'PAYMENT', 'ORDER', 'SHIPMENT', 'DELIVERY',
        'REFUND', 'COMMUNICATION', 'EVIDENCE', 'POLICY', 'DISPUTE', 'CASE')),
    CONSTRAINT ck_evidence_version CHECK (current_version >= 1),
    -- money is either fully present or fully absent; never half a Money
    CONSTRAINT ck_evidence_money CHECK ((amount_minor IS NULL) = (currency IS NULL))
);

CREATE INDEX ix_evidence_merchant       ON pdei.evidence (merchant_id);
CREATE INDEX ix_evidence_transaction    ON pdei.evidence (transaction_id);
CREATE INDEX ix_evidence_status         ON pdei.evidence (status);
CREATE INDEX ix_evidence_type           ON pdei.evidence (type);
CREATE INDEX ix_evidence_merchant_type  ON pdei.evidence (merchant_id, type, status);
CREATE INDEX ix_evidence_tx_status      ON pdei.evidence (transaction_id, status);
CREATE INDEX ix_evidence_expires_at     ON pdei.evidence (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX ix_evidence_created_at     ON pdei.evidence (created_at DESC);
CREATE INDEX ix_evidence_source_event   ON pdei.evidence (source_event_id);
CREATE INDEX ix_evidence_related_entity ON pdei.evidence (related_entity_type, related_entity_id);

-- One content hash may only be attached once per transaction: this is what makes
-- EvidenceRepository.findByShaAndTransactionId(...) an Optional rather than a List, and
-- what makes re-delivery of the same source event a no-op.
CREATE UNIQUE INDEX ux_evidence_tx_sha ON pdei.evidence (transaction_id, sha256)
    WHERE transaction_id IS NOT NULL AND sha256 IS NOT NULL;

COMMENT ON TABLE  pdei.evidence            IS 'Current state of an evidence artifact. Bytes live in MinIO, this row owns metadata, hash and provenance.';
COMMENT ON COLUMN pdei.evidence.object_key IS 'MinIO key: {merchantId}/{transactionId}/{evidenceType}/{evidenceId}/v{version}/{filename}';

-- -------------------------------------------------------------------------------------
-- evidence_versions  |  APPEND-ONLY full version history
-- id convention: {evidenceId}-v{versionNumber}
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.evidence_versions (
    evidence_version_id VARCHAR(64) NOT NULL,
    evidence_id         VARCHAR(64) NOT NULL,
    version_number      INTEGER     NOT NULL,
    parent_version      INTEGER,
    sha256              VARCHAR(64) NOT NULL,
    object_key          VARCHAR(512) NOT NULL,
    content_type        VARCHAR(128),
    size_bytes          BIGINT,
    filename            VARCHAR(256),
    status              VARCHAR(32) NOT NULL,
    source              VARCHAR(32) NOT NULL,
    source_event_id     VARCHAR(64),
    change_reason       VARCHAR(512),
    created_by          VARCHAR(128),
    observed_at         TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata            JSONB,

    CONSTRAINT pk_evidence_versions PRIMARY KEY (evidence_version_id),
    CONSTRAINT uq_evidence_versions UNIQUE (evidence_id, version_number),
    -- deliberately RESTRICT, not CASCADE: history outlives careless deletes
    CONSTRAINT fk_evidence_versions_evidence FOREIGN KEY (evidence_id)
        REFERENCES pdei.evidence (evidence_id) ON DELETE RESTRICT,
    CONSTRAINT ck_evidence_versions_number CHECK (version_number >= 1),
    CONSTRAINT ck_evidence_versions_parent CHECK (parent_version IS NULL
                                                  OR parent_version < version_number),
    CONSTRAINT ck_evidence_versions_status CHECK (status IN (
        'PENDING', 'ACTIVE', 'EXPIRING', 'EXPIRED', 'INVALIDATED', 'SUPERSEDED')),
    CONSTRAINT ck_evidence_versions_source CHECK (source IN (
        'PSP_ADAPTER', 'ORDER_SYSTEM', 'LOGISTICS', 'CRM', 'DOCUMENT_UPLOAD',
        'MERCHANT_PORTAL', 'SIMULATOR', 'INTERNAL_DERIVED'))
);

CREATE INDEX ix_evidence_versions_evidence ON pdei.evidence_versions (evidence_id, version_number DESC);
CREATE INDEX ix_evidence_versions_sha      ON pdei.evidence_versions (sha256);
CREATE INDEX ix_evidence_versions_created  ON pdei.evidence_versions (created_at DESC);

CREATE TRIGGER trg_evidence_versions_immutable
    BEFORE UPDATE OR DELETE ON pdei.evidence_versions
    FOR EACH ROW EXECUTE FUNCTION pdei.fn_reject_mutation();

COMMENT ON TABLE pdei.evidence_versions IS 'Append-only evidence version chain (parent_version, sha256, object_key). Historical versions are never overwritten.';

-- -------------------------------------------------------------------------------------
-- evidence_relationships  |  the evidence graph edges
-- id convention: opaque UUID string (no contract prefix defined)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.evidence_relationships (
    relationship_id   VARCHAR(64) NOT NULL,
    from_evidence_id  VARCHAR(64) NOT NULL,
    to_evidence_id    VARCHAR(64) NOT NULL,
    relationship_type VARCHAR(32) NOT NULL,
    -- detector confidence in basis points (0..10000); integer, never a float
    confidence_bps    INTEGER,
    detected_by       VARCHAR(32) NOT NULL DEFAULT 'DETERMINISTIC',
    field             VARCHAR(128),
    detail            TEXT,
    metadata          JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_evidence_relationships PRIMARY KEY (relationship_id),
    CONSTRAINT uq_evidence_relationships UNIQUE (from_evidence_id, to_evidence_id, relationship_type),
    CONSTRAINT fk_evidence_rel_from FOREIGN KEY (from_evidence_id) REFERENCES pdei.evidence (evidence_id),
    CONSTRAINT fk_evidence_rel_to   FOREIGN KEY (to_evidence_id)   REFERENCES pdei.evidence (evidence_id),
    CONSTRAINT ck_evidence_rel_type CHECK (relationship_type IN (
        'SUPERSEDES', 'SUPPORTS', 'CONTRADICTS', 'DERIVED_FROM',
        'REFERENCES', 'DUPLICATE_OF', 'ATTACHED_TO')),
    CONSTRAINT ck_evidence_rel_detected_by CHECK (detected_by IN (
        'DETERMINISTIC', 'AI', 'MERCHANT', 'SYSTEM')),
    CONSTRAINT ck_evidence_rel_confidence CHECK (confidence_bps IS NULL
                                                 OR confidence_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_evidence_rel_no_self_loop CHECK (from_evidence_id <> to_evidence_id)
);

CREATE INDEX ix_evidence_rel_from ON pdei.evidence_relationships (from_evidence_id);
CREATE INDEX ix_evidence_rel_to   ON pdei.evidence_relationships (to_evidence_id);
CREATE INDEX ix_evidence_rel_type ON pdei.evidence_relationships (relationship_type);
