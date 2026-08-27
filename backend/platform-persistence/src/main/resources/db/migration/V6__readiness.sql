-- =====================================================================================
-- V6__readiness.sql  |  readiness_snapshots, readiness_gaps
--
-- Readiness is deterministic (contract section 7). Every recomputation appends a new
-- snapshot; the previous snapshot for the (transaction, reasonCode) pair is flagged
-- is_current = FALSE. Gaps hang off the snapshot that detected them and drive the
-- at-risk feed (GET /gaps?merchantId&type&severity).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- readiness_snapshots  |  id convention: opaque UUID string
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.readiness_snapshots (
    snapshot_id            VARCHAR(64) NOT NULL,
    transaction_id         VARCHAR(64) NOT NULL,
    merchant_id            VARCHAR(64) NOT NULL,
    reason_code            VARCHAR(48),

    score                  INTEGER     NOT NULL,
    band                   VARCHAR(32) NOT NULL,
    base_score             INTEGER     NOT NULL DEFAULT 0,
    penalty_total          INTEGER     NOT NULL DEFAULT 0,

    satisfied_weight       INTEGER     NOT NULL DEFAULT 0,
    total_weight           INTEGER     NOT NULL DEFAULT 0,
    mandatory_total        INTEGER     NOT NULL DEFAULT 0,
    mandatory_satisfied    INTEGER     NOT NULL DEFAULT 0,
    recommended_total      INTEGER     NOT NULL DEFAULT 0,
    recommended_satisfied  INTEGER     NOT NULL DEFAULT 0,
    gap_count              INTEGER     NOT NULL DEFAULT 0,
    contradiction_count    INTEGER     NOT NULL DEFAULT 0,

    -- serialized List<RequirementView> exactly as the API returns it
    requirements           JSONB,
    policy_id              VARCHAR(64),
    policy_version         INTEGER,
    trigger_event_id       VARCHAR(64),
    trigger_reason         VARCHAR(64),
    is_current             BOOLEAN     NOT NULL DEFAULT TRUE,
    computed_at            TIMESTAMPTZ NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_readiness_snapshots PRIMARY KEY (snapshot_id),
    CONSTRAINT fk_readiness_snapshots_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_readiness_snapshots_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT fk_readiness_snapshots_policy      FOREIGN KEY (policy_id)      REFERENCES pdei.policies (policy_id),
    CONSTRAINT ck_readiness_snapshots_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_readiness_snapshots_band CHECK (band IN (
        'READY', 'NEARLY_READY', 'AT_RISK', 'NOT_READY')),
    CONSTRAINT ck_readiness_snapshots_reason CHECK (reason_code IS NULL OR reason_code IN (
        'GOODS_NOT_RECEIVED', 'SERVICE_NOT_RENDERED', 'PRODUCT_NOT_AS_DESCRIBED',
        'DUPLICATE_PROCESSING', 'CREDIT_NOT_PROCESSED', 'SUBSCRIPTION_CANCELLED',
        'FRAUDULENT_TRANSACTION', 'UNRECOGNIZED_TRANSACTION',
        'INCORRECT_AMOUNT', 'PAID_BY_OTHER_MEANS')),
    CONSTRAINT ck_readiness_snapshots_trigger CHECK (trigger_reason IS NULL OR trigger_reason IN (
        'EVIDENCE_EVENT', 'ENTITY_STATE_CHANGE', 'POLICY_VERSION_CHANGE',
        'NIGHTLY_SWEEP', 'MANUAL_RECOMPUTE', 'DISPUTE_EVENT'))
);

CREATE INDEX ix_readiness_snapshots_tx        ON pdei.readiness_snapshots (transaction_id, computed_at DESC);
CREATE INDEX ix_readiness_snapshots_merchant  ON pdei.readiness_snapshots (merchant_id);
CREATE INDEX ix_readiness_snapshots_band      ON pdei.readiness_snapshots (merchant_id, band);
CREATE INDEX ix_readiness_snapshots_computed  ON pdei.readiness_snapshots (computed_at DESC);
CREATE INDEX ix_readiness_snapshots_current   ON pdei.readiness_snapshots (transaction_id)
    WHERE is_current;

COMMENT ON COLUMN pdei.readiness_snapshots.base_score    IS 'Weighted requirement satisfaction before penalties (contract section 7).';
COMMENT ON COLUMN pdei.readiness_snapshots.penalty_total IS 'Sum of contradiction / expiry / provenance penalties applied to base_score.';

-- -------------------------------------------------------------------------------------
-- readiness_gaps  |  id convention: opaque UUID string
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.readiness_gaps (
    gap_id               VARCHAR(64) NOT NULL,
    snapshot_id          VARCHAR(64),
    transaction_id       VARCHAR(64) NOT NULL,
    merchant_id          VARCHAR(64) NOT NULL,
    type                 VARCHAR(32) NOT NULL,
    severity             VARCHAR(16) NOT NULL,
    evidence_type        VARCHAR(48),
    evidence_id          VARCHAR(64),
    related_evidence_id  VARCHAR(64),
    requirement_strength VARCHAR(16),
    detail               TEXT,
    remediation          TEXT,
    penalty_applied      INTEGER     NOT NULL DEFAULT 0,
    detected_at          TIMESTAMPTZ NOT NULL,
    resolved             BOOLEAN     NOT NULL DEFAULT FALSE,
    resolved_at          TIMESTAMPTZ,
    metadata             JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_readiness_gaps PRIMARY KEY (gap_id),
    CONSTRAINT fk_readiness_gaps_snapshot    FOREIGN KEY (snapshot_id)    REFERENCES pdei.readiness_snapshots (snapshot_id) ON DELETE SET NULL,
    CONSTRAINT fk_readiness_gaps_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_readiness_gaps_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT fk_readiness_gaps_evidence    FOREIGN KEY (evidence_id)    REFERENCES pdei.evidence (evidence_id),
    CONSTRAINT ck_readiness_gaps_type CHECK (type IN (
        'MISSING', 'EXPIRED', 'EXPIRING_SOON', 'CONTRADICTORY',
        'UNVERIFIABLE_PROVENANCE', 'LOW_QUALITY', 'VERSION_CONFLICT')),
    CONSTRAINT ck_readiness_gaps_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_readiness_gaps_evidence_type CHECK (evidence_type IS NULL OR evidence_type IN (
        'PAYMENT_PROOF', 'INVOICE', 'ORDER_RECORD', 'SHIPPING_RECORD',
        'DELIVERY_PROOF', 'REFUND_RECEIPT', 'CUSTOMER_COMMUNICATION',
        'MERCHANT_POLICY', 'TERMS_OF_SERVICE', 'AVS_CVV_RESULT',
        'DEVICE_FINGERPRINT', 'PRIOR_TRANSACTION_HISTORY', 'SIGNED_CONTRACT')),
    CONSTRAINT ck_readiness_gaps_strength CHECK (requirement_strength IS NULL OR requirement_strength IN (
        'MANDATORY', 'RECOMMENDED', 'OPTIONAL', 'PROHIBITED'))
);

CREATE INDEX ix_readiness_gaps_merchant_sev  ON pdei.readiness_gaps (merchant_id, severity) WHERE NOT resolved;
CREATE INDEX ix_readiness_gaps_merchant_type ON pdei.readiness_gaps (merchant_id, type)     WHERE NOT resolved;
CREATE INDEX ix_readiness_gaps_transaction   ON pdei.readiness_gaps (transaction_id);
CREATE INDEX ix_readiness_gaps_snapshot      ON pdei.readiness_gaps (snapshot_id);
CREATE INDEX ix_readiness_gaps_detected_at   ON pdei.readiness_gaps (detected_at DESC);
