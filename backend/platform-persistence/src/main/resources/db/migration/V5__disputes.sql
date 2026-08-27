-- =====================================================================================
-- V5__disputes.sql  |  disputes, dispute_cases, case_evidence
--
-- A dispute is the external fact. A dispute_case is the internal Temporal-driven
-- workflow instance that assembles a representment for it. case_evidence pins the
-- exact evidence version that was attached, with the hash observed at attach time.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- disputes  (id prefix DSP-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.disputes (
    dispute_id      VARCHAR(64) NOT NULL,
    merchant_id     VARCHAR(64) NOT NULL,
    transaction_id  VARCHAR(64) NOT NULL,
    customer_id     VARCHAR(64),
    psp_dispute_ref VARCHAR(128),
    reason_code     VARCHAR(48) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    amount_minor    BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,
    network         VARCHAR(32),
    stage           VARCHAR(32),
    source          VARCHAR(32) NOT NULL DEFAULT 'PSP_ADAPTER',
    description     TEXT,
    opened_at       TIMESTAMPTZ NOT NULL,
    deadline_at     TIMESTAMPTZ,
    closed_at       TIMESTAMPTZ,
    outcome         VARCHAR(32),
    last_event_id   VARCHAR(64),
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_disputes PRIMARY KEY (dispute_id),
    CONSTRAINT fk_disputes_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT fk_disputes_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_disputes_customer    FOREIGN KEY (customer_id)    REFERENCES pdei.customers (customer_id),
    CONSTRAINT ck_disputes_id_prefix CHECK (dispute_id LIKE 'DSP-%'),
    CONSTRAINT ck_disputes_reason_code CHECK (reason_code IN (
        'GOODS_NOT_RECEIVED', 'SERVICE_NOT_RENDERED', 'PRODUCT_NOT_AS_DESCRIBED',
        'DUPLICATE_PROCESSING', 'CREDIT_NOT_PROCESSED', 'SUBSCRIPTION_CANCELLED',
        'FRAUDULENT_TRANSACTION', 'UNRECOGNIZED_TRANSACTION',
        'INCORRECT_AMOUNT', 'PAID_BY_OTHER_MEANS')),
    CONSTRAINT ck_disputes_status CHECK (status IN (
        'OPEN', 'EVIDENCE_GATHERING', 'UNDER_INVESTIGATION', 'AWAITING_HUMAN_REVIEW',
        'REPRESENTMENT_PREPARED', 'SUBMITTED', 'WON', 'LOST', 'EXPIRED', 'WITHDRAWN')),
    CONSTRAINT ck_disputes_stage CHECK (stage IS NULL OR stage IN (
        'RETRIEVAL', 'CHARGEBACK', 'PRE_ARBITRATION', 'ARBITRATION')),
    CONSTRAINT ck_disputes_outcome CHECK (outcome IS NULL OR outcome IN (
        'WON', 'LOST', 'WITHDRAWN', 'EXPIRED')),
    CONSTRAINT ck_disputes_source CHECK (source IN (
        'PSP_ADAPTER', 'ORDER_SYSTEM', 'LOGISTICS', 'CRM', 'SIMULATOR',
        'INTERNAL', 'MERCHANT_PORTAL')),
    CONSTRAINT ck_disputes_amount CHECK (amount_minor >= 0)
);

CREATE INDEX ix_disputes_merchant        ON pdei.disputes (merchant_id);
CREATE INDEX ix_disputes_transaction     ON pdei.disputes (transaction_id);
CREATE INDEX ix_disputes_status          ON pdei.disputes (status);
CREATE INDEX ix_disputes_reason_code     ON pdei.disputes (reason_code);
CREATE INDEX ix_disputes_merchant_status ON pdei.disputes (merchant_id, status);
CREATE INDEX ix_disputes_deadline_at     ON pdei.disputes (deadline_at);
CREATE INDEX ix_disputes_opened_at       ON pdei.disputes (opened_at DESC);

CREATE UNIQUE INDEX ux_disputes_psp_ref ON pdei.disputes (merchant_id, psp_dispute_ref)
    WHERE psp_dispute_ref IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- dispute_cases  (id prefix CASE-)  -- Temporal workflow id = case-{caseId}
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.dispute_cases (
    case_id             VARCHAR(64) NOT NULL,
    dispute_id          VARCHAR(64) NOT NULL,
    merchant_id         VARCHAR(64) NOT NULL,
    transaction_id      VARCHAR(64) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    amount_minor        BIGINT      NOT NULL,
    currency            CHAR(3)     NOT NULL,

    workflow_id         VARCHAR(128),
    run_id              VARCHAR(128),
    task_queue          VARCHAR(64) NOT NULL DEFAULT 'pdei-dispute-cases',

    assigned_to         VARCHAR(128),
    readiness_score     INTEGER,
    readiness_band      VARCHAR(32),
    recommended_action  VARCHAR(48),
    safety_decision     VARCHAR(32),
    progress_percent    INTEGER     NOT NULL DEFAULT 0,

    opened_at           TIMESTAMPTZ NOT NULL,
    deadline_at         TIMESTAMPTZ,
    prepared_at         TIMESTAMPTZ,
    submitted_at        TIMESTAMPTZ,
    closed_at           TIMESTAMPTZ,

    package_object_key  VARCHAR(512),
    package_version     INTEGER,
    package_manifest    JSONB,

    approval_actor      VARCHAR(128),
    approval_at         TIMESTAMPTZ,
    approval_notes      TEXT,

    failure_reason      VARCHAR(512),
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_dispute_cases PRIMARY KEY (case_id),
    CONSTRAINT fk_dispute_cases_dispute     FOREIGN KEY (dispute_id)     REFERENCES pdei.disputes (dispute_id),
    CONSTRAINT fk_dispute_cases_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT fk_dispute_cases_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT ck_dispute_cases_id_prefix CHECK (case_id LIKE 'CASE-%'),
    CONSTRAINT ck_dispute_cases_status CHECK (status IN (
        'CREATED', 'ASSEMBLING', 'INVESTIGATING', 'AWAITING_EVIDENCE',
        'AWAITING_APPROVAL', 'PREPARED', 'SUBMITTED', 'CLOSED', 'FAILED')),
    CONSTRAINT ck_dispute_cases_band CHECK (readiness_band IS NULL OR readiness_band IN (
        'READY', 'NEARLY_READY', 'AT_RISK', 'NOT_READY')),
    CONSTRAINT ck_dispute_cases_action CHECK (recommended_action IS NULL OR recommended_action IN (
        'PREPARE_REPRESENTMENT', 'GATHER_MORE_EVIDENCE', 'ACCEPT_LIABILITY',
        'ESCALATE_TO_HUMAN', 'REQUEST_POLICY_REVIEW')),
    CONSTRAINT ck_dispute_cases_safety CHECK (safety_decision IS NULL OR safety_decision IN (
        'ALLOW', 'ALLOW_WITH_REVIEW', 'DENY')),
    CONSTRAINT ck_dispute_cases_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_dispute_cases_score CHECK (readiness_score IS NULL
                                             OR readiness_score BETWEEN 0 AND 100)
);

CREATE INDEX ix_dispute_cases_dispute        ON pdei.dispute_cases (dispute_id);
CREATE INDEX ix_dispute_cases_merchant       ON pdei.dispute_cases (merchant_id);
CREATE INDEX ix_dispute_cases_status         ON pdei.dispute_cases (status);
CREATE INDEX ix_dispute_cases_merchant_stat  ON pdei.dispute_cases (merchant_id, status);
CREATE INDEX ix_dispute_cases_transaction    ON pdei.dispute_cases (transaction_id);
CREATE INDEX ix_dispute_cases_deadline_at    ON pdei.dispute_cases (deadline_at);
CREATE INDEX ix_dispute_cases_merchant_band  ON pdei.dispute_cases (merchant_id, readiness_band);
CREATE UNIQUE INDEX ux_dispute_cases_workflow ON pdei.dispute_cases (workflow_id)
    WHERE workflow_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- case_evidence  |  evidence attached to a case, version-pinned
-- id convention: opaque UUID string
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.case_evidence (
    case_evidence_id    VARCHAR(64) NOT NULL,
    case_id             VARCHAR(64) NOT NULL,
    evidence_id         VARCHAR(64) NOT NULL,
    evidence_version    INTEGER     NOT NULL,
    role                VARCHAR(32) NOT NULL DEFAULT 'SUPPORTING',
    -- hash observed when the item was attached: divergence later == tampering
    sha256_at_attach    VARCHAR(64),
    display_order       INTEGER     NOT NULL DEFAULT 0,
    included_in_package BOOLEAN     NOT NULL DEFAULT TRUE,
    attached_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    attached_by         VARCHAR(128),
    notes               TEXT,
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_case_evidence PRIMARY KEY (case_evidence_id),
    CONSTRAINT uq_case_evidence UNIQUE (case_id, evidence_id),
    CONSTRAINT fk_case_evidence_case     FOREIGN KEY (case_id)     REFERENCES pdei.dispute_cases (case_id),
    CONSTRAINT fk_case_evidence_evidence FOREIGN KEY (evidence_id) REFERENCES pdei.evidence (evidence_id),
    CONSTRAINT ck_case_evidence_role CHECK (role IN (
        'PRIMARY', 'SUPPORTING', 'CONTEXT', 'REBUTTAL', 'EXCLUDED')),
    CONSTRAINT ck_case_evidence_version CHECK (evidence_version >= 1)
);

CREATE INDEX ix_case_evidence_case     ON pdei.case_evidence (case_id, display_order);
CREATE INDEX ix_case_evidence_evidence ON pdei.case_evidence (evidence_id);
