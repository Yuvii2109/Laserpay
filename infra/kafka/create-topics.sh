#!/usr/bin/env bash
# PDEI - Kafka topic bootstrap.
#
# Creates the eight topics of docs/PLATFORM-CONTRACT.md section 4 with their exact names
# and partition counts. Runs as the one-shot "kafka-init" compose service after the broker
# reports healthy; also runnable by hand:
#
#   docker compose --profile core run --rm kafka-init
#   PDEI_KAFKA_BOOTSTRAP=localhost:29092 KAFKA_BIN=/path/to/kafka/bin ./create-topics.sh
#
# Idempotent: existing topics are left alone except for a config re-apply, so re-running
# after editing retention here is the supported way to change it.

set -euo pipefail

BOOTSTRAP="${PDEI_KAFKA_BOOTSTRAP:-kafka:9092}"
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"
REPLICATION="${PDEI_KAFKA_REPLICATION_FACTOR:-1}"   # single broker in dev
TOPICS_CMD="${KAFKA_BIN}/kafka-topics.sh"
CONFIGS_CMD="${KAFKA_BIN}/kafka-configs.sh"

log() { printf '[kafka-init] %s\n' "$*"; }

# --------------------------------------------------------------------------- wait
wait_for_broker() {
  local attempts=60
  log "waiting for broker at ${BOOTSTRAP} ..."
  until "${TOPICS_CMD}" --bootstrap-server "${BOOTSTRAP}" --list >/dev/null 2>&1; do
    attempts=$((attempts - 1))
    if [ "${attempts}" -le 0 ]; then
      log "ERROR: broker at ${BOOTSTRAP} never became reachable"
      exit 1
    fi
    sleep 2
  done
  log "broker reachable"
}

# --------------------------------------------------------------------------- create
# usage: ensure_topic <name> <partitions> <config=value>...
ensure_topic() {
  local topic="$1"; shift
  local partitions="$1"; shift
  local configs=("$@")

  local config_args=()
  for cfg in "${configs[@]}"; do
    config_args+=(--config "${cfg}")
  done

  if "${TOPICS_CMD}" --bootstrap-server "${BOOTSTRAP}" --describe --topic "${topic}" >/dev/null 2>&1; then
    log "topic ${topic} exists - re-applying config"
    # One kafka-configs call per entry. A comma-joined --add-config would mis-parse
    # list-valued settings such as cleanup.policy=compact,delete, so values containing a
    # comma are wrapped in the bracket syntax ConfigCommand expects.
    local key value
    for cfg in "${configs[@]}"; do
      key="${cfg%%=*}"
      value="${cfg#*=}"
      case "${value}" in
        *,*) value="[${value}]" ;;
      esac
      "${CONFIGS_CMD}" --bootstrap-server "${BOOTSTRAP}" \
        --entity-type topics --entity-name "${topic}" \
        --alter --add-config "${key}=${value}" >/dev/null
    done
  else
    log "creating ${topic} (partitions=${partitions}, rf=${REPLICATION})"
    "${TOPICS_CMD}" --bootstrap-server "${BOOTSTRAP}" --create \
      --topic "${topic}" \
      --partitions "${partitions}" \
      --replication-factor "${REPLICATION}" \
      "${config_args[@]}" >/dev/null
  fi
}

main() {
  wait_for_broker

  # ---------------------------------------------------------------------
  # Retention rationale
  #
  # Event-sourced topics use cleanup.policy=delete. Log compaction would keep only the
  # latest record per key, and the key here is merchantId:aggregateId (contract section 4)
  # - compacting it would silently destroy the event history that replay and audit depend
  # on. Retention windows instead widen with how far downstream and how legally
  # interesting the topic is.
  #
  # The one exception is pdei.readiness.events.v1: a readiness event is a SNAPSHOT of a
  # transaction's score, not an increment, so the newest record per key is genuinely
  # sufficient to rebuild current readiness. It gets compact+delete: compaction keeps the
  # latest snapshot per transaction indefinitely, while the delete half bounds the tail.
  # Full readiness history still lives in Postgres (readiness_snapshots) and in the audit
  # chain, so nothing is lost.
  # ---------------------------------------------------------------------

  local WEEK=604800000        # 7d
  local MONTH=2592000000      # 30d
  local QUARTER=7776000000    # 90d
  local YEAR=31536000000      # 365d
  local FORTNIGHT=1209600000  # 14d

  # topic                        partitions  configs
  ensure_topic pdei.raw.events.v1        12 \
    "cleanup.policy=delete" "retention.ms=${WEEK}" "segment.ms=3600000" \
    "compression.type=lz4" "max.message.bytes=2097152" "min.insync.replicas=1"

  ensure_topic pdei.canonical.events.v1  12 \
    "cleanup.policy=delete" "retention.ms=${MONTH}" "segment.ms=3600000" \
    "compression.type=lz4" "max.message.bytes=2097152" "min.insync.replicas=1"

  ensure_topic pdei.evidence.events.v1   12 \
    "cleanup.policy=delete" "retention.ms=${MONTH}" "segment.ms=3600000" \
    "compression.type=lz4" "min.insync.replicas=1"

  # compacted: latest readiness snapshot per merchantId:transactionId is authoritative
  ensure_topic pdei.readiness.events.v1  12 \
    "cleanup.policy=compact,delete" "retention.ms=${FORTNIGHT}" \
    "min.cleanable.dirty.ratio=0.1" "segment.ms=3600000" "delete.retention.ms=86400000" \
    "compression.type=lz4" "min.insync.replicas=1"

  ensure_topic pdei.dispute.events.v1    12 \
    "cleanup.policy=delete" "retention.ms=${QUARTER}" "segment.ms=3600000" \
    "compression.type=lz4" "min.insync.replicas=1"

  ensure_topic pdei.case.events.v1       12 \
    "cleanup.policy=delete" "retention.ms=${QUARTER}" "segment.ms=3600000" \
    "compression.type=lz4" "min.insync.replicas=1"

  ensure_topic pdei.audit.events.v1       6 \
    "cleanup.policy=delete" "retention.ms=${YEAR}" "segment.ms=86400000" \
    "compression.type=lz4" "min.insync.replicas=1"

  ensure_topic pdei.dlq.v1                6 \
    "cleanup.policy=delete" "retention.ms=${FORTNIGHT}" "segment.ms=86400000" \
    "compression.type=lz4" "max.message.bytes=4194304" "min.insync.replicas=1"

  log "topics now present:"
  "${TOPICS_CMD}" --bootstrap-server "${BOOTSTRAP}" --list | sed 's/^/[kafka-init]   /'
  log "done"
}

main "$@"
