# spend-recon

Card-transaction to receipt reconciliation.

The locked design is [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). This service records card transactions and documents. Matching, approval, and the outbox are later pull requests.

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
