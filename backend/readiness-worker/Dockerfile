# =====================================================================================
# readiness-worker  |  PDEI (Pre-Dispute Evidence Intelligence)
#
# BUILD CONTEXT IS THE REACTOR ROOT, NOT THIS DIRECTORY:
#
#   docker build -f backend/readiness-worker/Dockerfile -t pdei/readiness-worker:dev backend
#
# The worker depends on platform-common, platform-persistence and evidence-core, which are
# sibling modules; a context rooted at this directory could not see them.
# =====================================================================================

# --- stage 1: dependency cache -------------------------------------------------------
# Copying only the POMs first means a source-only change never re-downloads the world.
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build

COPY pom.xml ./pom.xml
COPY platform-common/pom.xml       platform-common/pom.xml
COPY platform-persistence/pom.xml  platform-persistence/pom.xml
COPY evidence-core/pom.xml         evidence-core/pom.xml
COPY readiness-worker/pom.xml      readiness-worker/pom.xml

RUN mvn -B -q -pl readiness-worker -am dependency:go-offline -DskipTests || true

# --- stage 2: build ------------------------------------------------------------------
FROM deps AS build
WORKDIR /build

COPY platform-common       platform-common
COPY platform-persistence  platform-persistence
COPY evidence-core         evidence-core
COPY readiness-worker      readiness-worker

# -am builds the three library modules this worker needs, and nothing else in the reactor.
RUN mvn -B -pl readiness-worker -am clean package -DskipTests

# --- stage 3: runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# Never run a financial service as root.
RUN groupadd --system pdei && useradd --system --gid pdei --home /app pdei
WORKDIR /app

COPY --from=build --chown=pdei:pdei /build/readiness-worker/target/readiness-worker.jar /app/app.jar

USER pdei
EXPOSE 8084

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC" \
    OTEL_SERVICE_NAME=readiness-worker \
    SPRING_PROFILES_ACTIVE=docker

# Actuator is the contract's health endpoint for every Spring service (section 2).
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD ["sh", "-c", "wget -qO- http://localhost:8084/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
