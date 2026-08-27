#!/usr/bin/env bash
# PDEI - destroy the local environment and start from nothing.
#
#   ./scripts/reset.sh              interactive confirmation
#   ./scripts/reset.sh --yes        no prompt (CI / scripted)
#   ./scripts/reset.sh --yes --up   wipe, then bring the stack back up
#
# What this deletes:
#   * every pdei container
#   * every named volume: postgres (incl. the Temporal databases), kafka log dirs,
#     minio buckets and their object versions, prometheus TSDB, grafana state,
#     loki chunks, tempo blocks, promtail positions
#   * the pdei-net network
#
# What this does NOT delete: infra/.env, your override file, built images, or anything
# in your source tree.
#
# Reach for this when: Kafka refuses to start after a CLUSTER_ID change, Flyway is
# wedged on a partially-applied migration, or you want the demo seed to be the only data
# in the system.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

ASSUME_YES=0
BRING_UP=0
PRUNE_IMAGES=0

for arg in "$@"; do
  case "${arg}" in
    --yes|-y)  ASSUME_YES=1 ;;
    --up)      BRING_UP=1 ;;
    --images)  PRUNE_IMAGES=1 ;;
    -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument ${arg}" ;;
  esac
done

require_docker

if [ "${ASSUME_YES}" -eq 0 ]; then
  printf '%sThis deletes ALL local PDEI data (postgres, kafka, minio, metrics, logs, traces).%s\n' "${C_YELLOW}" "${C_RESET}"
  printf 'Type %sreset%s to confirm: ' "${C_BOLD}" "${C_RESET}"
  read -r answer
  [ "${answer}" = "reset" ] || die "aborted - nothing was deleted"
fi

mapfile -t FLAGS < <(profile_flags all)

info "removing containers and volumes"
compose "${FLAGS[@]}" down --volumes --remove-orphans --timeout 20 || true

# down --volumes only removes volumes compose knows about; the named volumes are declared
# with explicit `name:` keys, so remove any survivor by name too.
VOLUMES=(
  pdei-postgres-data
  pdei-redis-data
  pdei-kafka-data
  pdei-minio-data
  pdei-prometheus-data
  pdei-grafana-data
  pdei-loki-data
  pdei-tempo-data
  pdei-promtail-data
)
for v in "${VOLUMES[@]}"; do
  if docker volume inspect "${v}" >/dev/null 2>&1; then
    docker volume rm -f "${v}" >/dev/null && info "removed volume ${v}"
  fi
done

if docker network inspect pdei-net >/dev/null 2>&1; then
  docker network rm pdei-net >/dev/null 2>&1 && info "removed network pdei-net" || warn "pdei-net still in use; it will be recreated on next up"
fi

if [ "${PRUNE_IMAGES}" -eq 1 ]; then
  info "removing images built from this repo"
  docker images --format '{{.Repository}}:{{.Tag}}' | grep '^pdei/' | xargs -r docker rmi -f >/dev/null 2>&1 || true
  ok "repo images removed - next up.sh will rebuild them"
fi

ok "reset complete - the environment is empty"

if [ "${BRING_UP}" -eq 1 ]; then
  info "bringing the stack back up"
  "${REPO_ROOT}/scripts/up.sh"
else
  info "next: ./scripts/up.sh    then    ./scripts/seed-demo.sh"
fi
