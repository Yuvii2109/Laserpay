# `backend/` — PDEI Maven Reactor (`pdei-backend`)

> Module context for the reactor root. Normative references, in precedence order:
> `docs/PLATFORM-CONTRACT.md` → `docs/SHARED-LIBRARY-API.md` → `planner/pre-dispute-evidence-intelligence-reference.md`.
> If this file and those disagree, those win and this file is stale.

---

## 1. Purpose

`backend/pom.xml` is the aggregator and dependency-management authority for every JVM component of
PDEI. It exists to guarantee three things across twelve modules:

1. **One version of every third-party library.** No module declares a version; all of them are
   resolved from this POM's `dependencyManagement`. A Kafka client mismatch between
   `ingestion-service` and `normalization-worker` is not a debugging session anyone should ever have.
2. **One compiler configuration.** Java 21, `-parameters`, UTF-8, everywhere.
3. **A clean split between libraries and applications.** `spring-boot-maven-plugin` lives in
   `pluginManagement` only, so `platform-common`, `platform-persistence` and `evidence-core` build
   as plain jars that other modules can depend on, while the nine deployable services opt into
   fat-jar repackaging themselves.

## 2. Coordinates

| Item | Value |
|---|---|
| groupId | `com.laserpay.pdei` |
| artifactId | `pdei-backend` |
| version | `0.1.0-SNAPSHOT` |
| packaging | `pom` |
| Java release | 21 |
| Spring Boot | 3.3.5 (BOM import, **not** parent) |

### Why the Spring Boot BOM is imported rather than inherited

`spring-boot-starter-parent` brings resource filtering, a `repackage` execution bound by default,
and a plugin set that assumes every module is an application. Three of ours are libraries. Importing
`spring-boot-dependencies` into `dependencyManagement` gives the identical version alignment with
none of the application-shaped side effects.

The one thing lost by not inheriting the parent is **property-based version overriding**
(`<jackson.version>` in a child does not re-point the BOM). This POM compensates by pinning every
version-critical artifact explicitly in `dependencyManagement`, declared **before** the
`spring-boot-dependencies` import so the pins win under Maven's "first declaration wins" merge.
If you add a library whose version must differ from Boot's, pin it the same way, above the import.

## 3. Modules (build order is dependency order, not list order)

| # | Module | Package root | Kind | Port |
|---|---|---|---|---|
| 1 | `platform-common` | `com.laserpay.pdei.common` | library | — |
| 2 | `platform-persistence` | `com.laserpay.pdei.persistence` | library (JPA + Flyway) | — |
| 3 | `evidence-core` | `com.laserpay.pdei.core` | library (domain engine) | — |
| 4 | `api-gateway-service` | `com.laserpay.pdei.api` | Spring Boot web | 8080 |
| 5 | `ingestion-service` | `com.laserpay.pdei.ingestion` | Spring Boot web | 8081 |
| 6 | `normalization-worker` | `com.laserpay.pdei.normalization` | worker | 8082 |
| 7 | `state-builder-worker` | `com.laserpay.pdei.statebuilder` | worker | 8083 |
| 8 | `readiness-worker` | `com.laserpay.pdei.readiness` | worker | 8084 |
| 9 | `case-orchestrator-service` | `com.laserpay.pdei.orchestrator` | Boot + Temporal worker | 8085 |
| 10 | `document-processor-service` | `com.laserpay.pdei.docproc` | worker + web | 8086 |
| 11 | `audit-service` | `com.laserpay.pdei.audit` | worker + web | 8087 |
| 12 | `simulator-service` | `com.laserpay.pdei.simulator` | Spring Boot web | 8088 |

Dependency rule: `platform-common` ← `platform-persistence` ← `evidence-core` ← every service.
Services never depend on each other; they communicate over Kafka and (for the AI tool callbacks)
HTTP. Nothing depends on a service module.

## 4. File map

```
backend/
├── pom.xml                 reactor root: modules, dependencyManagement, pluginManagement
├── context.md              this file
├── platform-common/        IMPLEMENTED — see platform-common/context.md
├── platform-persistence/   (owned elsewhere)
├── evidence-core/          (owned elsewhere)
├── api-gateway-service/    (owned elsewhere)
├── ingestion-service/      (owned elsewhere)
├── normalization-worker/   (owned elsewhere)
├── state-builder-worker/   (owned elsewhere)
├── readiness-worker/       (owned elsewhere)
├── case-orchestrator-service/ (owned elsewhere)
├── document-processor-service/ (owned elsewhere)
├── audit-service/          (owned elsewhere)
└── simulator-service/      (owned elsewhere)
```

At the time of writing only `platform-common` has been generated. The other eleven `<module>`
entries are declared because the contract fixes the module list; the reactor will not build until
each directory contains a `pom.xml`.

## 5. Managed dependency versions

Pinned in `<properties>` and applied through `dependencyManagement`:

| Property | Version | Used by |
|---|---|---|
| `spring-boot.version` | 3.3.5 | all app modules |
| `temporal.version` | 1.25.1 | case-orchestrator-service |
| `minio.version` | 8.5.12 | evidence-core |
| `tika.version` | 2.9.2 | document-processor-service |
| `pdfbox.version` | 3.0.3 | document-processor-service |
| `flyway.version` | 10.20.1 | platform-persistence |
| `postgresql.version` | 42.7.4 | platform-persistence |
| `lettuce.version` | 6.3.2.RELEASE | idempotency / cache users |
| `jackson.version` | 2.17.2 | all |
| `micrometer.version` | 1.13.6 | all |
| `opentelemetry.version` | 1.42.1 | all (tracing) |
| `junit-jupiter.version` | 5.10.5 | tests |
| `assertj.version` | 3.25.3 | tests |
| `awaitility.version` | 4.2.2 | async/integration tests |
| `testcontainers.version` | 1.20.3 | integration tests |

Reactor-internal modules are also managed here (`platform-common`, `platform-persistence`,
`evidence-core` at `${project.version}`), so a consumer writes:

```xml
<dependency>
  <groupId>com.laserpay.pdei</groupId>
  <artifactId>platform-common</artifactId>
</dependency>
```

with no version.

## 6. Plugin configuration

`pluginManagement` (versions + configuration for everyone):

| Plugin | Version | Notes |
|---|---|---|
| `maven-compiler-plugin` | 3.13.0 | `release=21`, `<parameters>true</parameters>` + explicit `-parameters`, `-Xlint:all,-serial,-processing,-this-escape` |
| `maven-surefire-plugin` | 3.5.1 | `trimStackTrace=false`, `useModulePath=false` (classpath build, no JPMS) |
| `maven-failsafe-plugin` | 3.5.1 | reserved for Testcontainers `*IT` tests |
| `maven-jar-plugin` | 3.4.2 | — |
| `jacoco-maven-plugin` | 0.8.12 | `prepare-agent` + `report` at `verify` |
| `spring-boot-maven-plugin` | 3.3.5 | **pluginManagement only** — see below |

`build/plugins` (actually activated for the parent and inherited by all modules): compiler,
surefire, jacoco.

**Application modules must add this to their own POM** to produce a runnable jar:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-maven-plugin</artifactId>
    </plugin>
  </plugins>
</build>
```

Library modules must not. If `platform-common` were repackaged, its classes would move under
`BOOT-INF/` and nothing could compile against it.

Note on surefire and JaCoCo: `argLine` is deliberately **not** set in the surefire configuration.
JaCoCo's `prepare-agent` sets the `argLine` property itself; overriding it without the
`@{argLine}` late-replacement form silently disables coverage, and using `@{argLine}` breaks any
build run with `-Djacoco.skip=true`. Leaving it alone is correct for both.

## 7. Inbound / outbound contracts

The reactor root produces no runtime artifact and consumes no runtime contract. Its "contracts" are
build-time:

- **Inbound:** the module list, groupId/artifactId/version and Java version fixed by
  `docs/PLATFORM-CONTRACT.md` section 1, and the service registry in section 2.
- **Outbound:** every child module inherits compiler settings, test setup and managed versions. Any
  change here affects all twelve modules and must be reviewed as a platform change.

## 8. Configuration and environment variables

The build itself needs none. Runtime env vars are per-service and listed in
`docs/PLATFORM-CONTRACT.md` section 15 (`PDEI_POSTGRES_URL`, `PDEI_KAFKA_BOOTSTRAP`,
`PDEI_REDIS_URL`, `PDEI_MINIO_*`, `PDEI_TEMPORAL_*`, `PDEI_AI_SERVICE_URL`,
`OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_SERVICE_NAME`, …). Service modules bind them in their own
`application.yml`; nothing in `backend/pom.xml` reads or defaults them.

Toolchain expectations: JDK 21 on `PATH` (or `JAVA_HOME`), Maven 3.9+.

## 9. Build and run

```bash
# whole reactor (once all twelve modules exist)
cd backend
mvn -q clean verify

# one module and its reactor dependencies
mvn -q -pl platform-common -am clean verify

# a single module directly (does not require sibling modules to exist)
mvn -q -f platform-common/pom.xml clean test

# skip tests for a fast packaging pass
mvn -q clean package -DskipTests

# coverage report lands at <module>/target/site/jacoco/index.html
```

Running a service (after its module exists):

```bash
mvn -q -pl api-gateway-service spring-boot:run
# or
java -jar api-gateway-service/target/api-gateway-service-0.1.0-SNAPSHOT.jar
```

Infrastructure (Postgres, Kafka, Redis, MinIO, Temporal, observability stack) comes from
`infra/docker-compose.yml`; every service expects it to be up.

## 10. Conventions every module inherits

- **Money** is `com.laserpay.pdei.common.money.Money` (`long amountMinor`, `String currency`).
  No `double`, `float` or `BigDecimal` in any financial path, in code or in SQL.
- **Time** is `java.time.Instant` / `TIMESTAMPTZ` / ISO-8601 UTC. `LocalDateTime` is banned.
- **Ids** come from `common.id.Ids` and carry the prefixes in PLATFORM-CONTRACT section 5.
- **Events** are `common.event.CanonicalEvent`, keyed `merchantId + ":" + aggregateId`.
- **Consumers are idempotent**: dedupe on `eventId` via Redis `SETNX` plus the Postgres
  `processed_events` table, and tolerate late, duplicate and out-of-order delivery.
- **AI never mutates financial state.** All model code lives in the Python service; Java only calls
  it through `evidence-core`'s `AiReasoningClient` and gates every result.
- Every module carries its own `context.md`.

## 11. Extension points

- **Adding a module:** create the directory with a POM whose `<parent>` is this one, add a
  `<module>` entry here, and add its artifact to `dependencyManagement` if other modules will
  depend on it. Update `docs/PLATFORM-CONTRACT.md` sections 1 and 2 first — the contract leads.
- **Adding a dependency:** declare it (no version) in the consuming module; pin the version here
  only if Spring Boot does not already manage it or manages a version you must override.
- **Integration tests:** `maven-failsafe-plugin` is pre-configured in `pluginManagement`; a module
  that needs Testcontainers activates it and names its tests `*IT`.
- **Native/AOT, BOM publication, release profiles:** none configured; add as profiles rather than
  changing the default build.

## 12. Known gaps / TODOs

- **The reactor cannot build yet:** eleven of the twelve `<module>` directories have no `pom.xml`.
  Building `backend/pom.xml` as an aggregator fails until they exist. Build
  `platform-common` directly (`mvn -f platform-common/pom.xml test`) in the meantime.
- **Nothing in this workspace was compiled or test-run.** The machine that generated this tree has
  no JDK and no Maven on `PATH` (checked: `java`, `javac`, `mvn` all absent), so the POM and all
  sources are unverified by a real build. First action in an environment with a JDK 21 toolchain:
  run `mvn -f backend/platform-common/pom.xml clean test` and fix anything that surfaces.
- No `maven-enforcer-plugin` pinning the JDK/Maven version, no `.mvn/maven.config`, no wrapper
  (`mvnw`). Worth adding once CI exists.
- No `dependency-check`/SBOM plugin, no `spotless`/checkstyle. Formatting is by convention only.
- JaCoCo has no coverage thresholds (`check` goal not bound) — reports only.
- No `<distributionManagement>` / release profile; artifacts are local-install only.
