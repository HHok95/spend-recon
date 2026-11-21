---
name: verify-spend-recon
description: Verify the spend-recon HTTP API (health, Prometheus, Scalar at /scalar, the Flyway schema, and the append-only audit table) against the Docker Compose stack. Use after a change to those routes or before claiming a later reconciliation feature works.
---

# Verify spend-recon

Drive the real Compose stack over HTTP. Read `features/README.md`, then follow one feature file. Do not invent a passing result for a route that is not built. When a later PR adds a route, update this map with `/maintain-verification-skill`.

The helper is `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify`. Run it from the repository root with `RUN_ID` set. It records proof under `/tmp/spend-recon-verify-evidence/$RUN_ID` and does not delete that directory.

## Launch

One stack can run at a time. Compose publishes host ports `5432` and `8080`, and the recipes call `http://127.0.0.1:8080`. A second project cannot bind those ports.

From the repository root, with Docker running and both ports free:

```bash
export RUN_ID=proof1
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify launch
```

`launch` writes `/tmp/spend-recon-verify-evidence/$RUN_ID/run.env`, then runs `docker compose -p sr<run-id> -f docker-compose.yml up --build -d`. The project name is `sr` plus the lowercase letters and digits in `RUN_ID`. Postgres has no host volume, so the data directory starts empty and Flyway applies `V1__init.sql`.

Ready means both of these, not merely a healthy database:

- `GET http://127.0.0.1:8080/actuator/health` returns 200 and `"status":"UP"`.
- `SELECT version FROM flyway_schema_history WHERE success` returns `1`.

The app does not listen until Flyway has finished, so a database that is up with no migration is not ready. Launch waits up to three minutes, then exits non-zero and leaves `launch/logs.txt`.

Teardown for this same `RUN_ID` is `spend-recon-verify cleanup`. It runs `docker compose -p <project> down` for the project stored in `run.env`.

If the app container exits with `Connect timed out` while Postgres is healthy, this host's legacy iptables `FORWARD` policy may be `DROP`. That drops container-to-container packets. A normal Docker install does not need a change. On a VM that shows it: `sudo iptables-legacy -P FORWARD ACCEPT`. Then run cleanup and launch again.

## Doctor

Read-only. Requires the same `RUN_ID` as launch.

```bash
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify doctor
```

It passes only when all of these are true:

- The app container for this compose project is running.
- Its `com.docker.compose.project` label equals the project in `run.env`.
- Host port `8080` is published by that container, not by another process.
- `GET /actuator/health` with no `X-API-Key` returns 200 and `"status":"UP"`.
- Flyway's successful version is `1`.
- `GET /v3/api-docs` returns `"title":"spend-recon"`, which identifies this build.

Current read routes do not check an API key. A 200 without `X-API-Key` is the auth check. Later write routes will require the key; do not treat today's open reads as proof of that.

## Drive

Follow `features/README.md`, then one feature file. The built commands are:

```bash
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive health
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive prometheus
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive scalar
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive schema
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive audit
```

`drive scalar` is the API reference check: `GET /scalar` returns the Scalar page pointed at `/v3/api-docs`, and `GET /swagger-ui/index.html` returns 404. Planned ingest, reconcile, approve, reject, auto-approve, and outbox routes are listed in the map and must not be reported as verified.

## Evidence

Each command writes under `/tmp/spend-recon-verify-evidence/$RUN_ID/`:

- `launch/` — compose output, the health body, and the Flyway version that marked the stack ready.
- `doctor/doctor.txt` — one `PASS` or `FAIL` line per check.
- `<feature>/` — the command, HTTP status, headers, body, and curl stderr. SQL features also store stdout, stderr, and the exit code.

Proof is the response plus the resulting state. For HTTP, that is the status and the body. For the audit table, that is the rejected `UPDATE` and `DELETE` text containing `append-only`, then a second `SELECT` showing the original `reason`. Do not use a test-only endpoint or an in-memory stand-in. There is no dry-run mode.

```bash
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify evidence
```

prints the directory and the files in it.

## Cleanup

```bash
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify cleanup
```

This stops and removes only the compose project named in this run's `run.env`. It does not kill processes by name, and it does not delete `/tmp/spend-recon-verify-evidence/$RUN_ID`. Run it after a failed launch before trying again. A different `RUN_ID` is a different project and a different evidence directory.

## Helpers

The only helper is the executable script above.

```bash
export RUN_ID=proof1
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify launch
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify doctor
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive scalar
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify evidence
.cursor/skills/verify-spend-recon/scripts/spend-recon-verify cleanup
```

`RUN_ID` must match `[A-Za-z0-9_-]+`. The compose project name keeps only the lowercase letters and digits, prefixed with `sr`.
