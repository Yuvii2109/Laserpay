#!/usr/bin/env bash
# PDEI - tail container logs.
#
#   ./scripts/logs.sh                       follow every service
#   ./scripts/logs.sh readiness-worker      follow one
#   ./scripts/logs.sh api-gateway-service ingestion-service
#   ./scripts/logs.sh --pipeline            the four event-pipeline workers together
#   ./scripts/logs.sh --errors              every service, filtered to ERROR/exception
#   ./scripts/logs.sh --tail 500 audit-service
#   ./scripts/logs.sh --no-follow kafka
#
# For anything structured (by traceId, merchantId, correlationId) use Grafana Explore
# against the Loki datasource instead - promtail is already parsing those fields into
# labels and structured metadata.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

TAIL=200
FOLLOW=1
FILTER=""
SERVICES=()

PIPELINE_SERVICES=(ingestion-service normalization-worker state-builder-worker readiness-worker)

while [ "$#" -gt 0 ]; do
  case "$1" in
    --tail)      TAIL="$2"; shift 2 ;;
    --no-follow) FOLLOW=0; shift ;;
    --pipeline)  SERVICES+=("${PIPELINE_SERVICES[@]}"); shift ;;
    --errors)    FILTER='(ERROR|Exception|error|FATAL|panic)'; shift ;;
    -h|--help)   sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)          die "unknown option $1" ;;
    *)           SERVICES+=("$1"); shift ;;
  esac
done

require_docker
mapfile -t FLAGS < <(profile_flags all)

ARGS=(logs --tail "${TAIL}")
[ "${FOLLOW}" -eq 1 ] && ARGS+=(--follow)

if [ -n "${FILTER}" ]; then
  info "filtering on /${FILTER}/"
  compose "${FLAGS[@]}" "${ARGS[@]}" "${SERVICES[@]}" 2>&1 | grep -E --color=auto "${FILTER}"
else
  compose "${FLAGS[@]}" "${ARGS[@]}" "${SERVICES[@]}"
fi
