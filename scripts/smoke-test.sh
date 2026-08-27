#!/usr/bin/env bash
# PDEI - health check every component and print a table.
#
#   ./scripts/smoke-test.sh                 check everything
#   ./scripts/smoke-test.sh --core          infrastructure only
#   ./scripts/smoke-test.sh --app           the 11 services only
#   ./scripts/smoke-test.sh --obs           observability only
#   ./scripts/smoke-test.sh --quiet         table only, no per-probe chatter
#   ./scripts/smoke-test.sh --wait 90       retry for up to 90s before failing a row
#
# Exit code is the number of failed checks (0 = everything healthy), so CI can gate on it.
#
# Ports and health paths come from docs/PLATFORM-CONTRACT.md section 2. If a row here
# disagrees with that table, that table is right.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
set +e   # a failing probe is data, not a reason to stop

CHECK_CORE=0; CHECK_APP=0; CHECK_OBS=0
QUIET=0
WAIT_SECONDS=0

for arg in "$@"; do
  case "${arg}" in
    --core) CHECK_CORE=1 ;;
    --app)  CHECK_APP=1 ;;
    --obs)  CHECK_OBS=1 ;;
    --quiet|-q) QUIET=1 ;;
    --wait) shift; ;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) ;;
  esac
done
# --wait takes a value; re-scan for it (kept separate so order does not matter).
prev=""
for arg in "$@"; do
  [ "${prev}" = "--wait" ] && WAIT_SECONDS="${arg}"
  prev="${arg}"
done

if [ $((CHECK_CORE + CHECK_APP + CHECK_OBS)) -eq 0 ]; then
  CHECK_CORE=1; CHECK_APP=1; CHECK_OBS=1
fi

FAILURES=0
ROWS=()

# ---------------------------------------------------------------------------
# record <group> <name> <endpoint> <status> <detail> <millis>
# ---------------------------------------------------------------------------
record() {
  ROWS+=("$1|$2|$3|$4|$5|$6")
  [ "$4" = "UP" ] || FAILURES=$((FAILURES + 1))
  if [ "${QUIET}" -eq 0 ]; then
    if [ "$4" = "UP" ]; then
      printf '  %s%-3s%s %s\n' "${C_GREEN}" "OK" "${C_RESET}" "$2"
    else
      printf '  %s%-3s%s %s  %s\n' "${C_RED}" "!!" "${C_RESET}" "$2" "${C_DIM}$5${C_RESET}"
    fi
  fi
}

# ---------------------------------------------------------------------------
# check_http <group> <name> <url> [expected-code-regex]
# ---------------------------------------------------------------------------
check_http() {
  local group="$1" name="$2" url="$3" want="${4:-2..}"
  local deadline=$(( $(date +%s) + WAIT_SECONDS ))
  local out code secs ms
  while :; do
    out="$(http_probe "${url}" 5)"
    code="${out%% *}"; secs="${out##* }"
    ms="$(awk -v s="${secs}" 'BEGIN{printf "%.0f", s*1000}' 2>/dev/null || echo "?")"
    if printf '%s' "${code}" | grep -Eq "^${want}$"; then
      record "${group}" "${name}" "${url}" "UP" "HTTP ${code}" "${ms}ms"
      return 0
    fi
    [ "$(date +%s)" -ge "${deadline}" ] && break
    sleep 2
  done
  local detail="HTTP ${code}"
  [ "${code}" = "000" ] && detail="no response (container down or port not published)"
  record "${group}" "${name}" "${url}" "DOWN" "${detail}" "${ms}ms"
  return 1
}

# ---------------------------------------------------------------------------
# check_exec <group> <name> <container> <command...>
# Used where there is no HTTP surface (postgres, redis, kafka, temporal).
# ---------------------------------------------------------------------------
check_exec() {
  local group="$1" name="$2" container="$3"; shift 3
  local start end ms
  start="$(date +%s%N 2>/dev/null || echo 0)"
  if docker exec "${container}" "$@" >/dev/null 2>&1; then
    end="$(date +%s%N 2>/dev/null || echo 0)"
    ms=$(( (end - start) / 1000000 ))
    record "${group}" "${name}" "docker exec ${container}" "UP" "exec ok" "${ms}ms"
    return 0
  fi
  if ! docker ps --format '{{.Names}}' | grep -qx "${container}"; then
    record "${group}" "${name}" "docker exec ${container}" "DOWN" "container not running" "-"
  else
    record "${group}" "${name}" "docker exec ${container}" "DOWN" "command failed inside container" "-"
  fi
  return 1
}

require_docker

printf '\n%sPDEI smoke test%s  %s\n\n' "${C_BOLD}" "${C_RESET}" "${C_DIM}$(date -u +%Y-%m-%dT%H:%M:%SZ)${C_RESET}"

# =========================================================== core infrastructure
if [ "${CHECK_CORE}" -eq 1 ]; then
  [ "${QUIET}" -eq 0 ] && info "core infrastructure"
  check_exec core "postgres"        pdei-postgres  pg_isready -U pdei -d pdei
  check_exec core "redis"           pdei-redis     redis-cli ping
  check_exec core "kafka"           pdei-kafka     /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
  check_http core "kafka-ui"        "http://localhost:${PDEI_KAFKA_UI_HOST_PORT:-8090}/actuator/health"
  check_http core "minio"           "http://localhost:${PDEI_MINIO_HOST_PORT:-9000}/minio/health/live"
  check_http core "minio-console"   "http://localhost:${PDEI_MINIO_CONSOLE_HOST_PORT:-9001}/" "2..|3.."
  check_exec core "temporal"        pdei-temporal-admin-tools temporal operator cluster health --address temporal:7233
  check_http core "temporal-ui"     "http://localhost:${PDEI_TEMPORAL_UI_HOST_PORT:-8233}/" "2..|3.."
fi

# =========================================================== application services
if [ "${CHECK_APP}" -eq 1 ]; then
  [ "${QUIET}" -eq 0 ] && { echo; info "application services"; }
  check_http app "api-gateway-service"        "http://localhost:8080/actuator/health"
  check_http app "ingestion-service"          "http://localhost:8081/actuator/health"
  check_http app "normalization-worker"       "http://localhost:8082/actuator/health"
  check_http app "state-builder-worker"       "http://localhost:8083/actuator/health"
  check_http app "readiness-worker"           "http://localhost:8084/actuator/health"
  check_http app "case-orchestrator-service"  "http://localhost:8085/actuator/health"
  check_http app "document-processor-service" "http://localhost:8086/actuator/health"
  check_http app "audit-service"              "http://localhost:8087/actuator/health"
  check_http app "simulator-service"          "http://localhost:8088/actuator/health"
  check_http app "ai-reasoning-service"       "http://localhost:8000/health"
  check_http app "frontend"                   "http://localhost:3000/api/health"
  # Contract section 8.1: the gateway also exposes its own readiness probe.
  check_http app "gateway /health/ready"      "http://localhost:8080/api/v1/health/ready"
fi

# =========================================================== observability
if [ "${CHECK_OBS}" -eq 1 ]; then
  [ "${QUIET}" -eq 0 ] && { echo; info "observability"; }
  check_http obs "otel-collector"  "http://localhost:13133/"
  check_http obs "prometheus"      "http://localhost:${PDEI_PROMETHEUS_HOST_PORT:-9090}/-/healthy"
  check_http obs "grafana"         "http://localhost:${PDEI_GRAFANA_HOST_PORT:-3001}/api/health"
  check_http obs "loki"            "http://localhost:${PDEI_LOKI_HOST_PORT:-3100}/ready"
  check_http obs "tempo"           "http://localhost:${PDEI_TEMPO_HOST_PORT:-3200}/ready"
fi

# =========================================================== table
echo
printf '%s\n' "+-------+-----------------------------+-----------------------------------------------------+--------+----------+"
printf '| %-5s | %-27s | %-51s | %-6s | %-8s |\n' "GROUP" "COMPONENT" "ENDPOINT" "STATUS" "LATENCY"
printf '%s\n' "+-------+-----------------------------+-----------------------------------------------------+--------+----------+"
for row in "${ROWS[@]}"; do
  IFS='|' read -r g n e s d m <<< "${row}"
  colour="${C_GREEN}"; [ "${s}" = "UP" ] || colour="${C_RED}"
  # Trim the endpoint so the table never wraps.
  if [ "${#e}" -gt 51 ]; then e="${e:0:48}..."; fi
  printf '| %-5s | %-27s | %-51s | %s%-6s%s | %-8s |\n' "${g}" "${n}" "${e}" "${colour}" "${s}" "${C_RESET}" "${m}"
done
printf '%s\n' "+-------+-----------------------------+-----------------------------------------------------+--------+----------+"

echo
if [ "${FAILURES}" -eq 0 ]; then
  ok "all ${#ROWS[@]} checks passed"
else
  err "${FAILURES} of ${#ROWS[@]} checks failed"
  echo
  printf '%sFirst things to try%s\n' "${C_BOLD}" "${C_RESET}"
  printf '  1. Still starting?      docker compose -f infra/docker-compose.yml ps\n'
  printf '  2. Look at one service: ./scripts/logs.sh <service>\n'
  printf '  3. Profile not enabled? ./scripts/up.sh core app obs\n'
  printf '  4. Port already taken?  override it in infra/.env (PDEI_*_HOST_PORT)\n'
  printf '  5. Nuclear option:      ./scripts/reset.sh --yes --up\n'
fi

exit "${FAILURES}"
