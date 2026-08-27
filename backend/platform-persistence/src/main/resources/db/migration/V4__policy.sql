-- =====================================================================================
-- V4__policy.sql  |  policies, policy_versions, evidence_requirements
--
-- The policy engine is deterministic and versioned: a policy row is the current head,
-- policy_versions is the immutable history, evidence_requirements is the requirement
-- matrix that the readiness engine scores against.
--
-- Confidence thresholds are stored in BASIS POINTS (integer). The AI contract exposes
-- them as doubles (0.90); conversion happens at the service boundary, never in storage.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- policies  (id prefix POL-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.policies (
    policy_id                       VARCHAR(64)  NOT NULL,
    merchant_id                     VARCHAR(64)  NOT NULL,
    name                            VARCHAR(256) NOT NULL,
    description                     TEXT,
    scope                           VARCHAR(32)  NOT NULL DEFAULT 'MERCHANT',
    reason_code                     VARCHAR(48),
    current_version                 INTEGER      NOT NULL DEFAULT 1,
    active                          BOOLEAN      NOT NULL DEFAULT TRUE,
    effective_from                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    effective_to                    TIMESTAMPTZ,

    auto_prepare_min_confidence_bps INTEGER      NOT NULL DEFAULT 9000,
    max_contradictions              INTEGER      NOT NULL DEFAULT 0,
    prohibited_evidence_types       JSONB,
    permitted_actions               JSONB,
    evidence_ttl_days               INTEGER,

    metadata                        JSONB,
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_policies PRIMARY KEY (policy_id),
    CONSTRAINT fk_policies_merchant FOREIGN KEY (merchant_id) REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT ck_policies_id_prefix CHECK (policy_id LIKE 'POL-%'),
    CONSTRAINT ck_policies_scope CHECK (scope IN ('GLOBAL', 'MERCHANT', 'REASON_CODE')),
    CONSTRAINT ck_policies_reason_code CHECK (reason_code IS NULL OR reason_code IN (
        'GOODS_NOT_RECEIVED', 'SERVICE_NOT_RENDERED', 'PRODUCT_NOT_AS_DESCRIBED',
        'DUPLICATE_PROCESSING', 'CREDIT_NOT_PROCESSED', 'SUBSCRIPTION_CANCELLED',
        'FRAUDULENT_TRANSACTION', 'UNRECOGNIZED_TRANSACTION',
        'INCORRECT_AMOUNT', 'PAID_BY_OTHER_MEANS')),
    CONSTRAINT ck_policies_confidence CHECK (auto_prepare_min_confidence_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_policies_max_contradictions CHECK (max_contradictions >= 0),
    CONSTRAINT ck_policies_version CHECK (current_version >= 1)
);

CREATE INDEX ix_policies_merchant        ON pdei.policies (merchant_id);
CREATE INDEX ix_policies_merchant_reason ON pdei.policies (merchant_id, reason_code, active);
CREATE INDEX ix_policies_active          ON pdei.policies (active);

COMMENT ON COLUMN pdei.policies.auto_prepare_min_confidence_bps IS
    'policyConstraints.autoPrepareMinConfidence in basis points (9000 = 0.90). Integer by contract rule 4.';

-- -------------------------------------------------------------------------------------
-- policy_versions  |  immutable history; id convention {policyId}-v{versionNumber}
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.policy_versions (
    policy_version_id               VARCHAR(64) NOT NULL,
    policy_id                       VARCHAR(64) NOT NULL,
    version_number                  INTEGER     NOT NULL,
    parent_version                  INTEGER,
    document                        JSONB       NOT NULL,
    auto_prepare_min_confidence_bps INTEGER     NOT NULL,
    max_contradictions              INTEGER     NOT NULL,
    prohibited_evidence_types       JSONB,
    permitted_actions               JSONB,
    sha256                          VARCHAR(64) NOT NULL,
    change_reason                   VARCHAR(512),
    created_by                      VARCHAR(128),
    effective_from                  TIMESTAMPTZ NOT NULL,
    effective_to                    TIMESTAMPTZ,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_policy_versions PRIMARY KEY (policy_version_id),
    CONSTRAINT uq_policy_versions UNIQUE (policy_id, version_number),
    CONSTRAINT fk_policy_versions_policy FOREIGN KEY (policy_id)
        REFERENCES pdei.policies (policy_id) ON DELETE RESTRICT,
    CONSTRAINT ck_policy_versions_number CHECK (version_number >= 1),
    CONSTRAINT ck_policy_versions_parent CHECK (parent_version IS NULL
                                                OR parent_version < version_number),
    CONSTRAINT ck_policy_versions_confidence CHECK (auto_prepare_min_confidence_bps BETWEEN 0 AND 10000)
);

CREATE INDEX ix_policy_versions_policy    ON pdei.policy_versions (policy_id, version_number DESC);
CREATE INDEX ix_policy_versions_effective ON pdei.policy_versions (effective_from, effective_to);

-- -------------------------------------------------------------------------------------
-- evidence_requirements  |  requirement matrix (per policy version, per reason code)
-- id convention: opaque UUID string
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.evidence_requirements (
    requirement_id VARCHAR(64) NOT NULL,
    policy_id      VARCHAR(64) NOT NULL,
    policy_version INTEGER     NOT NULL,
    merchant_id    VARCHAR(64) NOT NULL,
    reason_code    VARCHAR(48),
    evidence_type  VARCHAR(48) NOT NULL,
    strength       VARCHAR(16) NOT NULL,
    -- RequirementStrength.weight(): MANDATORY=3, RECOMMENDED=2, OPTIONAL=1, PROHIBITED=0
    weight         INTEGER     NOT NULL DEFAULT 1,
    max_age_days   INTEGER,
    description    TEXT,
    metadata       JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    version        BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_evidence_requirements PRIMARY KEY (requirement_id),
    CONSTRAINT uq_evidence_requirements UNIQUE (policy_id, policy_version, reason_code, evidence_type),
    CONSTRAINT fk_evidence_requirements_policy   FOREIGN KEY (policy_id)   REFERENCES pdei.policies (policy_id),
    CONSTRAINT fk_evidence_requirements_merchant FOREIGN KEY (merchant_id) REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT ck_evidence_requirements_reason CHECK (reason_code IS NULL OR reason_code IN (
        'GOODS_NOT_RECEIVED', 'SERVICE_NOT_RENDERED', 'PRODUCT_NOT_AS_DESCRIBED',
        'DUPLICATE_PROCESSING', 'CREDIT_NOT_PROCESSED', 'SUBSCRIPTION_CANCELLED',
        'FRAUDULENT_TRANSACTION', 'UNRECOGNIZED_TRANSACTION',
        'INCORRECT_AMOUNT', 'PAID_BY_OTHER_MEANS')),
    CONSTRAINT ck_evidence_requirements_type CHECK (evidence_type IN (
        'PAYMENT_PROOF', 'INVOICE', 'ORDER_RECORD', 'SHIPPING_RECORD',
        'DELIVERY_PROOF', 'REFUND_RECEIPT', 'CUSTOMER_COMMUNICATION',
        'MERCHANT_POLICY', 'TERMS_OF_SERVICE', 'AVS_CVV_RESULT',
        'DEVICE_FINGERPRINT', 'PRIOR_TRANSACTION_HISTORY', 'SIGNED_CONTRACT')),
    CONSTRAINT ck_evidence_requirements_strength CHECK (strength IN (
        'MANDATORY', 'RECOMMENDED', 'OPTIONAL', 'PROHIBITED')),
    CONSTRAINT ck_evidence_requirements_weight CHECK (weight BETWEEN 0 AND 100)
);

CREATE INDEX ix_evidence_requirements_reason          ON pdei.evidence_requirements (reason_code);
CREATE INDEX ix_evidence_requirements_merchant_reason ON pdei.evidence_requirements (merchant_id, reason_code);
CREATE INDEX ix_evidence_requirements_policy          ON pdei.evidence_requirements (policy_id, policy_version);
CREATE INDEX ix_evidence_requirements_strength        ON pdei.evidence_requirements (strength);
