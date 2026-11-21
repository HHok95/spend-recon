# Grafana dashboard

An operator opens the local Grafana dashboard. Prometheus has scraped the app, the spend-recon target is up, and the idempotent replays panel shows the same count as the app.

## Sub-features

- `grafana.target` — Prometheus reports the spend-recon target health `up` for `http://app:8080/actuator/prometheus`.
- `grafana.dashboard` — Grafana serves the provisioned dashboard `spend-recon`, and that dashboard names every section 9 metric.
- `grafana.replays` — One repeated transaction ingest increases `idempotent_replays_total` by 1, and Grafana's query of that metric returns the same number.

## How to get to it (user POV)

- Prometheus targets: `http://127.0.0.1:9090/targets`.
- Grafana login: `http://127.0.0.1:3000` with user `admin` and password `admin`. That login is for this local Compose stack only.
- Dashboard: `http://127.0.0.1:3000/d/spend-recon/spend-recon`.
- The replay posts `http://127.0.0.1:8080/transactions` with `X-API-Key: local-dev-key` and `Idempotency-Key: grafana-replay-1`.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The app is `http://127.0.0.1:8080`. Prometheus is `http://127.0.0.1:9090`. Grafana is `http://127.0.0.1:3000`.
- `grafana-replay-1` has not been used on this database. A second drive on the same database is not a pass. Launch a new `RUN_ID`.
- `python3` is on `PATH`. The drive uses it to read the JSON responses.

- **Open the stack.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive grafana` requests `GET /actuator/health` and `GET /actuator/prometheus`, then posts the same transaction twice. Result files are under `/tmp/spend-recon-verify-evidence/$RUN_ID/grafana/`.
- **App health.** `health/http-status.txt` is `200` and the body contains `"status":"UP"`.
- **Replay.** Both posts return 201 and the same `id`. `replay-count-after.txt` is one greater than `replay-count-before.txt`.
- **Prometheus target.** `targets.json` includes job `spend-recon`, health `up`, and scrape URL `http://app:8080/actuator/prometheus`.
- **Prometheus sample.** `prometheus-query.json` returns `idempotent_replays_total` equal to `replay-count-after.txt`.
- **Dashboard.** `dashboard-status.txt` is `200`. The body names `recon_matches_total`, `recon_match_latency_seconds`, `recon_exceptions_total`, `idempotent_replays_total`, `recon_auto_approvals_total`, `optimistic_lock_conflicts_total`, and `outbox_lag_seconds`.
- **Grafana sample.** `grafana-query-status.txt` is `200`. The query result for `idempotent_replays_total` equals `replay-count-after.txt`.

## Gotchas

- `admin` / `admin` is the local Grafana login. Do not reuse it outside this Compose stack.
- Panels for matching, approval, and outbox metrics have no series until those pull requests record them. An empty panel is the expected state. This drive does not invent those series.
- This drive adds one replay. `drive ingest` expects `idempotent_replays_total` of 9 on a fresh process. Run `drive ingest` on its own launch, or before this drive.
- Prometheus scrapes every 5 seconds. The drive waits until the scraped value matches the app.
