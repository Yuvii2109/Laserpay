# =====================================================================================
# case-orchestrator-service  |  PDEI  |  container port 8085
#
# Build context is the REPO ROOT, not this directory: the module depends on three sibling
# Maven modules (platform-common, platform-persistence, evidence-core) that must be in the
# reactor for the build to resolve.
#
#   docker build -f backend/case-orchestrator-service/Dockerfile -t pdei/case-orchestrator-service .
# =====================================================================================

# ---- build ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Poms first: this layer is cached until a dependency actually changes.
COPY backend/pom.xml backend/pom.xml
COPY backend/platform-common/pom.xml backend/platform-common/pom.xml
COPY backend/platform-persistence/pom.xml backend/platform-persistence/pom.xml
COPY backend/evidence-core/pom.xml backend/evidence-core/pom.xml
COPY backend/case-orchestrator-service/pom.xml backend/case-orchestrator-service/pom.xml
# Warm the dependency cache from the poms alone. Best-effort: a miss only costs a slower build.
RUN mvn -f backend/pom.xml -B -pl case-orchestrator-service -am dependency:go-offline -DskipTests || true

COPY backend/platform-common backend/platform-common
COPY backend/platform-persistence backend/platform-persistence
COPY backend/evidence-core backend/evidence-core
COPY backend/case-orchestrator-service backend/case-orchestrator-service

# -am builds the three module dependencies; -pl limits the reactor to what this image needs.
RUN mvn -f backend/pom.xml -B -DskipTests -pl case-orchestrator-service -am package

# ---- run -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --system pdei && useradd --system --gid pdei --home /app pdei
WORKDIR /app

COPY --from=build /build/backend/case-orchestrator-service/target/case-orchestrator-service-*.jar /app/app.jar
RUN chown -R pdei:pdei /app
USER pdei

EXPOSE 8085

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError" \
    OTEL_SERVICE_NAME=case-orchestrator-service \
    SPRING_PROFILES_ACTIVE=default

# A Temporal worker that cannot reach its namespace is useless, so liveness is the actuator
# health group; the readiness probe is what keeps it out of rotation while it reconnects.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD ["sh", "-c", "wget -qO- http://localhost:8085/actuator/health/readiness || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
