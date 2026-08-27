-- =====================================================================================
-- V2__transactions.sql  |  the financial spine
--   transactions, payments, orders, order_lines, refunds, shipments, deliveries,
--   communications
-- Money is ALWAYS (amount_minor BIGINT, currency CHAR(3)). Never NUMERIC/FLOAT.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- transactions  (id prefix TX-)  -- the readiness unit of work
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.transactions (
    transaction_id          VARCHAR(64) NOT NULL,
    merchant_id             VARCHAR(64) NOT NULL,
    customer_id             VARCHAR(64),
    external_ref            VARCHAR(128),

    amount_minor            BIGINT      NOT NULL,
    currency                CHAR(3)     NOT NULL,
    captured_amount_minor   BIGINT      NOT NULL DEFAULT 0,
    captured_currency       CHAR(3)     NOT NULL,
    refunded_amount_minor   BIGINT      NOT NULL DEFAULT 0,
    refunded_currency       CHAR(3)     NOT NULL,

    status                  VARCHAR(32) NOT NULL,
    channel                 VARCHAR(32),
    occurred_at             TIMESTAMPTZ NOT NULL,
    observed_at             TIMESTAMPTZ NOT NULL,

    -- denormalised readiness projection maintained by readiness-worker; the authoritative
    -- history lives in pdei.readiness_snapshots.
    readiness_score         INTEGER,
    readiness_band          VARCHAR(32),
    readiness_computed_at   TIMESTAMPTZ,

    last_event_id           VARCHAR(64),
    last_event_at           TIMESTAMPTZ,
    metadata                JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_transactions PRIMARY KEY (transaction_id),
    CONSTRAINT fk_transactions_merchant FOREIGN KEY (merchant_id) REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT fk_transactions_customer FOREIGN KEY (customer_id) REFERENCES pdei.customers (customer_id),
    CONSTRAINT ck_transactions_id_prefix CHECK (transaction_id LIKE 'TX-%'),
    CONSTRAINT ck_transactions_status CHECK (status IN (
        'CREATED', 'AUTHORIZED', 'CAPTURED', 'SETTLED',
        'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED', 'CHARGEBACK')),
    CONSTRAINT ck_transactions_channel CHECK (channel IS NULL OR channel IN (
        'ONLINE', 'POS', 'MOTO', 'RECURRING', 'IN_APP')),
    CONSTRAINT ck_transactions_readiness_band CHECK (readiness_band IS NULL OR readiness_band IN (
        'READY', 'NEARLY_READY', 'AT_RISK', 'NOT_READY')),
    CONSTRAINT ck_transactions_readiness_score CHECK (readiness_score IS NULL
                                                      OR readiness_score BETWEEN 0 AND 100)
);

CREATE INDEX ix_transactions_merchant      ON pdei.transactions (merchant_id);
CREATE INDEX ix_transactions_customer      ON pdei.transactions (customer_id);
CREATE INDEX ix_transactions_status        ON pdei.transactions (status);
CREATE INDEX ix_transactions_occurred_at   ON pdei.transactions (occurred_at);
CREATE INDEX ix_transactions_merchant_band ON pdei.transactions (merchant_id, readiness_band);
CREATE INDEX ix_transactions_merchant_time ON pdei.transactions (merchant_id, occurred_at DESC);

COMMENT ON COLUMN pdei.transactions.readiness_band IS 'ReadinessBand projection: READY|NEARLY_READY|AT_RISK|NOT_READY.';

-- -------------------------------------------------------------------------------------
-- payments  (id prefix PAY-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.payments (
    payment_id          VARCHAR(64) NOT NULL,
    transaction_id      VARCHAR(64) NOT NULL,
    merchant_id         VARCHAR(64) NOT NULL,
    psp                 VARCHAR(64),
    psp_reference       VARCHAR(128),
    method              VARCHAR(32),
    card_brand          VARCHAR(32),
    card_bin            VARCHAR(8),
    card_last4          VARCHAR(4),
    amount_minor        BIGINT      NOT NULL,
    currency            CHAR(3)     NOT NULL,
    status              VARCHAR(32) NOT NULL,
    avs_result          VARCHAR(16),
    cvv_result          VARCHAR(16),
    three_ds_result     VARCHAR(32),
    device_fingerprint  VARCHAR(128),
    ip_address          VARCHAR(64),
    occurred_at         TIMESTAMPTZ NOT NULL,
    authorized_at       TIMESTAMPTZ,
    captured_at         TIMESTAMPTZ,
    failed_at           TIMESTAMPTZ,
    failure_code        VARCHAR(64),
    failure_message     VARCHAR(512),
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_payments PRIMARY KEY (payment_id),
    CONSTRAINT fk_payments_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_payments_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT ck_payments_id_prefix CHECK (payment_id LIKE 'PAY-%'),
    CONSTRAINT ck_payments_status CHECK (status IN (
        'CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'VOIDED')),
    CONSTRAINT ck_payments_method CHECK (method IS NULL OR method IN (
        'CARD', 'UPI', 'NETBANKING', 'WALLET', 'BNPL', 'BANK_TRANSFER'))
);

CREATE INDEX ix_payments_transaction ON pdei.payments (transaction_id);
CREATE INDEX ix_payments_merchant    ON pdei.payments (merchant_id);
CREATE INDEX ix_payments_status      ON pdei.payments (status);
CREATE INDEX ix_payments_occurred_at ON pdei.payments (occurred_at);
CREATE INDEX ix_payments_psp_ref     ON pdei.payments (psp, psp_reference);

-- -------------------------------------------------------------------------------------
-- orders  (id prefix ORD-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.orders (
    order_id                VARCHAR(64) NOT NULL,
    transaction_id          VARCHAR(64),
    merchant_id             VARCHAR(64) NOT NULL,
    customer_id             VARCHAR(64),
    external_ref            VARCHAR(128),
    amount_minor            BIGINT      NOT NULL,
    currency                CHAR(3)     NOT NULL,
    tax_amount_minor        BIGINT      NOT NULL DEFAULT 0,
    tax_currency            CHAR(3)     NOT NULL,
    shipping_amount_minor   BIGINT      NOT NULL DEFAULT 0,
    shipping_currency       CHAR(3)     NOT NULL,
    status                  VARCHAR(32) NOT NULL,
    placed_at               TIMESTAMPTZ NOT NULL,
    fulfilled_at            TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    shipping_address        JSONB,
    billing_address         JSONB,
    metadata                JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                 BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_orders PRIMARY KEY (order_id),
    CONSTRAINT fk_orders_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_orders_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT fk_orders_customer    FOREIGN KEY (customer_id)    REFERENCES pdei.customers (customer_id),
    CONSTRAINT ck_orders_id_prefix CHECK (order_id LIKE 'ORD-%'),
    CONSTRAINT ck_orders_status CHECK (status IN (
        'CREATED', 'CONFIRMED', 'PARTIALLY_FULFILLED', 'FULFILLED', 'CANCELLED'))
);

CREATE INDEX ix_orders_transaction ON pdei.orders (transaction_id);
CREATE INDEX ix_orders_merchant    ON pdei.orders (merchant_id);
CREATE INDEX ix_orders_status      ON pdei.orders (status);
CREATE INDEX ix_orders_placed_at   ON pdei.orders (placed_at);

-- -------------------------------------------------------------------------------------
-- order_lines  |  id convention: {orderId}-L{lineNumber} (no dedicated contract prefix)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.order_lines (
    order_line_id           VARCHAR(64)  NOT NULL,
    order_id                VARCHAR(64)  NOT NULL,
    line_number             INTEGER      NOT NULL,
    sku                     VARCHAR(128),
    description             VARCHAR(512),
    quantity                INTEGER      NOT NULL DEFAULT 1,
    unit_price_amount_minor BIGINT       NOT NULL,
    unit_price_currency     CHAR(3)      NOT NULL,
    line_total_amount_minor BIGINT       NOT NULL,
    line_total_currency     CHAR(3)      NOT NULL,
    digital_good            BOOLEAN      NOT NULL DEFAULT FALSE,
    metadata                JSONB,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_order_lines PRIMARY KEY (order_line_id),
    CONSTRAINT uq_order_lines_order_line UNIQUE (order_id, line_number),
    CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES pdei.orders (order_id),
    CONSTRAINT ck_order_lines_quantity CHECK (quantity > 0)
);

CREATE INDEX ix_order_lines_order ON pdei.order_lines (order_id, line_number);
CREATE INDEX ix_order_lines_sku   ON pdei.order_lines (sku);

-- -------------------------------------------------------------------------------------
-- refunds  (id prefix REF-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.refunds (
    refund_id       VARCHAR(64) NOT NULL,
    transaction_id  VARCHAR(64) NOT NULL,
    payment_id      VARCHAR(64),
    merchant_id     VARCHAR(64) NOT NULL,
    amount_minor    BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,
    reason          VARCHAR(256),
    status          VARCHAR(32) NOT NULL,
    psp_reference   VARCHAR(128),
    requested_at    TIMESTAMPTZ NOT NULL,
    processed_at    TIMESTAMPTZ,
    failure_message VARCHAR(512),
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_refunds PRIMARY KEY (refund_id),
    CONSTRAINT fk_refunds_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_refunds_payment     FOREIGN KEY (payment_id)     REFERENCES pdei.payments (payment_id),
    CONSTRAINT fk_refunds_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT ck_refunds_id_prefix CHECK (refund_id LIKE 'REF-%'),
    CONSTRAINT ck_refunds_status CHECK (status IN ('CREATED', 'PROCESSING', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_refunds_amount CHECK (amount_minor >= 0)
);

CREATE INDEX ix_refunds_transaction  ON pdei.refunds (transaction_id);
CREATE INDEX ix_refunds_merchant     ON pdei.refunds (merchant_id);
CREATE INDEX ix_refunds_status       ON pdei.refunds (status);
CREATE INDEX ix_refunds_processed_at ON pdei.refunds (processed_at);

-- -------------------------------------------------------------------------------------
-- shipments  (id prefix SHP-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.shipments (
    shipment_id                  VARCHAR(64) NOT NULL,
    transaction_id               VARCHAR(64),
    order_id                     VARCHAR(64),
    merchant_id                  VARCHAR(64) NOT NULL,
    carrier                      VARCHAR(64),
    tracking_number              VARCHAR(128),
    service_level                VARCHAR(64),
    status                       VARCHAR(32) NOT NULL,
    declared_value_amount_minor  BIGINT      NOT NULL DEFAULT 0,
    declared_value_currency      CHAR(3)     NOT NULL,
    shipped_at                   TIMESTAMPTZ,
    estimated_delivery_at        TIMESTAMPTZ,
    destination_address          JSONB,
    metadata                     JSONB,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                      BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_shipments PRIMARY KEY (shipment_id),
    CONSTRAINT fk_shipments_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_shipments_order       FOREIGN KEY (order_id)       REFERENCES pdei.orders (order_id),
    CONSTRAINT fk_shipments_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT ck_shipments_id_prefix CHECK (shipment_id LIKE 'SHP-%'),
    CONSTRAINT ck_shipments_status CHECK (status IN (
        'CREATED', 'DISPATCHED', 'IN_TRANSIT', 'DELIVERED', 'RETURNED', 'LOST'))
);

CREATE INDEX ix_shipments_transaction ON pdei.shipments (transaction_id);
CREATE INDEX ix_shipments_order       ON pdei.shipments (order_id);
CREATE INDEX ix_shipments_merchant    ON pdei.shipments (merchant_id);
CREATE INDEX ix_shipments_status      ON pdei.shipments (status);
CREATE INDEX ix_shipments_tracking    ON pdei.shipments (carrier, tracking_number);

-- -------------------------------------------------------------------------------------
-- deliveries  (id prefix DLV-)
-- Geo coordinates are stored as integer micro-degrees: this database contains no
-- floating point columns at all, so nothing can accidentally be reused for money.
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.deliveries (
    delivery_id         VARCHAR(64) NOT NULL,
    shipment_id         VARCHAR(64) NOT NULL,
    transaction_id      VARCHAR(64),
    merchant_id         VARCHAR(64) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    delivered_at        TIMESTAMPTZ,
    attempts            INTEGER     NOT NULL DEFAULT 0,
    recipient_name      VARCHAR(256),
    signed_by           VARCHAR(256),
    signature_captured  BOOLEAN     NOT NULL DEFAULT FALSE,
    proof_object_key    VARCHAR(512),
    geo_lat_micro       INTEGER,
    geo_lon_micro       INTEGER,
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_deliveries PRIMARY KEY (delivery_id),
    CONSTRAINT fk_deliveries_shipment    FOREIGN KEY (shipment_id)    REFERENCES pdei.shipments (shipment_id),
    CONSTRAINT fk_deliveries_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_deliveries_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT ck_deliveries_id_prefix CHECK (delivery_id LIKE 'DLV-%'),
    CONSTRAINT ck_deliveries_status CHECK (status IN (
        'PENDING', 'ATTEMPTED', 'DELIVERED', 'REFUSED', 'FAILED')),
    CONSTRAINT ck_deliveries_geo CHECK (
        (geo_lat_micro IS NULL OR geo_lat_micro BETWEEN -90000000 AND 90000000) AND
        (geo_lon_micro IS NULL OR geo_lon_micro BETWEEN -180000000 AND 180000000))
);

CREATE INDEX ix_deliveries_shipment     ON pdei.deliveries (shipment_id);
CREATE INDEX ix_deliveries_transaction  ON pdei.deliveries (transaction_id);
CREATE INDEX ix_deliveries_merchant     ON pdei.deliveries (merchant_id);
CREATE INDEX ix_deliveries_status       ON pdei.deliveries (status);
CREATE INDEX ix_deliveries_delivered_at ON pdei.deliveries (delivered_at);

-- -------------------------------------------------------------------------------------
-- communications  (id prefix COM-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.communications (
    communication_id VARCHAR(64) NOT NULL,
    transaction_id   VARCHAR(64),
    merchant_id      VARCHAR(64) NOT NULL,
    customer_id      VARCHAR(64),
    channel          VARCHAR(32) NOT NULL,
    direction        VARCHAR(16) NOT NULL,
    subject          VARCHAR(512),
    body             TEXT,
    sender           VARCHAR(256),
    recipient        VARCHAR(256),
    occurred_at      TIMESTAMPTZ NOT NULL,
    object_key       VARCHAR(512),
    sha256           VARCHAR(64),
    metadata         JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_communications PRIMARY KEY (communication_id),
    CONSTRAINT fk_communications_transaction FOREIGN KEY (transaction_id) REFERENCES pdei.transactions (transaction_id),
    CONSTRAINT fk_communications_merchant    FOREIGN KEY (merchant_id)    REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT fk_communications_customer    FOREIGN KEY (customer_id)    REFERENCES pdei.customers (customer_id),
    CONSTRAINT ck_communications_id_prefix CHECK (communication_id LIKE 'COM-%'),
    CONSTRAINT ck_communications_channel CHECK (channel IN (
        'EMAIL', 'SMS', 'CHAT', 'PHONE', 'PORTAL', 'WHATSAPP')),
    CONSTRAINT ck_communications_direction CHECK (direction IN ('INBOUND', 'OUTBOUND'))
);

CREATE INDEX ix_communications_transaction ON pdei.communications (transaction_id, occurred_at);
CREATE INDEX ix_communications_merchant    ON pdei.communications (merchant_id, occurred_at DESC);
CREATE INDEX ix_communications_customer    ON pdei.communications (customer_id);
CREATE INDEX ix_communications_occurred_at ON pdei.communications (occurred_at);
