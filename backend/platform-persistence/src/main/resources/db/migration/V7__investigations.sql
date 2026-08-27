-- =====================================================================================
-- V7__investigations.sql  |  investigations, investigation_findings, ai_admission_log
--
-- These tables record what the AI (or the deterministic short-circuit) PROPOSED and what
-- the deterministic safety gate DECIDED. Nothing here mutates financial state: the AI
-- never writes to transactions, evidence, disputes or cases.
--
-- Confidence is stored in basis points (integer). No floating point in the store.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- investigations  (id prefix INV-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.investigations (
    investigation_id     VARCHAR(64) NOT NULL,
    case_id              VARCHAR(64),
    dispute_id           VARCHAR(64),
    merchant_id          VARCHAR(64) NOT NULL,
    transaction_id       VARCHAR(64) NOT NULL,

    classification       VARCHAR(32),
    confidence_bps       INTEGER,
    recommended_action   VARCHAR(48),
    safety_decision      VARCHAR(32),
    -- TRUE when the result came from a deterministic short-circuit (contract 9.4)
    deterministic        BOOLEAN     NOT NULL DEFAULT FALSE,

    reasoning_summary    TEXT,
    narrative            TEXT,
    supporting_evidence  JSONB,
    missing_evidence     JSONB,
    contradictions       JSONB,
    citations            JSONB,
    rejection_reasons    JSONB,
    context_snapshot     JSONB,

    provider             VARCHAR(32),
    model                VARCHAR(128),
    prompt_tokens        INTEGER,
    completion_tokens    INTEGER,
    latency_ms           BIGINT,
    attempt              INTEGER     NOT NULL DEFAULT 1,

    requested_at         TIMESTAMPTZ NOT NULL,
    completed_at         TIMESTAMPTZ,
    metadata             JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_investigations PRIMARY KEY (investigation_id),
    CONSTRAINT fk_investigations_case        FOREIGN KEY (case_id)        REFERENCES pdei.dispute_cases (case_id),
    CONSTRAINT fk_investigations_dispute     FOREIGN KEY (dispute_id)     REFERENCES pdei.disputes (dispute_id),
    CONSTRAINT fk_investigations_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT fk_investigations_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT ck_investigations_id_prefix CHECK (investigation_id LIKE 'INV-%'),
    CONSTRAINT ck_investigations_classification CHECK (classification IS NULL OR classification IN (
        'DEFENDABLE', 'WEAK', 'INDEFENSIBLE', 'INSUFFICIENT_EVIDENCE', 'AMBIGUOUS')),
    CONSTRAINT ck_investigations_action CHECK (recommended_action IS NULL OR recommended_action IN (
        'PREPARE_REPRESENTMENT', 'GATHER_MORE_EVIDENCE', 'ACCEPT_LIABILITY',
        'ESCALATE_TO_HUMAN', 'REQUEST_POLICY_REVIEW')),
    CONSTRAINT ck_investigations_safety CHECK (safety_decision IS NULL OR safety_decision IN (
        'ALLOW', 'ALLOW_WITH_REVIEW', 'DENY')),
    CONSTRAINT ck_investigations_confidence CHECK (confidence_bps IS NULL
                                                   OR confidence_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_investigations_attempt CHECK (attempt >= 1)
);

CREATE INDEX ix_investigations_case           ON pdei.investigations (case_id, completed_at DESC);
CREATE INDEX ix_investigations_dispute        ON pdei.investigations (dispute_id);
CREATE INDEX ix_investigations_merchant       ON pdei.investigations (merchant_id);
CREATE INDEX ix_investigations_transaction    ON pdei.investigations (transaction_id);
CREATE INDEX ix_investigations_classification ON pdei.investigations (classification);
CREATE INDEX ix_investigations_completed_at   ON pdei.investigations (completed_at DESC);

COMMENT ON COLUMN pdei.investigations.confidence_bps IS
    'InvestigationResult.confidence in basis points (9730 = 0.973). Integer by contract rule 4.';

-- -------------------------------------------------------------------------------------
-- investigation_findings  |  one row per claim/citation/contradiction, with the
-- deterministic validation outcome that AiResultValidator produced for it.
-- id convention: opaque UUID string
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.investigation_findings (
    finding_id          VARCHAR(64) NOT NULL,
    investigation_id    VARCHAR(64) NOT NULL,
    sequence_no         INTEGER     NOT NULL DEFAULT 0,
    finding_type        VARCHAR(32) NOT NULL,
    evidence_id         VARCHAR(64),
    related_evidence_id VARCHAR(64),
    evidence_type       VARCHAR(48),
    field               VARCHAR(128),
    claim               TEXT,
    detail              TEXT,
    validated           BOOLEAN     NOT NULL DEFAULT FALSE,
    validation_error    VARCHAR(256),
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_investigation_findings PRIMARY KEY (finding_id),
    CONSTRAINT fk_investigation_findings_investigation FOREIGN KEY (investigation_id)
        REFERENCES pdei.investigations (investigation_id) ON DELETE CASCADE,
    CONSTRAINT ck_investigation_findings_type CHECK (finding_type IN (
        'SUPPORTING_EVIDENCE', 'MISSING_EVIDENCE', 'CONTRADICTION',
        'CITATION', 'POLICY_VIOLATION', 'OBSERVATION')),
    CONSTRAINT ck_investigation_findings_evidence_type CHECK (evidence_type IS NULL OR evidence_type IN (
        'PAYMENT_PROOF', 'INVOICE', 'ORDER_RECORD', 'SHIPPING_RECORD',
        'DELIVERY_PROOF', 'REFUND_RECEIPT', 'CUSTOMER_COMMUNICATION',
        'MERCHANT_POLICY', 'TERMS_OF_SERVICE', 'AVS_CVV_RESULT',
        'DEVICE_FINGERPRINT', 'PRIOR_TRANSACTION_HISTORY', 'SIGNED_CONTRACT'))
);

CREATE INDEX ix_investigation_findings_inv       ON pdei.investigation_findings (investigation_id, sequence_no);
CREATE INDEX ix_investigation_findings_evidence  ON pdei.investigation_findings (evidence_id);
CREATE INDEX ix_investigation_findings_unvalid   ON pdei.investigation_findings (investigation_id)
    WHERE NOT validated;

-- -------------------------------------------------------------------------------------
-- ai_admission_log  |  append-only record of every admission-control decision
-- (contract 9.4). Components are 0..100 integers; priority is 0..100.
-- id convention: opaque UUID string
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.ai_admission_log (
    admission_id                     VARCHAR(64) NOT NULL,
    case_id                          VARCHAR(64),
    dispute_id                       VARCHAR(64),
    investigation_id                 VARCHAR(64),
    merchant_id                      VARCHAR(64) NOT NULL,
    transaction_id                   VARCHAR(64),

    admitted                         BOOLEAN     NOT NULL,
    priority                         INTEGER     NOT NULL,
    financial_impact_component       INTEGER     NOT NULL DEFAULT 0,
    deadline_urgency_component       INTEGER     NOT NULL DEFAULT 0,
    ambiguity_component              INTEGER     NOT NULL DEFAULT 0,
    deterministic_confidence_component INTEGER   NOT NULL DEFAULT 0,

    amount_minor                     BIGINT,
    currency                         CHAR(3),

    short_circuit                    VARCHAR(64),
    rate_limited                     BOOLEAN     NOT NULL DEFAULT FALSE,
    budget_key                       VARCHAR(64),
    budget_remaining                 INTEGER,
    reason                           VARCHAR(512),
    decided_at                       TIMESTAMPTZ NOT NULL,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata                         JSONB,

    CONSTRAINT pk_ai_admission_log PRIMARY KEY (admission_id),
    CONSTRAINT fk_ai_admission_case     FOREIGN KEY (case_id)     REFERENCES pdei.dispute_cases (case_id),
    CONSTRAINT fk_ai_admission_dispute  FOREIGN KEY (dispute_id)  REFERENCES pdei.disputes (dispute_id),
    CONSTRAINT fk_ai_admission_merchant FOREIGN KEY (merchant_id) REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT ck_ai_admission_priority CHECK (priority BETWEEN 0 AND 100),
    CONSTRAINT ck_ai_admission_money CHECK ((amount_minor IS NULL) = (currency IS NULL)),
    CONSTRAINT ck_ai_admission_short_circuit CHECK (short_circuit IS NULL OR short_circuit IN (
        'ALL_MANDATORY_SATISFIED', 'NO_EVIDENCE', 'PAST_DEADLINE',
        'BUDGET_EXHAUSTED', 'RATE_LIMITED', 'BELOW_PRIORITY_THRESHOLD'))
);

CREATE INDEX ix_ai_admission_merchant ON pdei.ai_admission_log (merchant_id, decided_at DESC);
CREATE INDEX ix_ai_admission_case     ON pdei.ai_admission_log (case_id);
CREATE INDEX ix_ai_admission_decided  ON pdei.ai_admission_log (decided_at DESC);
CREATE INDEX ix_ai_admission_admitted ON pdei.ai_admission_log (admitted, decided_at DESC);
