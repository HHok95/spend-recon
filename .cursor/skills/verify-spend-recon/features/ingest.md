# Ingest

A client records a card transaction or a receipt. The amount is a decimal string. Repeating the same request with the same idempotency token returns the original id. The local API key is `local-dev-key`.

## Sub-features

- `ingest.auth` — `POST /transactions` without `X-API-Key` returns 401 problem+json.
- `ingest.amount-string` — a JSON number and a JPY amount with a decimal place return 400, and no row is stored.
- `ingest.parallel` — 10 parallel posts with one `Idempotency-Key` create 1 transaction and 9 replays.
- `ingest.money` — `12.34` AUD is stored as 1234 minor units, and `500` JPY is stored as 500.
- `ingest.replay` — the same key and the same body return the same id.
- `ingest.mismatch` — the same key and a different body return 422.
- `ingest.document` — `POST /documents` returns 201 and stores kind `RECEIPT`.
- `ingest.in-flight` — a key with no stored response returns 409 and `Retry-After`.

## How to get to it (user POV)

- `POST http://127.0.0.1:8080/transactions` with `Content-Type: application/json`, `X-API-Key: local-dev-key`, and `Idempotency-Key`.
- `POST http://127.0.0.1:8080/documents` with the same headers. `kind` is `RECEIPT` or `BILL`.
- Read `GET http://127.0.0.1:8080/actuator/prometheus` for `idempotent_replays_total`. No API key is required on that read.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The base URL is `http://127.0.0.1:8080`.
- The database is the empty one this launch migrated. This recipe inserts fixed keys (`parallel-key-1`, `aud-key-1`, `jpy-key-1`, `doc-key-1`, `inflight-key`). A second drive on the same database is not a pass. Launch a new `RUN_ID`.

- **Record transactions and documents.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive ingest` runs the requests below in order. Result files are under `/tmp/spend-recon-verify-evidence/$RUN_ID/ingest/`.
- **Missing API key.** The unauthenticated request returns HTTP 401 and `application/problem+json`. `unauthenticated/http-status.txt` is `401`.
- **Decimal strings.** A body with `"amount":12.34` returns 400. `500.0` JPY returns 400. `12.34` AUD is stored as amount_minor `1234`, tax_minor `110`, currency `AUD`. `500` JPY is stored as amount_minor `500`.
- **Parallel idempotency.** Ten posts share `Idempotency-Key: parallel-key-1` and one body. Every status is 201, every body has the same id, `card_transactions` has one row for `parallel-owner`, and `idempotent_replays_total` is `9`.
- **Replay and mismatch.** Posting `aud-key-1` again with the same file returns the same id. Posting `aud-key-1` with a different amount returns 422, and `aud-owner` still has one row.
- **Document.** `POST /documents` returns 201. The stored kind is `RECEIPT`.
- **In flight.** A committed idempotency row with no response, whose hash matches the curl body, returns 409 and header `Retry-After: 1`. `inflight-owner` has no transaction row.

## Gotchas

- The idempotency hash is the raw body bytes. A space added to the JSON is a different request and returns 422.
- The same `Idempotency-Key` on `/transactions` and `/documents` is two keys. The recipe uses different keys anyway.
- `idempotent_replays_total` is `9` only when this drive is the first replay traffic on a fresh process. A second drive increments it again.
- The duplicate-document check is not built. Two documents with the same merchant and amount are both stored.
- Read routes still answer without `X-API-Key`. A 200 from `/actuator/health` does not prove the write key.
