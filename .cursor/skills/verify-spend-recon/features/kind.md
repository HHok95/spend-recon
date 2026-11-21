# Kind cluster

An operator starts the API in a Kubernetes cluster on their own machine. The cluster is kind, which runs Kubernetes inside Docker. Health answers on localhost, and a charge with a matching receipt is approved. The cluster is deleted at the end.

## Sub-features

- `kind.health` — `GET /actuator/health`, `/actuator/health/liveness`, and `/actuator/health/readiness` return 200 and `"status":"UP"`.
- `kind.reconcile` — a matching charge and receipt reconcile to `APPROVED` with score `1`, and the history shows `MATCHED` then `APPROVED`.
- `kind.flyway` — the highest successful Flyway version in the cluster database matches the highest `V<n>__*.sql` file, and no migration failed.
- `kind.down` — the cluster named `spend-recon` is deleted when the drive finishes.

## How to get to it (user POV)

- `make kind-up` builds the image, starts the cluster, and forwards http://127.0.0.1:8080.
- `GET http://127.0.0.1:8080/actuator/health` with no API key.
- `POST http://127.0.0.1:8080/transactions`, `POST http://127.0.0.1:8080/documents`, and `POST http://127.0.0.1:8080/reconcile/{txnId}` with `X-API-Key: local-dev-key`.
- `make kind-down` deletes the cluster.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set. This drive does not use `launch` or `doctor`.
- Docker is running. `kind` and `kubectl` are on `PATH`.
- Host port `8080` is free. A Compose stack must not be running.
- The drive deletes the kind cluster named `spend-recon`, including when a check fails.

- **Bring the cluster up, reconcile, and tear it down.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive kind` runs `scripts/kind-up.sh`, checks health, liveness, and readiness, posts a `12.34` AUD charge and a matching receipt for `kind-owner`, reconciles the charge, reads the history, checks Flyway, then runs `scripts/kind-down.sh`. Result: health is `UP`, the reconcile response is `APPROVED` with score `1`, history is `MATCHED` by `system:matching` then `APPROVED` by `system:auto-approve`, and the Flyway version matches the migration files with no failed row. Evidence is under `/tmp/spend-recon-verify-evidence/$RUN_ID/kind/`.

## Gotchas

- This cluster stays on the same machine. It does not deploy anywhere else.
- Prometheus and Grafana are not in the cluster. Those stay on Compose (`drive prometheus`, `drive grafana`).
- The database password and API key are the Compose local defaults (`spendrecon` and `local-dev-key`).
- The drive deletes the cluster named `spend-recon`. Do not point it at a cluster you want to keep.
- Port `8080` is the forwarded API. Kind does not publish `5432`, `9090`, or `3000`.
- A second drive on the same `RUN_ID` overwrites the evidence files. The database is new each time, because the cluster is recreated.
