-- =====================================================================================
-- V9__simulation.sql  |  simulation_runs, chaos_injections
--
-- The simulator owns a deterministic synthetic world: (seed, parameters) fully determine
-- the generated data, so benchmarks are reproducible. Chaos injections are recorded so a
-- demo can show exactly which failure was injected, when, and what the platform did.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- simulation_runs  (id prefix SIM-)
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.simulation_runs (
    run_id               VARCHAR(64) NOT NULL,
    seed                 BIGINT      NOT NULL,
    merchant_count       INTEGER     NOT NULL DEFAULT 0,
    transaction_count    INTEGER     NOT NULL DEFAULT 0,
    days                 INTEGER     NOT NULL DEFAULT 1,
    -- dispute rate in basis points (250 = 2.50%); integer, never a float
    dispute_rate_bps     INTEGER     NOT NULL DEFAULT 0,
    failure_profile      VARCHAR(64),
    scenario_key         VARCHAR(64),
    status               VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    progress_percent     INTEGER     NOT NULL DEFAULT 0,

    events_emitted       BIGINT      NOT NULL DEFAULT 0,
    transactions_created BIGINT      NOT NULL DEFAULT 0,
    evidence_created     BIGINT      NOT NULL DEFAULT 0,
    disputes_created     BIGINT      NOT NULL DEFAULT 0,

    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    requested_by         VARCHAR(128),
    error_message        VARCHAR(1024),
    params               JSONB,
    stats                JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    version              BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_simulation_runs PRIMARY KEY (run_id),
    CONSTRAINT ck_simulation_runs_id_prefix CHECK (run_id LIKE 'SIM-%'),
    CONSTRAINT ck_simulation_runs_status CHECK (status IN (
        'PENDING', 'RUNNING', 'STOPPING', 'STOPPED', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_simulation_runs_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_simulation_runs_rate CHECK (dispute_rate_bps BETWEEN 0 AND 10000)
);

CREATE INDEX ix_simulation_runs_status  ON pdei.simulation_runs (status);
CREATE INDEX ix_simulation_runs_created ON pdei.simulation_runs (created_at DESC);
CREATE INDEX ix_simulation_runs_seed    ON pdei.simulation_runs (seed);

-- -------------------------------------------------------------------------------------
-- chaos_injections  |  id convention: opaque UUID string
-- -------------------------------------------------------------------------------------
CREATE TABLE pdei.chaos_injections (
    injection_id  VARCHAR(64) NOT NULL,
    run_id        VARCHAR(64),
    merchant_id   VARCHAR(64),
    type          VARCHAR(32) NOT NULL,
    status        VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    target        JSONB,
    delay_ms      BIGINT,
    event_count   INTEGER,
    actor         VARCHAR(128),
    injected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ,
    result        JSONB,
    error_message VARCHAR(1024),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_chaos_injections PRIMARY KEY (injection_id),
    CONSTRAINT fk_chaos_injections_run      FOREIGN KEY (run_id)      REFERENCES pdei.simulation_runs (run_id),
    CONSTRAINT fk_chaos_injections_merchant FOREIGN KEY (merchant_id) REFERENCES pdei.merchants (merchant_id),
    CONSTRAINT ck_chaos_injections_type CHECK (type IN (
        'DUPLICATE_EVENT', 'DELAYED_EVENT', 'OUT_OF_ORDER_EVENT', 'DROP_EVENT',
        'DELETE_EVIDENCE', 'CORRUPT_EVIDENCE_HASH', 'EXPIRE_EVIDENCE',
        'CONFLICTING_EVIDENCE', 'KILL_WORKER', 'RESTART_CONSUMER', 'REPLAY_EVENTS',
        'INJECT_DISPUTE', 'SLOW_CONSUMER')),
    CONSTRAINT ck_chaos_injections_status CHECK (status IN (
        'REQUESTED', 'APPLIED', 'FAILED', 'REVERTED')),
    CONSTRAINT ck_chaos_injections_delay CHECK (delay_ms IS NULL OR delay_ms >= 0),
    CONSTRAINT ck_chaos_injections_count CHECK (event_count IS NULL OR event_count >= 0)
);

CREATE INDEX ix_chaos_injections_run      ON pdei.chaos_injections (run_id, injected_at DESC);
CREATE INDEX ix_chaos_injections_type     ON pdei.chaos_injections (type, injected_at DESC);
CREATE INDEX ix_chaos_injections_merchant ON pdei.chaos_injections (merchant_id);
CREATE INDEX ix_chaos_injections_injected ON pdei.chaos_injections (injected_at DESC);
