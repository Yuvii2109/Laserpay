# =====================================================================================
# simulator-service  |  port 8088
#
# Built from the backend/ reactor root so the three shared modules (platform-common,
# platform-persistence, evidence-core) resolve from the same build:
#
#   docker build -f simulator-service/Dockerfile -t pdei/simulator-service .
#
# run from  Laserpay/backend  as the build context.
#
# Container-level chaos (KILL_WORKER / RESTART_CONSUMER / SLOW_CONSUMER) needs the Docker
# Engine API reachable at PDEI_SIM_DOCKER_HOST and PDEI_SIM_DOCKER_CHAOS=true. That is an
# explicit opt-in: exposing the Docker socket is equivalent to granting root on the host,
# so the image never mounts it and the default configuration never asks for it. Without it
# those three types fall back to a Redis control directive and record which mechanism ran.
# =====================================================================================

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY platform-common/pom.xml platform-common/pom.xml
COPY platform-persistence/pom.xml platform-persistence/pom.xml
COPY evidence-core/pom.xml evidence-core/pom.xml
COPY simulator-service/pom.xml simulator-service/pom.xml
RUN mvn -q -B -pl platform-common,platform-persistence,evidence-core,simulator-service \
        -am dependency:go-offline -DskipTests || true

COPY platform-common/src platform-common/src
COPY platform-persistence/src platform-persistence/src
COPY evidence-core/src evidence-core/src
COPY simulator-service/src simulator-service/src

RUN mvn -q -B -pl simulator-service -am clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is only here for the container HEALTHCHECK below.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system pdei && useradd --system --gid pdei --home /app pdei
WORKDIR /app

COPY --from=build --chown=pdei:pdei /workspace/simulator-service/target/*.jar /app/app.jar

USER pdei
EXPOSE 8088

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    OTEL_SERVICE_NAME=simulator-service \
    SPRING_PROFILES_ACTIVE=default \
    PDEI_SIM_DOCKER_CHAOS=false

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8088/actuator/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
