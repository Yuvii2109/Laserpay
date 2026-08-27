# =====================================================================================
# document-processor-service  |  port 8086
#
# Built from the backend/ reactor root so the three shared modules (platform-common,
# platform-persistence, evidence-core) resolve from the same build:
#
#   docker build -f document-processor-service/Dockerfile -t pdei/document-processor-service .
#
# run from  Laserpay/backend  as the build context.
# =====================================================================================

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Poms first: this layer is cached until a dependency actually changes, which is what keeps
# an edit to one Java file from re-downloading the world.
COPY pom.xml ./pom.xml
COPY platform-common/pom.xml platform-common/pom.xml
COPY platform-persistence/pom.xml platform-persistence/pom.xml
COPY evidence-core/pom.xml evidence-core/pom.xml
COPY document-processor-service/pom.xml document-processor-service/pom.xml
RUN mvn -q -B -pl platform-common,platform-persistence,evidence-core,document-processor-service \
        -am dependency:go-offline -DskipTests || true

COPY platform-common/src platform-common/src
COPY platform-persistence/src platform-persistence/src
COPY evidence-core/src evidence-core/src
COPY document-processor-service/src document-processor-service/src

RUN mvn -q -B -pl document-processor-service -am clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl is only here for the container HEALTHCHECK below.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Never run a parser of untrusted documents as root.
RUN groupadd --system pdei && useradd --system --gid pdei --home /app pdei
WORKDIR /app

COPY --from=build --chown=pdei:pdei /workspace/document-processor-service/target/*.jar /app/app.jar

USER pdei
EXPOSE 8086

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    OTEL_SERVICE_NAME=document-processor-service \
    SPRING_PROFILES_ACTIVE=default

# Container-level liveness; compose/k8s probes hit /actuator/health directly.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8086/actuator/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
