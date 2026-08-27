#!/usr/bin/env bash
# PDEI - stop the local stack. Volumes are PRESERVED.
#
#   ./scripts/down.sh                stop and remove containers, keep data
#   ./scripts/down.sh --stop-only    just stop them (fastest restart)
#   ./scripts/down.sh --volumes      also delete the data  (prefer reset.sh)
#
# Postgres, Kafka, MinIO, Grafana and the rest keep their named volumes, so the next
# up.sh finds the same world. Use reset.sh when you want a clean slate.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

STOP_ONLY=0
WITH_VOLUMES=0

for arg in "$@"; do
  case "${arg}" in
    --stop-only) STOP_ONLY=1 ;;
    --volumes|-v) WITH_VOLUMES=1 ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument ${arg}" ;;
  esac
done

require_docker

# Every profile is passed so that compose sees the full service set; otherwise services
# belonging to a profile that is not enabled are left running as orphans.
mapfile -t FLAGS < <(profile_flags all)

if [ "${STOP_ONLY}" -eq 1 ]; then
  info "stopping containers (keeping them for a fast restart)"
  compose "${FLAGS[@]}" stop
  ok "stopped. Restart with ./scripts/up.sh"
  exit 0
fi

if [ "${WITH_VOLUMES}" -eq 1 ]; then
  warn "this will DELETE all local data (postgres, kafka, minio, grafana, loki, tempo)"
  compose "${FLAGS[@]}" down --volumes --remove-orphans
  ok "stack down, volumes removed"
  exit 0
fi

info "stopping and removing containers (volumes preserved)"
compose "${FLAGS[@]}" down --remove-orphans
ok "stack down. Data is still on disk - ./scripts/reset.sh wipes it."
