#!/usr/bin/env bash
# PDEI - drive the simulator through the demo scenario.
#
#   ./scripts/seed-demo.sh                   the full docs/demo-script.md world
#   ./scripts/seed-demo.sh --small           300 transactions instead of 2000
#   ./scripts/seed-demo.sh --seed 1234       different deterministic seed
#   ./scripts/seed-demo.sh --dispute-rate-bps 2000   20% of transactions disputed
#                                          (basis points, contract 8.5; default 200 = 2%)
#   ./scripts/seed-demo.sh --no-scenarios    generate the world, skip the curated cases
#   ./scripts/seed-demo.sh --no-chaos        skip the chaos injections
#   ./scripts/seed-demo.sh --wait 600        allow longer for the run to finish
#
# This calls the simulator REST API of docs/PLATFORM-CONTRACT.md section 8.5 - it does not
# write to Postgres, Kafka or MinIO directly. Everything you see afterwards was produced by
# the platform processing events, which is the entire claim of the demo.
#
# Seed 4281 is the reproducible demo seed from docs/demo-script.md: two runs of this script
# produce the same transactions, the same readiness scores, and (with PDEI_AI_PROVIDER=mock)
# the same AI classifications.

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

SIM="${PDEI_SIM_URL:-http://localhost:8088/sim/v1}"
API="${PDEI_API_URL:-http://localhost:8080/api/v1}"

SEED=4281
MERCHANTS=3
TRANSACTIONS=2000
DAYS=45
# Basis points, per PLATFORM-CONTRACT section 8.5: 200 = 2%. NOT a fraction - this was
# 0.02, which Jackson truncated into the Integer field as 0, so every seeded world was
# generated with a 0% dispute rate and the whole dispute/case/AI path stayed empty.
DISPUTE_RATE_BPS=200
FAILURE_PROFILE=REALISTIC
RUN_SCENARIOS=1
RUN_CHAOS=1
MAX_WAIT=300

while [ "$#" -gt 0 ]; do
  case "$1" in
    --seed)          SEED="$2"; shift 2 ;;
    --dispute-rate-bps) DISPUTE_RATE_BPS="$2"; shift 2 ;;
    --transactions)  TRANSACTIONS="$2"; shift 2 ;;
    --merchants)     MERCHANTS="$2"; shift 2 ;;
    --days)          DAYS="$2"; shift 2 ;;
    --wait)          MAX_WAIT="$2"; shift 2 ;;
    --small)         TRANSACTIONS=300; DAYS=21; shift ;;
    --no-scenarios)  RUN_SCENARIOS=0; shift ;;
    --no-chaos)      RUN_CHAOS=0; shift ;;
    -h|--help)       sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument $1" ;;
  esac
done

# --------------------------------------------------------------------------- preflight
info "checking the simulator is reachable"
if ! wait_for_http "http://localhost:8088/actuator/health" "simulator-service" 30 2; then
  die "simulator-service is not up. Run ./scripts/up.sh and ./scripts/smoke-test.sh first."
fi

# Never let a transport failure kill the script: an empty body is handled by the caller,
# and a half-seeded world is still worth reporting on.
post_json() {
  local url="$1" body="$2"
  curl -sS -X POST "${url}" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: seed-demo-${SEED}-$(printf '%s' "${body}" | cksum | cut -d' ' -f1)" \
    -d "${body}" 2>/dev/null || true
}

get_json() {
  curl -sS "$1" 2>/dev/null || true
}

# --------------------------------------------------------------------------- 1. the world
printf '\n%s1. Generating the synthetic merchant world%s\n' "${C_BOLD}" "${C_RESET}"
info "seed=${SEED} merchants=${MERCHANTS} transactions=${TRANSACTIONS} days=${DAYS} disputeRateBps=${DISPUTE_RATE_BPS}"

RUN_BODY="$(cat <<JSON
{"seed": ${SEED}, "merchants": ${MERCHANTS}, "transactions": ${TRANSACTIONS}, "days": ${DAYS}, "disputeRateBps": ${DISPUTE_RATE_BPS}, "failureProfile": "${FAILURE_PROFILE}"}
JSON
)"

RUN_RESPONSE="$(post_json "${SIM}/runs" "${RUN_BODY}")"
RUN_ID="$(json_field "${RUN_RESPONSE}" runId)"
[ -n "${RUN_ID}" ] || RUN_ID="$(json_field "${RUN_RESPONSE}" id)"

if [ -z "${RUN_ID}" ]; then
  err "the simulator did not return a runId. Raw response:"
  printf '%s\n' "${RUN_RESPONSE}"
  die "cannot continue without a run id"
fi
ok "run started: ${RUN_ID}"

# --------------------------------------------------------------------------- 2. progress
printf '\n%s2. Waiting for the event stream to drain%s\n' "${C_BOLD}" "${C_RESET}"
info "watch it live in Kafka UI: http://localhost:8090  (topic pdei.raw.events.v1)"

elapsed=0
last_status=""
while [ "${elapsed}" -lt "${MAX_WAIT}" ]; do
  PROGRESS="$(get_json "${SIM}/runs/${RUN_ID}")"
  STATUS="$(json_field "${PROGRESS}" status)"
  EMITTED="$(json_field "${PROGRESS}" eventsEmitted)"
  [ -n "${EMITTED}" ] || EMITTED="$(json_field "${PROGRESS}" emitted)"

  if [ "${STATUS}" != "${last_status}" ] && [ -n "${STATUS}" ]; then
    info "status=${STATUS} events=${EMITTED:-?}"
    last_status="${STATUS}"
  fi

  case "${STATUS}" in
    COMPLETED|COMPLETE|FINISHED|SUCCEEDED) ok "simulation run complete (${EMITTED:-?} events)"; break ;;
    FAILED|ERROR) err "simulation run failed"; printf '%s\n' "${PROGRESS}"; break ;;
  esac

  sleep 5
  elapsed=$((elapsed + 5))
  if [ $((elapsed % 30)) -eq 0 ]; then
    info "still running (${elapsed}s elapsed, events=${EMITTED:-?})"
  fi
done

if [ "${elapsed}" -ge "${MAX_WAIT}" ]; then
  warn "run did not report completion within ${MAX_WAIT}s - continuing anyway."
  warn "the pipeline is asynchronous, so this is often just a slow consumer, not a failure."
fi

# Give the downstream pipeline a moment: raw -> canonical -> state -> readiness.
info "letting the pipeline settle (normalization -> state -> readiness)"
sleep 10

# --------------------------------------------------------------------------- 3. scenarios
if [ "${RUN_SCENARIOS}" -eq 1 ]; then
  printf '\n%s3. Running the curated demo scenarios%s\n' "${C_BOLD}" "${C_RESET}"

  # These three are the spine of docs/demo-script.md steps 4-6:
  #   clean-delivery-defendable   -> AI is NOT invoked (deterministic short-circuit)
  #   contradictory-delivery-dates-> AI IS invoked, claims cited to evidence ids
  #   missing-delivery-proof      -> safety gate DENIES a confident model (rule 7)
  for key in clean-delivery-defendable contradictory-delivery-dates missing-delivery-proof; do
    info "scenario: ${key}"
    RESP="$(post_json "${SIM}/scenarios/${key}/run" '{}')"
    CASE_ID="$(json_field "${RESP}" caseId)"
    DISPUTE_ID="$(json_field "${RESP}" disputeId)"
    if [ -n "${CASE_ID}" ]; then
      ok "  ${key} -> case ${CASE_ID}"
      printf '        Case X-Ray: http://localhost:3000/cases/%s\n' "${CASE_ID}"
    elif [ -n "${DISPUTE_ID}" ]; then
      ok "  ${key} -> dispute ${DISPUTE_ID}"
    else
      warn "  ${key} returned no case id; response was: ${RESP}"
    fi
    sleep 3
  done
fi

# --------------------------------------------------------------------------- 4. chaos
if [ "${RUN_CHAOS}" -eq 1 ]; then
  printf '\n%s4. Injecting the correctness-proving chaos%s\n' "${C_BOLD}" "${C_RESET}"

  # Each of these maps to a property in docs/demo-script.md step 7. They are safe to run
  # on the seeded world: none of them destroys data the later steps rely on.
  info "50x DUPLICATE_EVENT   - readiness must NOT move (idempotency)"
  post_json "${SIM}/chaos" '{"type":"DUPLICATE_EVENT","count":50,"target":{}}' >/dev/null

  info "20x OUT_OF_ORDER_EVENT - state must not regress"
  post_json "${SIM}/chaos" '{"type":"OUT_OF_ORDER_EVENT","count":20,"target":{}}' >/dev/null

  info "10x DELAYED_EVENT      - occurredAt/observedAt gap stays visible"
  post_json "${SIM}/chaos" '{"type":"DELAYED_EVENT","count":10,"delayMs":45000,"target":{}}' >/dev/null

  info "1x  CORRUPT_EVIDENCE_HASH - evidence flips to INVALIDATED, readiness drops"
  post_json "${SIM}/chaos" '{"type":"CORRUPT_EVIDENCE_HASH","count":1,"target":{}}' >/dev/null

  ok "chaos injected - see Grafana 'PDEI / Event Pipeline' for the duplicate counter"
fi

# --------------------------------------------------------------------------- 5. summary
printf '\n%sDemo world ready%s\n\n' "${C_BOLD}${C_GREEN}" "${C_RESET}"

FUNNEL="$(get_json "${API}/metrics/funnel")"
if [ -n "${FUNNEL}" ]; then
  printf '  Funnel (GET /api/v1/metrics/funnel):\n    %s\n\n' "${FUNNEL}"
fi

cat <<SUMMARY
  Run id           ${RUN_ID}
  Seed             ${SEED}   (re-run with the same seed for identical results)

  Control Tower    http://localhost:3000/control-tower
  Case queue       http://localhost:3000/cases
  At-risk gaps     http://localhost:3000/gaps
  Simulation       http://localhost:3000/simulation
  Grafana          http://localhost:3001/d/pdei-event-pipeline
  Temporal UI      http://localhost:8233
  Kafka UI         http://localhost:8090

  Walk the demo:   docs/demo-script.md
SUMMARY
