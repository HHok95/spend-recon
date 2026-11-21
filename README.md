# spend-recon

Card-transaction to receipt reconciliation.

The locked design is [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). This service records card transactions and documents, matches a charge to a receipt, and writes one audit event per transition. Manual approve and reject, and publishing the outbox, are later pull requests.

## Requirements

- JDK 21
- Docker, for Testcontainers and `docker compose`

Stack: Kotlin 2.2, Spring Boot 3.5, Gradle Kotlin DSL, PostgreSQL 16, Flyway.

## Build

```bash
./gradlew build
```

`./gradlew build` starts a Postgres 16 container and runs the migration, health, and audit tests.

## Run

```bash
docker compose up --build
```

- Health: http://localhost:8080/actuator/health
- Metrics: http://localhost:8080/actuator/prometheus
- API reference (Scalar): http://localhost:8080/scalar

Postgres is published on `localhost:5432` as database `spendrecon`, user `spendrecon`, password `spendrecon`. Those values are local defaults.

## Ingest

`POST /transactions` and `POST /documents` need two headers:

- `X-API-Key`. The local default is `local-dev-key`. Set `RECON_API_KEY` to override it.
- `Idempotency-Key`. A client-chosen token. The same token and the same raw body return the original response. The same token and a different body return 422. A token whose first request has not stored a response yet returns 409 with `Retry-After`.

Amounts are decimal strings. `12.34` with currency `AUD` is stored as 1234 minor units. A JSON number is rejected.

```bash
curl -sS -D - \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: local-dev-key' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"cardOwnerId":"owner-1","merchant":"Cafe","amount":"12.34","currency":"AUD","txnDate":"2026-11-21"}' \
  http://localhost:8080/transactions
```

## Reconcile

`POST /reconcile/{txnId}` uses the same two headers. The body may be empty. A match whose score is at least `0.95` and whose status is `MATCHED` is stored as `APPROVED`, with actor `system:auto-approve`. The response status is that stored status. `GET /matches/{id}/history` returns one event per transition. `GET /matches/{id}` includes `version`.

A charge with no receipt returns status `EXCEPTION`, exception type `NO_CANDIDATE`, and JSON `score` null. A charge that finds one receipt with a different amount returns `AMOUNT_MISMATCH` and keeps that receipt. Another charge cannot take a receipt a live match already holds.

```bash
curl -sS -D - -X POST \
  -H 'X-API-Key: local-dev-key' \
  -H 'Idempotency-Key: recon-1' \
  http://localhost:8080/reconcile/00000000-0000-0000-0000-000000000000
```
