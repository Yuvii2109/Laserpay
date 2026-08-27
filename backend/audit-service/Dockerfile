# =====================================================================================
# audit-service  |  PDEI (Pre-Dispute Evidence Intelligence)
#
# BUILD CONTEXT IS THE REACTOR ROOT, NOT THIS DIRECTORY:
#
#   docker build -f backend/audit-service/Dockerfile -t pdei/audit-service:dev backend
#
# The service depends on platform-common, platform-persistence and evidence-core, which are
# sibling modules; a context rooted at this directory could not see them.
# =====================================================================================

# --- stage 1: dependency cache -------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS deps
WORKDIR /build

COPY pom.xml ./pom.xml
COPY platform-common/pom.xml       platform-common/pom.xml
COPY platform-persistence/pom.xml  platform-persistence/pom.xml
COPY evidence-core/pom.xml         evidence-core/pom.xml
COPY audit-service/pom.xml         audit-service/pom.xml

RUN mvn -B -q -pl audit-service -am dependency:go-offline -DskipTests || true

# --- stage 2: build ------------------------------------------------------------------
FROM deps AS build
WORKDIR /build

COPY platform-common       platform-common
COPY platform-persistence  platform-persistence
COPY evidence-core         evidence-core
COPY audit-service         audit-service

RUN mvn -B -pl audit-service -am clean package -DskipTests

# --- stage 3: runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# Never run a financial service as root - least of all the one that owns the audit trail.
RUN groupadd --system pdei && useradd --system --gid pdei --home /app pdei
WORKDIR /app

COPY --from=build --chown=pdei:pdei /build/audit-service/target/audit-service.jar /app/app.jar

USER pdei
EXPOSE 8087

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC" \
    OTEL_SERVICE_NAME=audit-service \
    SPRING_PROFILES_ACTIVE=docker

# /actuator/health, not /audit/v1/actuator/health: the REST base path is a controller mapping,
# so the contract's health path (section 2) is unaffected.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD ["sh", "-c", "wget -qO- http://localhost:8087/actuator/health | grep -q '\"status\":\"UP\"'"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
