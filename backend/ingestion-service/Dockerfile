# =====================================================================================
# ingestion-service  |  PDEI (Pre-Dispute Evidence Intelligence)
#
# Build context is the REPOSITORY ROOT, not this directory, because the Maven reactor
# needs sibling modules (platform-common, platform-persistence) and the build copies the
# shared JSON Schemas from /schemas/events into the jar:
#
#   docker build -f backend/ingestion-service/Dockerfile -t pdei/ingestion-service:dev .
#
# Runtime (PLATFORM-CONTRACT sections 2 and 15):
#   port 8081, health /actuator/health, metrics /actuator/prometheus
# =====================================================================================

# --- build ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependency layer: POMs first so a source-only change does not re-download the world.
COPY backend/pom.xml                          backend/pom.xml
COPY backend/platform-common/pom.xml          backend/platform-common/pom.xml
COPY backend/platform-persistence/pom.xml     backend/platform-persistence/pom.xml
COPY backend/ingestion-service/pom.xml        backend/ingestion-service/pom.xml
RUN mvn -f backend/pom.xml -q -B \
      -pl platform-common,platform-persistence,ingestion-service -am \
      -DskipTests dependency:go-offline || true

# Sources. /schemas/events is a build input: the module POM copies it into the jar.
COPY schemas/events                           schemas/events
COPY backend/platform-common/src              backend/platform-common/src
COPY backend/platform-persistence/src         backend/platform-persistence/src
COPY backend/ingestion-service/src            backend/ingestion-service/src

RUN mvn -f backend/pom.xml -B \
      -pl platform-common,platform-persistence,ingestion-service -am \
      -DskipTests package

# --- runtime -------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is for the HEALTHCHECK only; the JRE image ships without an HTTP client.
# Never run a network-facing service as root.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 1001 pdei \
 && useradd  --system --uid 1001 --gid pdei --home /app --shell /usr/sbin/nologin pdei

WORKDIR /app

COPY --from=build --chown=pdei:pdei /workspace/backend/ingestion-service/target/ingestion-service-*.jar /app/app.jar

# The schemas are already inside the jar; this copy is the overridable one. Mount the
# repository's schemas/events over it to register a new source schema without a rebuild
# (ingestion.schemas.directories reads it, and an external copy wins over the bundled one).
COPY --chown=pdei:pdei schemas/events /schemas/events

USER pdei
EXPOSE 8081

# Contract section 15 defaults; every one is overridable at run time.
ENV PDEI_KAFKA_BOOTSTRAP=kafka:9092 \
    PDEI_REDIS_URL=redis://redis:6379 \
    PDEI_POSTGRES_URL=jdbc:postgresql://postgres:5432/pdei \
    PDEI_POSTGRES_USER=pdei \
    PDEI_POSTGRES_PASSWORD=pdei \
    PDEI_SCHEMAS_DIR=/schemas/events \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
    OTEL_SERVICE_NAME=ingestion-service \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD curl -fsS http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
