#!/usr/bin/env bash
# PDEI - one-time developer bootstrap.
#
#   ./scripts/bootstrap.sh [--pull] [--skip-toolchain]
#
# Checks the toolchain, creates infra/.env, and pre-pulls the infrastructure images so the
# first `up.sh` is not also a 2 GB download. Safe to re-run.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

PULL=0
SKIP_TOOLCHAIN=0
for arg in "$@"; do
  case "${arg}" in
    --pull) PULL=1 ;;
    --skip-toolchain) SKIP_TOOLCHAIN=1 ;;
    -h|--help)
      sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) die "unknown option ${arg}" ;;
  esac
done

printf '\n%sPDEI bootstrap%s\n\n' "${C_BOLD}" "${C_RESET}"

# --------------------------------------------------------------------------- 1. toolchain
check_version() {
  local label="$1" cmd="$2" want="$3"
  if command -v "${cmd}" >/dev/null 2>&1; then
    local got
    got="$("${@:4}" 2>&1 | head -n1)"
    printf '  %-24s %s%s%s  %s\n' "${label}" "${C_GREEN}" "found" "${C_RESET}" "${C_DIM}${got}${C_RESET}"
    return 0
  fi
  printf '  %-24s %s%s%s  %s\n' "${label}" "${C_YELLOW}" "MISSING" "${C_RESET}" "${C_DIM}need ${want}${C_RESET}"
  return 1
}

if [ "${SKIP_TOOLCHAIN}" -eq 0 ]; then
  info "toolchain"
  missing=0
  check_version "docker"       docker "Docker 24+"        docker --version                 || missing=1
  check_version "docker compose" docker "Compose v2"      docker compose version           || missing=1
  check_version "java"         java   "JDK 21"            java -version                    || missing=1
  check_version "maven"        mvn    "Maven 3.9+"        mvn -v                           || missing=1
  check_version "node"         node   "Node 20+"          node --version                   || missing=1
  check_version "npm"          npm    "npm 10+"           npm --version                    || missing=1
  check_version "python"       python "Python 3.11+"      python --version                 || true
  check_version "uv"           uv     "uv (Python pkg mgr)" uv --version                   || true
  check_version "curl"         curl   "curl"              curl --version                   || missing=1
  echo
  if [ "${missing}" -ne 0 ]; then
    warn "some tools are missing. Docker alone is enough to run the stack;"
    warn "the JDK/Maven/Node/uv are only needed to build a service on the host."
  fi
fi

# --------------------------------------------------------------------------- 2. docker
require_docker
ok "Docker daemon reachable"

# --------------------------------------------------------------------------- 3. env file
ensure_env_file
if [ ! -f "${INFRA_DIR}/docker-compose.override.yml" ]; then
  info "no infra/docker-compose.override.yml (optional)."
  info "  cp infra/docker-compose.override.yml.example infra/docker-compose.override.yml"
  info "  ... when you want to run a service on the host instead of in Docker."
fi

# --------------------------------------------------------------------------- 4. sanity
info "validating compose configuration"
if compose --profile core --profile app --profile obs config --quiet; then
  ok "infra/docker-compose.yml is valid"
else
  die "compose configuration is invalid - fix the errors above before continuing"
fi

# --------------------------------------------------------------------------- 5. images
if [ "${PULL}" -eq 1 ]; then
  info "pulling infrastructure images (this is the slow part; it happens once)"
  compose --profile core --profile obs pull --ignore-buildable
  ok "images pulled"
else
  info "skipping image pull. Run with --pull to fetch them now instead of on first up."
fi

# --------------------------------------------------------------------------- 6. next
cat <<'NEXT'

Bootstrap complete.

  1. Start everything          ./scripts/up.sh
  2. Verify                    ./scripts/smoke-test.sh
  3. Load the demo world       ./scripts/seed-demo.sh

  Control Tower  http://localhost:3000/control-tower
  Grafana        http://localhost:3001   (admin/admin)
  Kafka UI       http://localhost:8090
  Temporal UI    http://localhost:8233
  MinIO Console  http://localhost:9001   (pdei-minio/pdei-minio-secret)

NEXT
