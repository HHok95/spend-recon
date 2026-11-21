# Approve and reject

A person approves a suggested match, sees a stale version refused, rejects another match so its receipt can be used again, and reads the audit history. Approving also writes an outbox row that the poller publishes.

## Sub-features

- `decisions.approve` — `POST /matches/{id}/approve` moves a `SUGGESTED` match to `APPROVED` at the next version and writes one audit row and one `MatchConfirmed` outbox row.
- `decisions.replay` — the same `Idempotency-Key` and body return the same response and do not write a second row.
- `decisions.stale` — approving again with the old version returns 409 problem+json and `optimistic_lock_conflicts_total` rises by 1.
- `decisions.exception-reason` — approving an `AMOUNT_MISMATCH` without a reason returns 422. With a reason it returns 200.
- `decisions.reject` — `POST /matches/{id}/reject` sets `REJECTED`, puts the charge back to `UNMATCHED`, writes no outbox row, and frees the receipt for another charge.
- `decisions.rematch` — reconciling the rejected charge again reuses the same match id, selects the same receipt, and history shows `REJECTED` then `SUGGESTED`.
- `decisions.outbox` — the two `MatchConfirmed` rows gain `published_at` after the poller runs.
- `decisions.history` — `GET /matches/{id}/history` shows the approve and reject events, including the reason.

## How to get to it (user POV)

- `POST http://127.0.0.1:8080/transactions` and `POST http://127.0.0.1:8080/documents` with `X-API-Key: local-dev-key` and `Idempotency-Key`.
- `POST http://127.0.0.1:8080/reconcile/{txnId}` with the same API key and a new `Idempotency-Key`.
- `GET http://127.0.0.1:8080/matches/{id}` and `GET http://127.0.0.1:8080/matches/{id}/history`. No API key is required.
- `POST http://127.0.0.1:8080/matches/{id}/approve` and `POST http://127.0.0.1:8080/matches/{id}/reject` with `X-API-Key`, `Idempotency-Key`, and a JSON body `{version, actor, reason?}`. Reject requires `reason`.
- `GET http://127.0.0.1:8080/actuator/prometheus` for `optimistic_lock_conflicts_total`.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The base URL is `http://127.0.0.1:8080`.
- This recipe inserts fixed idempotency keys beginning with `dec-`. A second drive on the same database is not a pass. Launch a new `RUN_ID`.

- **Approve a suggestion.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive decisions` posts an `8.00` AUD charge and receipt for `suggest-owner` with no tax, reconciles them to `SUGGESTED` at score `0.7` and version `0`, then approves version `0` as actor `sam` with reason `looks right`. Result: HTTP 200, status `APPROVED`, version `1`. History has `SUGGESTED` by `system:matching`, then `APPROVED` by `sam` with that reason. One `outbox` row has event type `MatchConfirmed`.
- **Replay the approve.** The same key and body return the same response. The match still has two audit rows and one outbox row.
- **Stale version.** Approving that match again with version `0` and a new key returns HTTP 409, `application/problem+json`, and a detail that says the version is stale. No third audit row is written. `optimistic_lock_conflicts_total` rises by 1.
- **Exception reason.** A `50.00` charge against a `55.00` receipt for `exception-owner` is `AMOUNT_MISMATCH`. Approving it with no reason returns HTTP 422 and leaves one audit row. Approving it with reason `override the amount` returns HTTP 200, status `APPROVED`, version `1`.
- **Reject and free the receipt.** An `8.00` suggestion for `reject-owner` is rejected with reason `not this receipt`. Result: HTTP 200, status `REJECTED`, the charge status is `UNMATCHED`, and that match has no outbox row. History ends with `REJECTED` and that reason.
- **Re-match the same row.** Reconciling that charge again returns HTTP 200 with the same `matchId`, status `SUGGESTED`, and the same receipt. History is `SUGGESTED`, then `REJECTED`, then `SUGGESTED`, and the last event's `before.status` is `REJECTED`. One match row exists for the charge. Rejecting that row again, at version `2`, frees the receipt. A later `8.00` charge for the same owner is `SUGGESTED` and holds that receipt.
- **Outbox publish.** Within about 20 seconds both `MatchConfirmed` rows have `published_at` set. Files are under `/tmp/spend-recon-verify-evidence/$RUN_ID/decisions/`.

## Gotchas

- An `EXCEPTION` approve with a missing or blank reason is 422, not 409. The version does not change.
- A stale version is 409 and increments `optimistic_lock_conflicts_total`. Approving a match that is already `APPROVED`, at its current version, is also 409 and does not increment that counter. This recipe only drives the stale case.
- Postgres may insert spaces into the stored JSON. Compare the replay to the first response body, not to the request.
- A re-match may select a receipt this charge already rejected. Skipping those receipts is not built. A receipt held by another live match is still skipped.
- A match that is not `REJECTED` still returns 409 from reconcile. The detail says the transaction already has a match.
- The poller runs about once a second. `published_at` can still be null in the moment after approve. This recipe waits for it.
