# Reconcile

A client records a card charge and a receipt, then asks the API to match them. A perfect pair is approved in the same request. A charge with no receipt is an exception with a null score. A charge whose only receipt has a different amount keeps that receipt and does not approve. The audit history of the approved match is readable over HTTP.

## Sub-features

- `reconcile.auto-approve` — identical charge and receipt return status `APPROVED`, score `1`, actor `system:auto-approve`, and an unpublished `MatchConfirmed` outbox row.
- `reconcile.history` — `GET /matches/{id}/history` returns the `MATCHED` event from `system:matching`, then the `APPROVED` event from `system:auto-approve`.
- `reconcile.no-candidate` — a charge with no receipt returns `EXCEPTION`, JSON `score` null, and exception type `NO_CANDIDATE`. It is not approved.
- `reconcile.amount-mismatch` — a $50 charge against a $55 receipt returns `AMOUNT_MISMATCH` and holds that receipt.
- `reconcile.held-receipt` — a later $55 charge for the same owner does not receive the held receipt and does not return 500.
- `reconcile.metrics` — `recon_auto_approvals_total`, `recon_matches_total`, `recon_exceptions_total`, and `recon_match_latency_seconds` move by the counts this recipe produced.

## How to get to it (user POV)

- `POST http://127.0.0.1:8080/transactions` and `POST http://127.0.0.1:8080/documents` with `X-API-Key: local-dev-key` and `Idempotency-Key`.
- `POST http://127.0.0.1:8080/reconcile/{txnId}` with the same API key, a new `Idempotency-Key`, and an empty body.
- `GET http://127.0.0.1:8080/matches/{id}` and `GET http://127.0.0.1:8080/matches/{id}/history`. No API key is required on those reads.
- `GET http://127.0.0.1:8080/actuator/prometheus` for the reconcile counters.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The base URL is `http://127.0.0.1:8080`.
- This recipe inserts fixed idempotency keys (`recon-auto-txn`, `recon-auto-doc`, `recon-auto`, `recon-none-txn`, `recon-none`, `recon-mismatch-txn`, `recon-mismatch-doc`, `recon-mismatch`, `recon-second-txn`, `recon-second`). A second drive on the same database is not a pass. Launch a new `RUN_ID`.

- **Approve a perfect pair.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive reconcile` posts a `12.34` AUD charge and a matching receipt for `auto-owner`, then reconciles the charge. Result: HTTP 200, status `APPROVED`, score `1`, and rules include `R5_MERCHANT` and `R6_TAX`. `GET /matches/{id}` shows `decidedBy` `system:auto-approve` and version `0`. One `outbox` row has event type `MatchConfirmed`. The poller may already have set `published_at`.
- **Read the audit.** `GET /matches/{id}/history` returns two events. The first is `MATCHED` by `system:matching`. The second is `APPROVED` by `system:auto-approve`, and its `after` object stores rule-set version `r1-r6.v1` and threshold `0.95`.
- **No receipt.** A charge for `none-owner` reconciles to `EXCEPTION` with JSON score null. The stored exception type is `NO_CANDIDATE`, `documentId` is null, and `decidedBy` is null.
- **Amount mismatch.** A `50.00` charge and a `55.00` receipt for `mismatch-owner` reconcile to `AMOUNT_MISMATCH`. The match holds that receipt id and is not approved.
- **Held receipt.** A second `55.00` charge for `mismatch-owner` returns HTTP 200, exception type `NO_CANDIDATE`, and a null `documentId`. It does not take the receipt the first match holds.
- **Counters.** Across the recipe, `recon_auto_approvals_total` rises by 1, `recon_matches_total{result="MATCHED"}` by 1, `recon_matches_total{result="EXCEPTION"}` by 3, `recon_exceptions_total{type="NO_CANDIDATE"}` by 2, and `recon_exceptions_total{type="AMOUNT_MISMATCH"}` by 1. `recon_match_latency_seconds` is present. Files are under `/tmp/spend-recon-verify-evidence/$RUN_ID/reconcile/`.

## Gotchas

- The reconcile response status is the stored status. An auto-approved match is `APPROVED` in that body, not `MATCHED`. The `MATCHED` step is the first history event.
- Postgres stores the idempotency body as jsonb, so the replayed JSON may contain `"score": null` with a space. Both forms are a null score.
- A receipt held by an `EXCEPTION` is not free. The second charge must not be treated as a failure when it comes back `NO_CANDIDATE`.
- This recipe does not call approve or reject. Those routes are `drive decisions`.
- The outbox row is written here. Publishing it is proved by `drive decisions`, which waits until `published_at` is set.
