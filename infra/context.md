# `infra/` — PDEI Local Environment

> Module context for the whole local stack. Normative references, in precedence order:
> `docs/PLATFORM-CONTRACT.md` → `docs/SHARED-LIBRARY-API.md` →
> `planner/pre-dispute-evidence-intelligence-reference.md`. If this file and those
> disagree, those win and this file is stale.

---

## 1. Purpose

`infra/` is the entire runtime environment of PDEI expressed as files. Its job is that a
developer with nothing but Docker installed can go from a fresh clone to a running,
observable, seeded platform in three commands — at **zero infrastructure cost** (reference
doc §30), on Windows, macOS or Linux, with no cloud account and no paid service.

It owns three things and deliberately owns nothing else:

1. **Topology** — which containers exist, how they find each other, what order they start in.
2. **Infrastructure configuration** — Kafka topics, the Postgres bootstrap, MinIO buckets,
   the Temporal namespace, and the whole observability pipeline.
3. **Image definitions** for the code in this repository.

It does **not** own application configuration. A Spring service reads its own
`application.yaml`; this directory only supplies the environment variables of contract §15.
It does not own the database schema either — see §6.

## 2. Responsibilities

| Responsibility | Where it lives |
|---|---|
| Container topology, profiles, healthchecks, startup order | `docker-compose.yml` |
| Host-run development overrides | `docker-compose.override.yml.example` |
| Every environment variable of contract §15 | `.env.example` |
| Building the 9 Spring services, the Python service, the frontend | `docker/Dockerfile.*` |
| The eight Kafka topics with their partition counts and retention | `kafka/create-topics.sh` |
| Database, roles, schema, extensions (NOT tables) | `postgres/init/01-init.sql` |
| Temporal tuning for a single-node SQL cluster | `temporal/dynamicconfig/development-sql.yaml` |
| Scrape targets, recording rules, alerts | `prometheus/prometheus.yml`, `prometheus/rules/` |
| Datasources, dashboard provider, four dashboards | `grafana/` |
| Log ingestion and label discipline | `promtail/promtail-config.yml`, `loki/loki-config.yml` |
| Trace storage and span metrics | `tempo/tempo.yml` |
| The single OTLP front door | `otel/otel-collector-config.yaml` |

---

## 3. File map

```
infra/
├── context.md                          this file
├── docker-compose.yml                  30 services, 3 profiles, 9 named volumes
├── docker-compose.override.yml.example copy to docker-compose.override.yml to customise
├── .env.example                        every variable of contract §15 + compose knobs
│
├── docker/
│   ├── Dockerfile.spring-service       ONE image definition for all nine Spring services
│   │                                   (ARG MODULE selects the Maven module)
│   ├── Dockerfile.ai-service           python:3.11-slim + uv + uvicorn
│   └── Dockerfile.frontend             node:20-alpine, deps → build → runtime
│
├── kafka/
│   └── create-topics.sh                idempotent topic bootstrap, run by kafka-init
│
├── postgres/
│   └── init/01-init.sql                database, roles, schema pdei, extensions
│
├── temporal/
│   └── dynamicconfig/development-sql.yaml
│
├── prometheus/
│   ├── prometheus.yml                  scrape config for every service + exporters
│   └── rules/pdei-alerts.yml           recording rules + 7 alerts
│
├── grafana/
│   ├── provisioning/datasources/datasources.yml   Prometheus, Loki, Tempo (pinned UIDs)
│   ├── provisioning/dashboards/dashboards.yml     file provider → folder "PDEI"
│   └── dashboards/
│       ├── pdei-event-pipeline.json
│       ├── pdei-evidence-readiness.json
│       ├── pdei-ai-usage.json
│       └── pdei-workflow-health.json
│
├── loki/loki-config.yml                single-binary, filesystem, TSDB schema v13
├── promtail/promtail-config.yml        docker SD → JSON parse → labels + metadata
├── tempo/tempo.yml                     OTLP in, local blocks, span metrics → Prometheus
└── otel/otel-collector-config.yaml     OTLP in; Tempo + Prometheus + Loki out
```

---

## 4. Containers — the complete inventory

### 4.1 Profile `core` — infrastructure (10 containers)

| Container | Image | Host → container | Volume | Purpose |
|---|---|---|---|---|
| `pdei-postgres` | `postgres:16-alpine` | 5432 → 5432 | `pdei-postgres-data` | Operational truth. Also hosts Temporal's two databases. |
| `pdei-redis` | `redis:7-alpine` | 6379 → 6379 | `pdei-redis-data` | Dedupe keys, readiness cache, locks, AI token bucket (contract §12). AOF on. |
| `pdei-kafka` | `apache/kafka:3.8.1` | 29092 → 29092 | `pdei-kafka-data` | KRaft single node, broker + controller in one process. |
| `pdei-kafka-init` | `apache/kafka:3.8.1` | — | — | One-shot. Creates the eight topics, then exits 0. |
| `pdei-kafka-ui` | `provectuslabs/kafka-ui:v0.7.2` | 8090 → 8080 | — | Topic browser used live in demo beats 1 and 7. |
| `pdei-minio` | `minio/minio:latest` | 9000 → 9000, 9001 → 9001 | `pdei-minio-data` | Evidence blobs and representment packages. |
| `pdei-minio-init` | `minio/mc:latest` | — | — | One-shot. Creates `pdei-evidence` + `pdei-packages`, enables versioning. |
| `pdei-temporal` | `temporalio/auto-setup:1.25.1` | 7233 → 7233 | (uses postgres) | Workflow engine; auto-creates schema and the `pdei` namespace. |
| `pdei-temporal-admin-tools` | `temporalio/admin-tools:1.25.1-tctl-1.18.1-cli-1.1.1` | — | — | Long-lived shell for `temporal` CLI; re-asserts the namespace. |
| `pdei-temporal-ui` | `temporalio/ui:2.31.2` | 8233 → 8080 | — | Workflow durability proof in demo beat 7. |

### 4.2 Profile `app` — the platform (11 containers)

All nine JVM services are built from **one** Dockerfile with `--build-arg MODULE=<name>`.

| Container | Host port | `OTEL_SERVICE_NAME` | Extra dependencies |
|---|---|---|---|
| `pdei-api-gateway-service` | 8080 | `api-gateway-service` | minio, minio-init |
| `pdei-ingestion-service` | 8081 | `ingestion-service` | — |
| `pdei-normalization-worker` | 8082 | `normalization-worker` | — |
| `pdei-state-builder-worker` | 8083 | `state-builder-worker` | — |
| `pdei-readiness-worker` | 8084 | `readiness-worker` | — |
| `pdei-case-orchestrator-service` | 8085 | `case-orchestrator-service` | temporal, minio |
| `pdei-document-processor-service` | 8086 | `document-processor-service` | minio, minio-init |
| `pdei-audit-service` | 8087 | `audit-service` | — |
| `pdei-simulator-service` | 8088 | `simulator-service` | — |
| `pdei-ai-reasoning-service` | 8000 | `ai-reasoning-service` | redis only — **never** Postgres |
| `pdei-frontend` | 3000 | — | api-gateway-service (started, not healthy) |

> The AI service depends on Redis and nothing else. That is not an oversight: contract §17
> rule 2 says the LLM never mutates financial state, and the cheapest way to guarantee it is
> to never give the process a database connection. It reads what it needs through the
> read-only `/api/v1/ai-tools/*` endpoints with `X-PDEI-Service-Token`.

### 4.3 Profile `obs` — observability (9 containers)

| Container | Image | Host → container | Volume |
|---|---|---|---|
| `pdei-otel-collector` | `otel/opentelemetry-collector-contrib:0.111.0` | 4317, 4318, 8889, 13133 | — |
| `pdei-prometheus` | `prom/prometheus:v2.54.1` | 9090 → 9090 | `pdei-prometheus-data` |
| `pdei-grafana` | `grafana/grafana:11.2.0` | 3001 → 3000 | `pdei-grafana-data` |
| `pdei-loki` | `grafana/loki:3.1.1` | 3100 → 3100 | `pdei-loki-data` |
| `pdei-promtail` | `grafana/promtail:3.1.1` | — | `pdei-promtail-data` |
| `pdei-tempo` | `grafana/tempo:2.6.0` | 3200 → 3200 | `pdei-tempo-data` |
| `pdei-kafka-exporter` | `danielqsj/kafka-exporter:v1.7.0` | 9308 → 9308 | — |
| `pdei-postgres-exporter` | `prometheuscommunity/postgres-exporter:v0.15.0` | 9187 → 9187 | — |
| `pdei-redis-exporter` | `oliver006/redis_exporter:v1.62.0` | 9121 → 9121 | — |

---

## 5. Profiles

| Profile | Command | What you get |
|---|---|---|
| `core` | `docker compose --profile core up -d` | Postgres, Redis, Kafka (+init), Kafka UI, MinIO (+init), Temporal (+admin, +UI) |
| `app` | `… --profile core --profile app up -d` | the above plus the nine Spring services, the Python service and the frontend |
| `obs` | `… --profile core --profile app --profile obs up -d` | plus collector, Prometheus, Grafana, Loki, Promtail, Tempo, three exporters |

**Profiles must be named explicitly, and `app` always needs `core` alongside it.** This file
previously said Compose ≥ 2.24 auto-enables the profile of anything reachable via
`depends_on`, so `--profile app` alone would work. It does not — verified against Compose
v5.1.4, where every shorthand fails at project validation, before a container is created:

```
docker compose --profile app config          service "normalization-worker" depends on
COMPOSE_PROFILES=app docker compose config   undefined service "postgres": invalid compose project
docker compose up api-gateway-service        no such service: kafka
```

The scripts pass profiles explicitly, which is why the incorrect note never caused a failure.

`scripts/up.sh` with no arguments starts all three.

---

## 6. Startup order

Ordering is expressed as `depends_on: condition:` in the compose file, never as sleeps.
The actual dependency graph:

```
postgres ─┬─(healthy)─► temporal ──(healthy)─┬─► temporal-admin-tools
          │                                  └─► temporal-ui
          │
          ├─(healthy)─► postgres-exporter
          │
kafka ────┼─(healthy)─► kafka-init ──(exit 0)──┐
          ├─(healthy)─► kafka-ui               │
          └─(healthy)─► kafka-exporter         │
                                               │
minio ────┬─(healthy)─► minio-init ─(exit 0)─┐ │
          └─(healthy)────────────────────────┤ │
                                             │ │
redis ────(healthy)──────────────────────────┤ │
                                             ▼ ▼
                          ┌──────────────────────────────────┐
                          │ the nine Spring services         │
                          │ (api-gateway also waits on minio)│
                          │ (orchestrator also waits Temporal│
                          └────────────────┬─────────────────┘
                                           │ (started)
                                           ▼
                                       frontend

loki ─► otel-collector ◄─ tempo          prometheus ─► grafana
```

Two of those edges matter more than the rest:

* **`kafka-init` must complete before any service starts.** `KAFKA_AUTO_CREATE_TOPICS_ENABLE`
  is `false` on purpose — a typo'd topic name should fail loudly, not silently create a
  9th topic with one partition. `service_completed_successfully` is what enforces it.
* **`minio-init` must complete before the gateway or the document processor start**, because
  both will attempt bucket operations on their first request and bucket versioning must
  already be on (contract §11) or the evidence version chain has a hole at v1.

Cold-start timing on a mid-range laptop, `--profile core`: ~45 s to all-healthy (Temporal's
schema setup dominates). Full stack including image builds: **10–20 minutes the first time**,
~90 s afterwards.

### The one thing this directory does NOT set up

**Tables.** `postgres/init/01-init.sql` creates the database, roles, schema and extensions,
and stops. Every table, index and constraint is Flyway's, owned by
`backend/platform-persistence/src/main/resources/db/migration` (contract §5). If a table
appears in the init SQL, the schema has two owners and they will drift.

---

## 7. Volumes

Nine named volumes, all pinned with explicit `name:` keys so they are addressable by
`docker volume rm` even when Compose has forgotten them.

| Volume | Container path | Holds | Safe to delete? |
|---|---|---|---|
| `pdei-postgres-data` | `/var/lib/postgresql/data` | the `pdei` database **and Temporal's two databases** | Yes — Flyway rebuilds; Temporal auto-setup rebuilds |
| `pdei-redis-data` | `/data` | AOF of dedupe keys, caches, budgets | Yes — everything in Redis is derived, never authoritative |
| `pdei-kafka-data` | `/var/lib/kafka/data` | KRaft metadata + all eight topic logs | Yes, but you lose replayability of past runs |
| `pdei-minio-data` | `/data` | evidence blobs, package zips, **all object versions** | Yes — but evidence rows in Postgres will then point at missing objects |
| `pdei-prometheus-data` | `/prometheus` | TSDB, 15 d retention | Yes |
| `pdei-grafana-data` | `/var/lib/grafana` | Grafana's own SQLite (users, UI-side dashboard edits) | Yes — provisioned dashboards and datasources come back from files |
| `pdei-loki-data` | `/loki` | log chunks + TSDB index, 7 d retention | Yes |
| `pdei-tempo-data` | `/var/tempo` | trace blocks, 48 h retention | Yes |
| `pdei-promtail-data` | `/var/lib/promtail` | read positions | Yes — deleting it re-reads container logs from the start |

`scripts/reset.sh` removes all nine plus the `pdei-net` network.

> **Deleting `pdei-minio-data` without also deleting `pdei-postgres-data` is the one
> genuinely inconsistent state you can create by hand.** Evidence rows will reference object
> keys that no longer exist and `EvidenceIntegrityService` will start reporting tamper. Wipe
> both, or neither.

---

## 8. Credentials (all dev-only)

| What | User | Secret | Set by |
|---|---|---|---|
| PostgreSQL | `pdei` | `pdei` | `PDEI_POSTGRES_USER` / `PDEI_POSTGRES_PASSWORD` |
| PostgreSQL runtime role | `pdei_app` | `pdei_app` | `postgres/init/01-init.sql` |
| PostgreSQL read-only role | `pdei_readonly` | `pdei_readonly` | `postgres/init/01-init.sql` |
| Redis | — | none | — |
| Kafka | — | none (PLAINTEXT) | — |
| MinIO | `pdei-minio` | `pdei-minio-secret` | `PDEI_MINIO_ACCESS_KEY` / `PDEI_MINIO_SECRET_KEY` |
| Grafana | `admin` | `admin` | `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` |
| Temporal | — | none, namespace `pdei` | `PDEI_TEMPORAL_NAMESPACE` |
| Service-to-service token | — | `dev-service-token` | `PDEI_SERVICE_TOKEN` |
| Gemini | — | empty by default | `GEMINI_API_KEY` |

Anonymous **viewer** access is enabled on Grafana so a demo does not open with a login form.
Nothing here is a secret; nothing here is used anywhere but a laptop. `infra/.env` is
git-ignored so a real `GEMINI_API_KEY` cannot be committed by accident.

---

## 9. Configuration and environment variables

`infra/.env` (created from `.env.example` by `bootstrap.sh` or any script that needs it) is
read automatically by Compose because Compose is always invoked from `infra/`.

### 9.1 Contract §15 variables

Injected into all nine Spring services through the `x-pdei-env` YAML anchor:

```
PDEI_POSTGRES_URL / _USER / _PASSWORD    PDEI_KAFKA_BOOTSTRAP    PDEI_REDIS_URL
PDEI_MINIO_ENDPOINT / _ACCESS_KEY / _SECRET_KEY
PDEI_TEMPORAL_TARGET / _NAMESPACE        PDEI_AI_SERVICE_URL     PDEI_AI_PROVIDER
PDEI_API_BASE_URL                        PDEI_SERVICE_TOKEN
OTEL_EXPORTER_OTLP_ENDPOINT              OTEL_SERVICE_NAME (per service = module name)
GEMINI_API_KEY / GEMINI_MODEL            NEXT_PUBLIC_API_BASE_URL / NEXT_PUBLIC_WS_URL
```

`OTEL_SERVICE_NAME` is set **per service**, never globally — a single global value would
attribute every span in the system to one service and make the distributed trace useless.

### 9.2 Internal versus host addresses

This trips everyone up once:

| From | Postgres | Kafka | MinIO | Gateway |
|---|---|---|---|---|
| inside a container | `postgres:5432` | `kafka:9092` (INTERNAL listener) | `minio:9000` | `api-gateway-service:8080` |
| from your host | `localhost:5432` | `localhost:29092` (**EXTERNAL** listener) | `localhost:9000` | `localhost:8080` |

Kafka advertises two listeners for exactly this reason. A host-run service configured with
`kafka:9092` will connect to the bootstrap and then fail on every produce, because the broker
hands back an address only reachable inside the Docker network.

`NEXT_PUBLIC_*` values are **browser-side** and must therefore use `localhost`, while the
frontend's server-side code uses `PDEI_API_BASE_URL` with the in-network name. Both are set.

### 9.3 Compose-only knobs

`PDEI_IMAGE_TAG`, `SPRING_PROFILES_ACTIVE`, `PDEI_TEMPORAL_TASK_QUEUE`,
`PDEI_SIM_DEFAULT_SEED`, `PDEI_KAFKA_CLUSTER_ID`, `NEXT_PUBLIC_USE_MOCKS`, `PDEI_AI_APP`,
`GRAFANA_ADMIN_*`, and a `PDEI_*_HOST_PORT` for every published infrastructure port.
Container ports never move; only the host side is remappable.

---

## 10. Kafka topics

Created by `kafka/create-topics.sh`, which is idempotent — existing topics get their config
re-applied rather than recreated, so editing retention there and re-running is the supported
way to change it.

| Topic | Partitions | Cleanup policy | Retention |
|---|---|---|---|
| `pdei.raw.events.v1` | 12 | delete | 7 d |
| `pdei.canonical.events.v1` | 12 | delete | 30 d |
| `pdei.evidence.events.v1` | 12 | delete | 30 d |
| `pdei.readiness.events.v1` | 12 | **compact,delete** | 14 d |
| `pdei.dispute.events.v1` | 12 | delete | 90 d |
| `pdei.case.events.v1` | 12 | delete | 90 d |
| `pdei.audit.events.v1` | 6 | delete | 365 d |
| `pdei.dlq.v1` | 6 | delete | 14 d |

**Why only one compacted topic.** Compaction keeps the newest record per key and discards the
rest. The key here is `merchantId:aggregateId` (contract §4), so compacting an event-sourced
topic would silently delete the history that replay and audit depend on — the demo's
"throw the database away and rebuild it" beat would stop working. `pdei.readiness.events.v1`
is the exception because a readiness event is a *snapshot* of a transaction's score, not an
increment: the newest record per key genuinely is sufficient to rebuild current readiness,
and the full history still lives in `readiness_snapshots` and the audit chain.

Run it by hand against a live broker:

```bash
docker compose --profile core run --rm kafka-init
```

---

## 11. Observability pipeline

```
   Spring services            Python service
   (Micrometer + OTel)        (prometheus_client + OTel)
          │                            │
          │  OTLP http/protobuf → otel-collector:4318
          ▼                            ▼
   ┌──────────────────────────────────────────────┐
   │ otel-collector                               │
   │  memory_limiter → resource → attributes      │
   │                 → batch                      │
   └───────┬──────────────┬──────────────┬────────┘
           │ traces       │ metrics      │ logs
           ▼              ▼              ▼
        Tempo        :8889 exposition   Loki /otlp
        :4317        (pulled by         :3100
                      Prometheus)
                           ▲
   container stdout ───► promtail ───► Loki
                           │
                   Prometheus also scrapes each service directly
                   at /actuator/prometheus and /metrics
```

Two metric paths exist on purpose. Direct scraping of `/actuator/prometheus` is the primary
one — it keeps working when the `obs` profile's collector is down. The OTLP path carries
anything a service pushes rather than exposes, plus Tempo's generated span metrics.

**Label discipline in Loki.** `merchantId`, `level` and `eventType` are stream labels
(bounded cardinality). `traceId`, `spanId`, `correlationId` and `eventId` are *structured
metadata*, not labels — one value per request would explode the index. Query them with a
line filter or follow the Tempo link, which is wired up in `datasources.yml`.

Datasource UIDs are pinned (`pdei-prometheus`, `pdei-loki`, `pdei-tempo`) because the
dashboard JSON references them. Renaming one silently blanks every panel.

### Metrics the dashboards consume

Everything in contract §13, plus two the readiness worker is expected to expose that §13 does
not list explicitly (see Known gaps): `pdei_readiness_gaps_open{merchant,type,severity}` and
the `_bucket` series implied by the histograms.

---

## 12. Inbound contracts (what this module consumes)

| From | What |
|---|---|
| `backend/` | The Maven reactor. `Dockerfile.spring-service` builds `-pl <MODULE> -am` from `backend/pom.xml`; the module list must match contract §1. |
| `ai-reasoning-service/` | `pyproject.toml` (required) and optionally `uv.lock` / `requirements.txt`. Entry point defaults to `pdei_ai.main:app`, overridable with `PDEI_AI_APP`. |
| `frontend/` | `package.json` with `build` and `start` scripts; `package-lock.json` if you want `npm ci`. |
| `docs/PLATFORM-CONTRACT.md` | §2 ports, §4 topics, §11 buckets, §12 Redis keys, §13 metrics, §15 env vars. CI enforces §2 and §4 mechanically. |
| Docker daemon socket | Promtail's service discovery. Read-only mount. |

## 13. Outbound contracts (what this module produces)

| To | What |
|---|---|
| every service | The environment of contract §15, on network `pdei-net`, with hostnames equal to compose service names. |
| every service | Eight pre-created Kafka topics — services must not create their own. |
| `platform-persistence` | A `pdei` database with schema `pdei`, roles `pdei_app`/`pdei_readonly`, and `pg_trgm` / `pgcrypto` / `btree_gin` installed, ready for Flyway. |
| `evidence-core` (`MinioObjectStore`) | Buckets `pdei-evidence` and `pdei-packages` with versioning ON. |
| `case-orchestrator-service` | A Temporal cluster with namespace `pdei` registered, 72 h retention. |
| operators | Four provisioned Grafana dashboards, seven alert rules, and the trace↔log↔metric links between them. |
| `scripts/` | A predictable service naming scheme (`pdei-<compose-service>`) that the smoke test and log scripts rely on. |

## 14. Dependencies on other modules

`infra/` has no build-time dependency on any module: `--profile core` starts with an empty
repository. The `app` profile needs source trees to exist:

* the nine Spring services need `backend/<module>/` to be a real Maven module;
* `ai-reasoning-service/pyproject.toml` must exist for the AI image to build;
* `frontend/package.json` must exist for the frontend image to build.

Until a module lands, build that service's image and it fails; everything else still runs.
CI is structured the same way — the `detect` job skips jobs whose tree is absent.

---

## 15. How to build and run

```bash
# once
./scripts/bootstrap.sh --pull

# every day
./scripts/up.sh                 # core + app + obs
./scripts/up.sh core            # infrastructure only (fast; use with IDE-run services)
./scripts/smoke-test.sh         # table of every component's health; exit code = failures
./scripts/logs.sh --pipeline    # the four event-pipeline workers together
./scripts/down.sh               # stop, keep data
./scripts/reset.sh --yes --up   # wipe everything and restart

# Windows PowerShell
.\scripts\up.ps1 -Profiles core,app
.\scripts\smoke-test.ps1
.\scripts\reset.ps1 -Force -Up
```

Rebuild one service after a code change:

```bash
cd infra
docker compose --profile app build readiness-worker
docker compose --profile app up -d readiness-worker
```

Run one service from your IDE against the containerised rest:

```bash
cp infra/docker-compose.override.yml.example infra/docker-compose.override.yml
# edit it, then use the HOST addresses from §9.2 in your run configuration
```

---

## 16. Extension points

| You want to… | Do this |
|---|---|
| Add a service | Add a compose entry copying an existing Spring block (anchors `*spring-service`, `*pdei-env`, `*infra-deps`), add its scrape target to `prometheus.yml`, add a row to `smoke-test.sh`/`.ps1`. |
| Give a module a bespoke image | Add `backend/<module>/Dockerfile` and point that service's `dockerfile:` key at it. The shared one stays for the other eight. |
| Add a Kafka topic | Add an `ensure_topic` line in `create-topics.sh` — **and** to contract §4 and `Topics.java` first, in that order. |
| Change retention | Edit the config list in `create-topics.sh` and re-run `kafka-init`; it alters existing topics in place. |
| Add a dashboard | Drop the JSON in `grafana/dashboards/`; the file provider picks it up within 30 s. Use datasource uid `pdei-prometheus`. |
| Add an alert | Append to `prometheus/rules/pdei-alerts.yml`; Prometheus hot-reloads with `curl -XPOST localhost:9090/-/reload`. |
| Add a datasource | Add to `datasources.yml` with a new pinned uid. Never reuse an existing uid. |
| Send traces elsewhere | Add an exporter in `otel-collector-config.yaml` and reference it in the `traces` pipeline. Services stay unchanged — that is the point of the collector. |
| Run against real Gemini | Set `PDEI_AI_PROVIDER=gemini` and `GEMINI_API_KEY` in `infra/.env`, then `docker compose up -d ai-reasoning-service`. |
| Bind a different host port | Set the matching `PDEI_*_HOST_PORT` in `infra/.env`. Never edit the container side. |
| Add an exporter | New service in the `obs` profile plus a scrape job. Keep it in `obs` so `core` stays lean. |

---

## 17. Troubleshooting — the failures that actually happen

### "port is already allocated"

Something else owns the port. On Windows the usual suspects are 5432 (a local Postgres
install), 3000 (another dev server) and 9090.

```bash
# Windows
netstat -ano | findstr :5432
# macOS / Linux
lsof -i :5432
```

Fix without touching the compose file: set the matching `PDEI_*_HOST_PORT` in `infra/.env`.
Application ports (8080–8088, 8000, 3000) are fixed by contract §2 and are not remappable —
stop the other process instead.

### Kafka will not start / `InconsistentClusterIdException`

The KRaft log directory remembers the cluster id it was formatted with. Changing
`PDEI_KAFKA_CLUSTER_ID` after first boot invalidates it.

```bash
docker compose --profile core down
docker volume rm pdei-kafka-data
docker compose --profile core up -d kafka kafka-init
```

### Kafka logs `Permission denied` on `/var/lib/kafka/data`

A fresh named volume mounts root-owned and `apache/kafka`'s default `appuser` cannot write to
it. This is why the service runs with `user: root`. If you removed that line, put it back or
`chown` the volume.

### `kafka-init` exits non-zero and every service refuses to start

That is `service_completed_successfully` working. Read the actual error:

```bash
docker compose --profile core logs kafka-init
docker compose --profile core run --rm kafka-init   # re-run it by hand
```

### Services start, then die with a Flyway error

Flyway is owned by `platform-persistence`, not by this directory. Two common causes: a
migration was edited after it was applied (checksum mismatch), or a migration failed halfway
and left the schema history dirty.

```bash
docker exec -it pdei-postgres psql -U pdei -d pdei -c \
  'select installed_rank, version, description, success from pdei.flyway_schema_history order by installed_rank desc limit 10;'
```

For local work the fastest fix is `./scripts/reset.sh --yes --up`. Never `repair` a
production database this casually.

### Temporal never becomes healthy

`auto-setup` creates the `temporal` and `temporal_visibility` databases on first boot and
needs Postgres to be genuinely ready — not merely accepting TCP. It retries, so give it
40–60 s. If it is still failing:

```bash
docker compose --profile core logs temporal | tail -50
docker exec -it pdei-postgres psql -U pdei -c '\l'    # expect pdei, temporal, temporal_visibility
```

Wiping `pdei-postgres-data` also wipes Temporal's databases; auto-setup rebuilds them.

### `Namespace pdei is not registered`

Registration happens once, in `auto-setup`, via `DEFAULT_NAMESPACE`. Re-assert it:

```bash
docker compose exec temporal-admin-tools \
  temporal operator namespace create --namespace pdei --retention 72h
```

### A host-run service cannot reach Kafka

You almost certainly used `kafka:9092`. From the host it is **`localhost:29092`** — see §9.2.
The bootstrap connection succeeds and then every produce fails, which is what makes this one
confusing.

### The frontend loads but every API call fails

`NEXT_PUBLIC_API_BASE_URL` is baked in at **build** time, not read at runtime. If you changed
it in `.env`, rebuild:

```bash
docker compose --profile app build frontend && docker compose --profile app up -d frontend
```

For a demo with no backend, set `NEXT_PUBLIC_USE_MOCKS=true` — and say out loud that it is
fixture data.

### Grafana shows "No data" on every panel

In order of likelihood: (1) the `app` profile is not running, so there is nothing to scrape;
(2) nothing has been seeded — run `./scripts/seed-demo.sh`; (3) check
http://localhost:9090/targets for red rows; (4) a service is not exposing
`/actuator/prometheus` — contract §2 requires `health,info,prometheus,metrics,loggers`.

### Grafana dashboards vanished after an edit

UI edits are not written back to `infra/grafana/dashboards/*.json`. Export the JSON from the
UI and commit the file, or the next container restart reverts it.

### Promtail collects nothing

It needs the Docker socket and the container log directory. On Docker Desktop for Windows
this works through the WSL2 VM; if it does not, confirm both mounts exist and that Promtail
is running as root:

```bash
docker compose --profile obs logs promtail | tail -30
```

### Traces appear in Tempo but logs do not link to them

The link is built from the `traceId` field in the JSON log line. If a service logs plain text
instead of JSON, promtail's parse stages fall through and no `traceId` metadata is attached.
Contract §13 requires structured JSON on stdout carrying `traceId`, `spanId`, `merchantId`
and `correlationId`.

### MinIO evidence downloads 403 / SignatureDoesNotMatch

Presigned URLs are signed for a specific endpoint. If a service signs with
`http://minio:9000` and the browser opens it against `localhost:9000`, the signature does not
match. Whatever generates the URL must sign with the host-visible endpoint.

### The build is enormous / slow

The build context is the repository root (the Maven reactor needs it). `/.dockerignore`
excludes `node_modules`, `target/`, `.git` and friends. If a build is uploading hundreds of
megabytes, something new needs adding there.

### Everything is broken and you have stopped caring why

```bash
./scripts/reset.sh --yes --up
./scripts/seed-demo.sh
```

Twelve minutes, deterministic, back to a known world.

---

## 18. Known gaps and TODOs

1. **Service images are built from one shared Dockerfile.** `docker/Dockerfile.spring-service`
   takes `ARG MODULE`. If a module ever needs bespoke build steps, add
   `backend/<module>/Dockerfile` and repoint that service's `dockerfile:` key. Documented as
   a convention, not enforced anywhere.
2. **The `app` profile cannot build until the modules exist.** Only `platform-common`,
   `platform-persistence` and `evidence-core` are present today; the nine service modules,
   `ai-reasoning-service/` and `frontend/` are owned by other work. `--profile core` is fully
   functional now; `--profile app` becomes functional as each tree lands.
3. **The Python entry point is a guess.** `Dockerfile.ai-service` defaults to
   `pdei_ai.main:app`. If the service names its app object differently, set `PDEI_AI_APP` in
   `infra/.env` rather than editing the Dockerfile.
4. **The frontend image runs `next start`, not `output: "standalone"`.** Standalone would
   produce a much smaller image but requires a `next.config` setting this directory does not
   own. Revisit once the frontend exists.
5. **No OpenTelemetry Java agent.** Services are expected to instrument themselves with
   Micrometer Tracing + the OTLP exporter. Attaching `opentelemetry-javaagent.jar` in the
   Dockerfile would give auto-instrumentation for free — deliberately deferred to avoid a
   build-time download and a second source of truth for span naming.
6. **Two dashboard metrics are not in contract §13.** The readiness panels read
   `pdei_readiness_gaps_open{merchant,type,severity}`. §13 lists the required minimum, not an
   exhaustive set, but if `readiness-worker` does not expose that gauge those panels stay
   empty (everything else on the dashboard uses contract-listed names). Either add the gauge
   in the worker or add the metric to §13 — the dashboards should not be the only place it is
   written down.
7. **Temporal task-queue backlog panel depends on Temporal's own metric names.**
   `service_latency_bucket` comes from the Temporal server's Prometheus listener on `:8000`;
   if that listener is not enabled in a future image version, that one panel goes blank.
8. **No Alertmanager.** The seven rules in `prometheus/rules/pdei-alerts.yml` fire into
   Prometheus and Grafana only. Nothing pages anyone, by design — this is a laptop.
9. **Single-broker Kafka, replication factor 1.** Correct for local; every topic would need
   `--replication-factor 3` and `min.insync.replicas=2` anywhere real.
10. **`minio/minio:latest` and `minio/mc:latest` are unpinned.** MinIO's release tags are
    date-stamped and churn quickly; pinning them makes the compose file stale within weeks.
    Every other image is pinned to an exact version.
11. **No resource limits.** No `mem_limit`/`cpus` anywhere, so a runaway JVM can starve the
    host. Add them in your override file if a simulator run makes your laptop unusable.
12. **`docker-compose.override.yml.example` is not exercised by the app profile in CI.** CI
    validates that it parses and merges, but no job actually runs the stack from it.
13. **The smoke test checks liveness, not correctness.** A green table means every process
    answers; it does not mean an event can traverse the pipeline. An end-to-end
    "post an event, assert a readiness snapshot appears" check belongs in
    `scripts/smoke-test.sh` once `ingestion-service` exists.
