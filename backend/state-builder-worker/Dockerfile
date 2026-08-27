# =====================================================================================
# state-builder-worker  |  PDEI (Pre-Dispute Evidence Intelligence)
#
# Multi-stage build from the repository's `backend/` directory:
#   docker build -f state-builder-worker/Dockerfile -t pdei/state-builder-worker:dev .
#
# The build context is `backend/` because this is a Maven reactor: the module needs its
# parent pom plus platform-common, platform-persistence and evidence-core.
# =====================================================================================

# --- build ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY platform-common/pom.xml platform-common/pom.xml
COPY platform-persistence/pom.xml platform-persistence/pom.xml
COPY evidence-core/pom.xml evidence-core/pom.xml
COPY state-builder-worker/pom.xml state-builder-worker/pom.xml
RUN mvn -q -B -pl state-builder-worker -am dependency:go-offline -DskipTests

COPY platform-common/src platform-common/src
COPY platform-persistence/src platform-persistence/src
COPY evidence-core/src evidence-core/src
COPY state-builder-worker/src state-builder-worker/src
RUN mvn -q -B -pl state-builder-worker -am clean package -DskipTests

# --- runtime -------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN groupadd --system pdei && useradd --system --gid pdei --home /app pdei
WORKDIR /app

COPY --from=build /workspace/state-builder-worker/target/state-builder-worker.jar /app/app.jar

USER pdei
EXPOSE 8083

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError" \
    OTEL_SERVICE_NAME=state-builder-worker \
    SPRING_PROFILES_ACTIVE=docker

# Kafka consumers must shut down cleanly to commit offsets; exec form so the JVM is PID 1
# and receives SIGTERM directly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- http://localhost:8083/actuator/health/readiness || exit 1
