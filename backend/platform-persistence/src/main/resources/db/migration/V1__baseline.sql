-- =====================================================================================
-- V1__baseline.sql  |  PDEI schema baseline: merchants, customers, processed_events
-- Contract: docs/PLATFORM-CONTRACT.md section 5.
--   * every primary key is a human-readable prefixed VARCHAR(64) id
--   * every timestamp is TIMESTAMPTZ stored in UTC
--   * every monetary value is (amount_minor BIGINT, currency CHAR(3))
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS pdei;

-- -------------------------------------------------------------------------------------
-- merchants  (id prefix MER-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.merchants (
    merchant_id             VARCHAR(64)  NOT NULL,
    legal_name              VARCHAR(256) NOT NULL,
    display_name            VARCHAR(256),
    country                 VARCHAR(2)   NOT NULL DEFAULT 'IN',
    default_currency        CHAR(3)      NOT NULL,
    mcc                     VARCHAR(8),
    status                  VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    timezone                VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    contact_email           VARCHAR(256),
    -- historical representment win rate in basis points (0..10000). Integer on purpose:
    -- no floating point anywhere in the operational store.
    baseline_win_rate_bps   INTEGER,
    onboarded_at            TIMESTAMPTZ,
    risk_profile            JSONB,
    metadata                JSONB,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_merchants PRIMARY KEY (merchant_id),
    CONSTRAINT ck_merchants_id_prefix CHECK (merchant_id LIKE 'MER-%'),
    CONSTRAINT ck_merchants_status    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_merchants_win_rate  CHECK (baseline_win_rate_bps IS NULL
                                             OR baseline_win_rate_bps BETWEEN 0 AND 10000)
);

CREATE INDEX ix_merchants_status  ON pdei.merchants (status);
CREATE INDEX ix_merchants_country ON pdei.merchants (country);

COMMENT ON TABLE  pdei.merchants                       IS 'Merchant tenant root. Owner of every transaction, evidence item and dispute.';
COMMENT ON COLUMN pdei.merchants.baseline_win_rate_bps IS 'Historical representment win rate in basis points, feeds InvestigationContext.historicalContext.merchantWinRate.';

-- -------------------------------------------------------------------------------------
-- customers  (id prefix CUS-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.customers (
    customer_id   VARCHAR(64)  NOT NULL,
    merchant_id   VARCHAR(64)  NOT NULL,
    external_ref  VARCHAR(128),
    display_name  VARCHAR(256),
    email         VARCHAR(256),
    phone         VARCHAR(64),
    country       VARCHAR(2),
    first_seen_at TIMESTAMPTZ,
    last_seen_at  TIMESTAMPTZ,
    risk_flags    JSONB,
    metadata      JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_customers PRIMARY KEY (customer_id),
    CONSTRAINT fk_customers_merchant FOREIGN KEY (merchant_id)
        REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT ck_customers_id_prefix CHECK (customer_id LIKE 'CUS-%')
);

CREATE INDEX ix_customers_merchant       ON pdei.customers (merchant_id);
CREATE INDEX ix_customers_merchant_email ON pdei.customers (merchant_id, email);
CREATE INDEX ix_customers_external_ref   ON pdei.customers (merchant_id, external_ref);

-- -------------------------------------------------------------------------------------
-- processed_events  |  canonical Postgres-side idempotency primitive.
-- Every Kafka consumer calls ProcessedEventRepository.markProcessed(eventId, consumerGroup)
-- which performs INSERT ... ON CONFLICT DO NOTHING against this composite primary key.
-- Redis SETNX is the fast path; this table is the durable one.
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.processed_events (
    event_id       VARCHAR(64)  NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer_group)
);

-- Supports retention pruning (delete rows older than the event dedupe window).
CREATE INDEX ix_processed_events_processed_at   ON pdei.processed_events (processed_at);
CREATE INDEX ix_processed_events_consumer_group ON pdei.processed_events (consumer_group, processed_at);

COMMENT ON TABLE pdei.processed_events IS 'Durable consumer-side dedupe ledger: (eventId, consumerGroup) seen-once marker.';
