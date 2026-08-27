# =====================================================================================
# PDEI api-gateway-service
#
# Build context is the reactor root (Laserpay/backend), because this module depends on
# platform-common, platform-persistence and evidence-core and Maven needs all four poms:
#
#   docker build -f api-gateway-service/Dockerfile -t pdei/api-gateway-service:dev .
#
# from the `backend` directory.
# =====================================================================================

# --- stage 1: dependency cache -------------------------------------------------------
# The poms are copied on their own first so that `go-offline` is only re-run when a pom
# actually changes. Editing a Java file then reuses the cached dependency layer, which
# takes an incremental build from minutes to seconds.
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build

COPY pom.xml ./
COPY platform-common/pom.xml           platform-common/pom.xml
COPY platform-persistence/pom.xml      platform-persistence/pom.xml
COPY evidence-core/pom.xml             evidence-core/pom.xml
COPY api-gateway-service/pom.xml       api-gateway-service/pom.xml

RUN mvn -B -q -pl api-gateway-service -am \
        -Dmaven.repo.local=/root/.m2/repository \
        dependency:go-offline || true

# --- stage 2: build ------------------------------------------------------------------
FROM deps AS build
WORKDIR /build

COPY platform-common/src           platform-common/src
COPY platform-persistence/src      platform-persistence/src
COPY evidence-core/src             evidence-core/src
COPY api-gateway-service/src       api-gateway-service/src

# Tests run in CI against real containers, not here: a Docker build should be reproducible
# and offline, and Testcontainers is neither inside a build layer.
RUN mvn -B -q -pl api-gateway-service -am \
        -Dmaven.repo.local=/root/.m2/repository \
        -DskipTests package

# Exploded layers so a code change only rewrites the smallest layer of the runtime image.
RUN java -Djarmode=layertools \
        -jar api-gateway-service/target/api-gateway-service.jar extract \
        --destination /build/extracted

# --- stage 3: runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# Non-root, no login shell, no home directory to write into. The process reads config from
# the environment and writes nothing to disk except the temp directory it is given.
RUN groupadd --system --gid 10001 pdei \
 && useradd  --system --uid 10001 --gid pdei --no-create-home --shell /usr/sbin/nologin pdei

WORKDIR /app

COPY --from=build --chown=pdei:pdei /build/extracted/dependencies/          ./
COPY --from=build --chown=pdei:pdei /build/extracted/spring-boot-loader/    ./
COPY --from=build --chown=pdei:pdei /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=pdei:pdei /build/extracted/application/           ./

USER pdei:pdei

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom" \
    OTEL_SERVICE_NAME=api-gateway-service \
    SPRING_PROFILES_ACTIVE=docker

# Uses the actuator liveness probe, not /api/v1/health/ready: the latter reports DEGRADED
# when Redis or Kafka is down, and that must not make Docker restart a gateway that is
# serving every REST route correctly.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD ["/bin/sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/liveness HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q 'UP' <&3"]

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
