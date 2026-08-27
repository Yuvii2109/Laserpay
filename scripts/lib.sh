#!/usr/bin/env bash
# PDEI - shared shell helpers. Sourced by every script in this directory.
#
#   source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
#
# Nothing here executes on its own; it only defines functions and the two paths every
# script needs (REPO_ROOT and INFRA_DIR).

set -euo pipefail

# --------------------------------------------------------------------------- paths
_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${_LIB_DIR}/.." && pwd)"
INFRA_DIR="${REPO_ROOT}/infra"
export REPO_ROOT INFRA_DIR

# --------------------------------------------------------------------------- colour
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_RESET=$'\033[0m'; C_DIM=$'\033[2m'; C_BOLD=$'\033[1m'
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'
else
  C_RESET=''; C_DIM=''; C_BOLD=''; C_RED=''; C_GREEN=''; C_YELLOW=''; C_BLUE=''
fi
# Exported because every consumer of these is a *sourcing* script (bootstrap, reset,
# seed-demo, smoke-test). shellcheck cannot see cross-file use, so without the export
# C_DIM and C_BOLD read as dead assignments (SC2034).
export C_RESET C_DIM C_BOLD C_RED C_GREEN C_YELLOW C_BLUE

info()  { printf '%s[pdei]%s %s\n' "${C_BLUE}"   "${C_RESET}" "$*"; }
ok()    { printf '%s[pdei]%s %s\n' "${C_GREEN}"  "${C_RESET}" "$*"; }
warn()  { printf '%s[pdei]%s %s\n' "${C_YELLOW}" "${C_RESET}" "$*" >&2; }
err()   { printf '%s[pdei]%s %s\n' "${C_RED}"    "${C_RESET}" "$*" >&2; }
die()   { err "$*"; exit 1; }

# --------------------------------------------------------------------------- docker
# Compose is always invoked from infra/ so that docker-compose.yml,
# docker-compose.override.yml and .env are all picked up implicitly.
compose() {
  ( cd "${INFRA_DIR}" && docker compose "$@" )
}

require_docker() {
  command -v docker >/dev/null 2>&1 || die "docker is not on PATH. Install Docker Desktop (Windows/macOS) or docker-ce (Linux)."
  docker compose version >/dev/null 2>&1 || die "docker compose v2 is not available. 'docker-compose' v1 is not supported."
  docker info >/dev/null 2>&1 || die "the Docker daemon is not reachable. Start Docker Desktop and try again."
}

ensure_env_file() {
  if [ ! -f "${INFRA_DIR}/.env" ]; then
    cp "${INFRA_DIR}/.env.example" "${INFRA_DIR}/.env"
    info "created infra/.env from .env.example"
    load_env_file
  fi
}

# Load infra/.env into this shell so the scripts poll the same HOST ports the compose file
# publishes. Compose reads the file itself; this is only so PDEI_*_HOST_PORT overrides are
# visible to wait_for_http and the smoke test. Comments and blank lines are skipped, and
# nothing is evaluated - values are taken verbatim.
load_env_file() {
  local file="${INFRA_DIR}/.env"
  [ -f "${file}" ] || return 0
  local line key value
  while IFS= read -r line || [ -n "${line}" ]; do
    case "${line}" in
      ''|'#'*) continue ;;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    case "${key}" in
      *[!A-Za-z0-9_]*|'') continue ;;
    esac
    # Do not clobber a value the caller exported deliberately.
    if [ -z "${!key:-}" ]; then
      export "${key}=${value}"
    fi
  done < "${file}"
}

load_env_file

# --------------------------------------------------------------------------- profiles
# Translate a friendly profile list into compose flags.
#   profile_flags core app   ->  --profile core --profile app
profile_flags() {
  local out=()
  for p in "$@"; do
    case "$p" in
      core|app|obs) out+=(--profile "$p") ;;
      all) out+=(--profile core --profile app --profile obs) ;;
      *) die "unknown profile '$p' (expected: core, app, obs, all)" ;;
    esac
  done
  printf '%s\n' "${out[@]}"
}

# --------------------------------------------------------------------------- json
# Minimal field extraction so the scripts do not depend on jq being installed.
# Falls back to a regex when no Python is available.
json_field() {
  local json="$1" field="$2"
  local py
  py="$(command -v python3 || command -v python || true)"
  if [ -n "${py}" ]; then
    printf '%s' "${json}" | "${py}" -c "
import json,sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
v = d.get('${field}') if isinstance(d, dict) else None
if v is not None:
    print(v)
" 2>/dev/null
  else
    printf '%s' "${json}" | sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -n1
  fi
}

# --------------------------------------------------------------------------- http
# curl wrapper that never fails the script; echoes "<http_code> <seconds>".
http_probe() {
  local url="$1" timeout="${2:-5}"
  curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time "${timeout}" "${url}" 2>/dev/null || printf '000 0.000'
}

wait_for_http() {
  local url="$1" label="$2" attempts="${3:-60}" sleep_s="${4:-2}"
  local code
  info "waiting for ${label} at ${url} ..."
  while [ "${attempts}" -gt 0 ]; do
    code="$(http_probe "${url}" 3 | cut -d' ' -f1)"
    case "${code}" in
      2*|3*) ok "${label} is up"; return 0 ;;
    esac
    attempts=$((attempts - 1))
    sleep "${sleep_s}"
  done
  warn "${label} did not become healthy in time (last HTTP ${code})"
  return 1
}
