#!/usr/bin/env bash
# Create a small book of card charges and receipts through the public API.
# Does not reconcile. scripts/load/reconcile-seed.js matches this book.
# Requires curl and jq. The app must already be up.
set -euo pipefail

need() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing $1." >&2
    exit 1
  fi
}
need curl
need jq

# GNU date accepts -d. macOS date does not, and uses -v instead.
plus_days() {
  local start="$1" days="$2" plus
  if plus="$(date -u -d "${start} + ${days} days" +%F 2>/dev/null)"; then
    printf '%s\n' "${plus}"
  else
    date -u -j -f "%Y-%m-%d" "${start}" -v+"${days}"d +%F
  fi
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="${BASE_URL:-http://127.0.0.1:8080}"
API_KEY="${RECON_API_KEY:-local-dev-key}"
OUT="${ROOT}/.load/seed.json"
RUN="${SEED_RUN:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"

mkdir -p "${ROOT}/.load"

health="$(curl -fsS "${BASE}/actuator/health")"
printf '%s' "${health}" | jq -e '.status == "UP"' >/dev/null

post() {
  local path="$1" key="$2" body="$3"
  curl -fsS \
    -H 'Content-Type: application/json' \
    -H "X-API-Key: ${API_KEY}" \
    -H "Idempotency-Key: ${key}" \
    -d "${body}" \
    "${BASE}${path}"
}

# Eight cardholders. Five charges each, on dates five days apart so one
# owner's receipts are outside the three-day match window of the others.
owners=(
  ada.ng
  ben.cho
  chi.okonkwo
  dev.patel
  eva.schmidt
  farid.hassan
  grace.kim
  hiro.sato
)

# case, charge merchant, receipt merchant or "-", charge amount, receipt amount or "-", tax or "-"
# "Dwayne" / "Duane" is the pair MatchingRulesTest pins: with equal tax the
# score is at least 0.85 and under 0.95, so the row stays MATCHED.
cases=(
  'perfect|Woolworths|Woolworths|54.80|54.80|4.98|APPROVED'
  'suggested|Cafe|Cafe|8.00|8.00|-|SUGGESTED'
  'close-name|Dwayne|Duane|6.50|6.50|0.59|MATCHED'
  'amount-mismatch|Officeworks|Officeworks|120.00|128.00|10.91|EXCEPTION'
  'no-receipt|Uber|-|24.30|-|2.21|EXCEPTION'
)

charges='[]'
receipt_count=0
bill_count=0

for owner_index in "${!owners[@]}"; do
  # The run id is part of the owner, so a second seed is a separate book.
  # The same merchant, amount, and date do not share receipts with the first run.
  owner="${owners[$owner_index]}-${RUN}"
  for case_index in "${!cases[@]}"; do
    IFS='|' read -r name charge_merchant receipt_merchant charge_amount receipt_amount tax expect <<<"${cases[$case_index]}"
    offset=$((owner_index * 40 + case_index * 5))
    day="$(plus_days 2026-01-06 "${offset}")"
    key_base="seed-${RUN}-${owner}-${name}"

    if [[ "${tax}" == "-" ]]; then
      charge_body="$(jq -nc \
        --arg owner "${owner}" \
        --arg merchant "${charge_merchant}" \
        --arg amount "${charge_amount}" \
        --arg day "${day}" \
        '{cardOwnerId:$owner, merchant:$merchant, amount:$amount, currency:"AUD", txnDate:$day}')"
    else
      charge_body="$(jq -nc \
        --arg owner "${owner}" \
        --arg merchant "${charge_merchant}" \
        --arg amount "${charge_amount}" \
        --arg day "${day}" \
        --arg tax "${tax}" \
        '{cardOwnerId:$owner, merchant:$merchant, amount:$amount, currency:"AUD", txnDate:$day, tax:$tax}')"
    fi
    charge_json="$(post /transactions "${key_base}-txn" "${charge_body}")"
    charge_id="$(printf '%s' "${charge_json}" | jq -r '.id')"
    charges="$(jq -c \
      --arg id "${charge_id}" \
      --arg owner "${owner}" \
      --arg name "${name}" \
      --arg expect "${expect}" \
      '. + [{id:$id, owner:$owner, case:$name, expect:$expect}]' <<<"${charges}")"

    if [[ "${receipt_merchant}" != "-" ]]; then
      if [[ "${tax}" == "-" ]]; then
        receipt_body="$(jq -nc \
          --arg owner "${owner}" \
          --arg merchant "${receipt_merchant}" \
          --arg amount "${receipt_amount}" \
          --arg day "${day}" \
          '{kind:"RECEIPT", cardOwnerId:$owner, merchant:$merchant, amount:$amount, currency:"AUD", docDate:$day}')"
      else
        # Same tax on both sides, except the amount mismatch: that receipt
        # is 128.00 with tax 11.64, against a 120.00 charge with tax 10.91.
        receipt_tax="${tax}"
        if [[ "${name}" == "amount-mismatch" ]]; then
          receipt_tax="11.64"
        fi
        receipt_body="$(jq -nc \
          --arg owner "${owner}" \
          --arg merchant "${receipt_merchant}" \
          --arg amount "${receipt_amount}" \
          --arg day "${day}" \
          --arg tax "${receipt_tax}" \
          '{kind:"RECEIPT", cardOwnerId:$owner, merchant:$merchant, amount:$amount, currency:"AUD", docDate:$day, tax:$tax}')"
      fi
      post /documents "${key_base}-doc" "${receipt_body}" >/dev/null
      receipt_count=$((receipt_count + 1))
    fi
  done

  # A bill is stored and is not a match candidate. Matching selects kind RECEIPT.
  bill_day="$(plus_days 2026-01-06 "$((owner_index * 40))")"
  bill_body="$(jq -nc \
    --arg owner "${owner}" \
    --arg day "${bill_day}" \
    '{kind:"BILL", cardOwnerId:$owner, merchant:"Telstra", amount:"89.00", currency:"AUD", docDate:$day, tax:"8.09"}')"
  post /documents "seed-${RUN}-${owner}-bill" "${bill_body}" >/dev/null
  bill_count=$((bill_count + 1))
done

jq -n \
  --arg base "${BASE}" \
  --arg run "${RUN}" \
  --argjson charges "${charges}" \
  --argjson receipts "${receipt_count}" \
  --argjson bills "${bill_count}" \
  '{baseUrl:$base, run:$run, receipts:$receipts, bills:$bills, charges:$charges}' >"${OUT}"

charge_count="$(jq 'length' <<<"${charges}")"
printf 'seeded %s charges, %s receipts, %s bills -> %s\n' \
  "${charge_count}" "${receipt_count}" "${bill_count}" "${OUT}"
