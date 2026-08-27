#!/usr/bin/env bash
# PDEI - start the local stack.
#
#   ./scripts/up.sh                  core + app + obs (everything)
#   ./scripts/up.sh core             infrastructure only
#   ./scripts/up.sh core app         infrastructure + services, no observability
#   ./scripts/up.sh --build          force a rebuild of the repo images
#   ./scripts/up.sh --no-wait        return as soon as containers are created
#
# Startup order is enforced by depends_on/service_healthy in docker-compose.yml, not by
# sleeps here: postgres+redis+kafka -> kafka-init/minio-init -> temporal -> services.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

PROFILES=()
BUILD_FLAG=()
WAIT=1

for arg in "$@"; do
  case "${arg}" in
    core|app|obs|all) PROFILES+=("${arg}") ;;
    --build)   BUILD_FLAG=(--build) ;;
    --no-wait) WAIT=0 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument ${arg}" ;;
  esac
done

if [ "${#PROFILES[@]}" -eq 0 ]; then
  PROFILES=(core app obs)
fi

require_docker
ensure_env_file

mapfile -t FLAGS < <(profile_flags "${PROFILES[@]}")

info "starting profiles: ${PROFILES[*]}"
compose "${FLAGS[@]}" up -d --remove-orphans "${BUILD_FLAG[@]}"

if [ "${WAIT}" -eq 0 ]; then
  ok "containers created (not waiting for health)"
  exit 0
fi

# ---------------------------------------------------------------------------
# Wait for the pieces a human is about to click on. The compose healthchecks
# already gate inter-service startup; this loop exists so the script does not
# hand back a prompt before the UIs actually answer.
# ---------------------------------------------------------------------------
contains() { local n="$1"; shift; for e in "$@"; do [ "$e" = "$n" ] && return 0; done; return 1; }

if contains core "${PROFILES[@]}" || contains all "${PROFILES[@]}"; then
  wait_for_http "http://localhost:${PDEI_KAFKA_UI_HOST_PORT:-8090}/actuator/health" "Kafka UI" 60 2 || true
  wait_for_http "http://localhost:${PDEI_MINIO_HOST_PORT:-9000}/minio/health/live"  "MinIO"    60 2 || true
  wait_for_http "http://localhost:${PDEI_TEMPORAL_UI_HOST_PORT:-8233}/"             "Temporal UI" 90 2 || true
fi

if contains app "${PROFILES[@]}" || contains all "${PROFILES[@]}"; then
  wait_for_http "http://localhost:8080/actuator/health" "api-gateway-service" 120 2 || true
  wait_for_http "http://localhost:8000/health"          "ai-reasoning-service" 90 2 || true
  wait_for_http "http://localhost:3000/api/health"      "frontend"            120 2 || true
fi

if contains obs "${PROFILES[@]}" || contains all "${PROFILES[@]}"; then
  wait_for_http "http://localhost:${PDEI_GRAFANA_HOST_PORT:-3001}/api/health" "Grafana" 90 2 || true
fi

echo
compose "${FLAGS[@]}" ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}' || true
echo
ok "stack is up. Next: ./scripts/smoke-test.sh"
