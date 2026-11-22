# spend-recon

Card-transaction to receipt reconciliation.

The locked design is [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). This service records card transactions and documents, matches a charge to a receipt, and writes one audit event per transition. A person can approve or reject a match. Approving writes an outbox row, and a poller publishes it.

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

## Kubernetes (kind)

Docker, kubectl, and kind 0.24.0 or newer are required. kind 0.24.0 is the first release whose default node image is Kubernetes 1.31, where nftables kube-proxy runs without a feature gate. `scripts/kind-config.yaml` selects that mode because a kernel without the `xt_statistic` match installs no iptables Service rules. `make kind-up` checks the kind version and stops if it is older.

`make kind-up` starts a Kubernetes cluster in Docker on this machine, builds the app image, and forwards the API to http://localhost:8080. Postgres 16 runs in that cluster. Flyway applies the migrations when the app process starts, the same way it does under Compose. Prometheus and Grafana stay on Compose.

The cluster name is `SPEND_RECON_KIND_CLUSTER`, default `spend-recon`. kind has its own cluster-name setting, `KIND_CLUSTER_NAME`. These scripts do not read it, so a value set for another project is left alone. After `make kind-up` creates a cluster, it labels the nodes `spend-recon.io/created-by=kind-up.sh`. It reuses a cluster only when that label is present. `make kind-down` deletes a cluster only when that label is present. Otherwise both scripts stop and explain why.

kubectl calls pass `--context kind-<cluster>` and `--kubeconfig .kube/kind-config`. They do not change the current context. `.kube/` is gitignored.

`make kind-down` deletes the cluster these scripts created.
The Postgres volume uses Retain. A deleted claim will not bind again, so recreate the data with `make kind-down && make kind-up`.
A cluster created before this change has no ownership label and is refused. Remove it once with `kind delete cluster --name spend-recon`.

The database password and `RECON_API_KEY` in `k8s/` are the same local defaults as Compose. They are not real credentials.

## Observability

`docker compose up` also starts Prometheus and Grafana. They are part of this local stack. CI runs `./gradlew build` and does not start them.

- Prometheus: http://localhost:9090
  It scrapes `http://app:8080/actuator/prometheus` every 5 seconds. The job name is `spend-recon`. The targets page is http://localhost:9090/targets
- Grafana: http://localhost:3000
  User `admin`, password `admin`. That login is for this local Compose stack only. Do not reuse it on another machine.
  Grafana loads `monitoring/grafana/dashboards/spend-recon.json` at startup. The dashboard is http://localhost:3000/d/spend-recon/spend-recon

The dashboard has a panel for each metric in section 9 of the architecture. `idempotent_replays_total` has samples while the app is running, because ingest registers that counter. Matching records `recon_matches_total{result}`, `recon_match_latency_seconds`, `recon_exceptions_total{type}`, and `recon_auto_approvals_total`. Approve and reject record `optimistic_lock_conflicts_total` when a version is stale. `outbox_lag_seconds` is the age of the oldest unpublished outbox row, or zero when the queue is empty.

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

A charge with no receipt returns status `EXCEPTION`, exception type `NO_CANDIDATE`, and JSON `score` null. A charge that finds one receipt with a different amount keeps that receipt as `AMOUNT_MISMATCH` when the merchant names score at least 0.60. A more distant merchant name leaves the receipt free. Another charge cannot take a receipt a live match already holds.

```bash
curl -sS -D - -X POST \
  -H 'X-API-Key: local-dev-key' \
  -H 'Idempotency-Key: recon-1' \
  http://localhost:8080/reconcile/00000000-0000-0000-0000-000000000000
```

## Approve and reject

`POST /matches/{id}/approve` and `POST /matches/{id}/reject` use the same two headers. The body carries the match `version` from `GET /matches/{id}`, an `actor`, and a `reason`. Reject requires a reason. Approve requires one only when the match is `EXCEPTION`. A version that is not current returns 409. The match then moves to `APPROVED` or `REJECTED`, and one audit row is written in that same transaction. Reject sets the charge back to `UNMATCHED` and frees the receipt. Approve also writes one `MatchConfirmed` outbox row.

Reconciling a charge whose match is `REJECTED` reuses that same row. The row is locked, `document_id` and `score` are set to the new decision, and `version` increments. `GET /matches/{id}/history` shows the reject and then the new match. `transaction_id` stays unique. A match in any other status still returns 409. A re-match may select a receipt this charge already rejected. Skipping those receipts is not built yet.

```bash
curl -sS -D - -X POST \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: local-dev-key' \
  -H 'Idempotency-Key: approve-1' \
  -d '{"version":0,"actor":"sam","reason":"same trip"}' \
  http://localhost:8080/matches/00000000-0000-0000-0000-000000000000/approve
```
