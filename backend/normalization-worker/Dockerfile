# =====================================================================================
# normalization-worker  |  PDEI (Pre-Dispute Evidence Intelligence)
#
# Multi-stage build from the repository's `backend/` directory:
#   docker build -f normalization-worker/Dockerfile -t pdei/normalization-worker:dev .
#
# The build context is `backend/` (not the module directory) because this is a Maven
# reactor: the module cannot be built without its parent pom and its sibling library
# modules.
# =====================================================================================

# --- build ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Poms first: this layer is cached until a dependency actually changes, which is what
# keeps an ordinary code change from re-downloading the whole dependency tree.
COPY pom.xml ./pom.xml
COPY platform-common/pom.xml platform-common/pom.xml
COPY platform-persistence/pom.xml platform-persistence/pom.xml
COPY evidence-core/pom.xml evidence-core/pom.xml
COPY normalization-worker/pom.xml normalization-worker/pom.xml
RUN mvn -q -B -pl normalization-worker -am dependency:go-offline -DskipTests

COPY platform-common/src platform-common/src
COPY platform-persistence/src platform-persistence/src
COPY normalization-worker/src normalization-worker/src
RUN mvn -q -B -pl normalization-worker -am clean package -DskipTests

# --- runtime -------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# Non-root: the worker needs no privileged capability, and a container that cannot write
# outside /tmp is one fewer thing to reason about during an incident.
RUN groupadd --system pdei && useradd --system --gid pdei --home /app pdei
WORKDIR /app

COPY --from=build /workspace/normalization-worker/target/normalization-worker.jar /app/app.jar

USER pdei
EXPOSE 8082

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError" \
    OTEL_SERVICE_NAME=normalization-worker \
    SPRING_PROFILES_ACTIVE=docker

# Kafka consumers must shut down cleanly to commit offsets; exec form so the JVM is PID 1
# and receives SIGTERM directly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD wget -qO- http://localhost:8082/actuator/health/readiness || exit 1
