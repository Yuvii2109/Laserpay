-- PDEI - PostgreSQL bootstrap.
--
-- Runs ONCE, on first boot of an empty pdei-postgres-data volume, via the
-- postgres image's /docker-entrypoint-initdb.d hook. It runs as the superuser
-- created by POSTGRES_USER against the database created by POSTGRES_DB (both "pdei").
--
-- SCOPE BOUNDARY (important):
--   This file creates the database, the roles, the schema and the extensions.
--   It does NOT create tables. Every table, index and constraint belongs to Flyway,
--   owned by backend/platform-persistence/src/main/resources/db/migration (contract
--   section 5). If you find yourself adding a CREATE TABLE here, it belongs in a V*.sql
--   migration instead - otherwise the schema has two owners and drifts.
--
-- Idempotent throughout: safe to re-run by hand against a live database.

\set ON_ERROR_STOP on

-- ---------------------------------------------------------------------------
-- 1. Database
--    POSTGRES_DB already created "pdei"; this block only matters when the file is
--    replayed by hand against a cluster where it is missing.
-- ---------------------------------------------------------------------------
SELECT 'CREATE DATABASE pdei'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'pdei')\gexec

\connect pdei

-- ---------------------------------------------------------------------------
-- 2. Roles
--    pdei          - owner/migrator (created by the image entrypoint)
--    pdei_app      - runtime role used by the services; DML only, no DDL
--    pdei_readonly - for psql pokes, BI, and the Grafana Postgres datasource
-- ---------------------------------------------------------------------------
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pdei_app') THEN
    CREATE ROLE pdei_app LOGIN PASSWORD 'pdei_app';
  END IF;

  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pdei_readonly') THEN
    CREATE ROLE pdei_readonly LOGIN PASSWORD 'pdei_readonly';
  END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- 3. Extensions
--    pg_trgm    - fuzzy evidence lookup alongside the tsvector search of V10__fts.sql
--    pgcrypto   - digest()/gen_random_uuid() for hash-chain verification in SQL
--    btree_gin  - lets a GIN index mix tsvector with scalar columns (merchant_id, status)
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- ---------------------------------------------------------------------------
-- 4. Schema (contract section 5: schema name is "pdei")
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS pdei AUTHORIZATION pdei;

COMMENT ON SCHEMA pdei IS
  'PDEI operational truth. Tables are owned by Flyway (platform-persistence), not by init SQL.';

-- Flyway keeps its history table in the same schema; make that explicit.
ALTER DATABASE pdei SET search_path TO pdei, public;

-- ---------------------------------------------------------------------------
-- 5. Grants
-- ---------------------------------------------------------------------------
GRANT USAGE ON SCHEMA pdei TO pdei_app, pdei_readonly;
GRANT CREATE ON SCHEMA pdei TO pdei;

-- Existing objects (none on a fresh volume; matters when replayed).
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA pdei TO pdei_app;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA pdei TO pdei_app;
GRANT SELECT                         ON ALL TABLES    IN SCHEMA pdei TO pdei_readonly;

-- Future objects created by the migrator role.
ALTER DEFAULT PRIVILEGES FOR ROLE pdei IN SCHEMA pdei
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pdei_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pdei IN SCHEMA pdei
  GRANT USAGE, SELECT ON SEQUENCES TO pdei_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pdei IN SCHEMA pdei
  GRANT SELECT ON TABLES TO pdei_readonly;

-- ---------------------------------------------------------------------------
-- 6. Session defaults
--    Contract section 5 time rule: everything is stored and read as UTC. Pinning it at
--    the database level means a developer with a local TZ cannot accidentally shift
--    timestamps in a psql session.
-- ---------------------------------------------------------------------------
ALTER DATABASE pdei SET timezone TO 'UTC';
ALTER DATABASE pdei SET lc_monetary TO 'C';   -- money is BIGINT minor units; never formatted in SQL
ALTER DATABASE pdei SET statement_timeout TO '60s';
ALTER DATABASE pdei SET idle_in_transaction_session_timeout TO '120s';

-- ---------------------------------------------------------------------------
-- 7. Temporal databases
--    temporalio/auto-setup creates and schema-loads "temporal" and
--    "temporal_visibility" itself on first start, using the same superuser. They are
--    NOT created here: auto-setup owns their schema versioning and would fight with us.
--    Listed only so the next reader knows why three databases appear in \l.
-- ---------------------------------------------------------------------------

SELECT 'pdei init complete: schema pdei, roles pdei_app/pdei_readonly, extensions ready' AS status;
