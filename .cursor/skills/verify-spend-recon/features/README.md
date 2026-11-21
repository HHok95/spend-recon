# spend-recon verification map

This directory is the maintained source for verifying the user-facing behavior of spend-recon. Read the index before driving the app, then use the matching feature file as the recipe. Keep the map current with `/maintain-verification-skill` when a route is added or renamed.

## Baseline preconditions

- Check out the repository and run from its root, with Docker available.
- Set `RUN_ID` to a fresh id such as `proof1`. The evidence directory is `/tmp/spend-recon-verify-evidence/$RUN_ID`.
- Launch with `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify launch`. Postgres has no host volume, so the database starts empty and Flyway applies V1.
- The only stack under test listens at `http://127.0.0.1:8080`. Host ports `8080` and `5432` must be free. A second stack cannot run beside this one.
- Run `spend-recon-verify doctor` and require this compose project, port `8080` owned by its app container, health `UP`, Flyway version `1`, and OpenAPI title `spend-recon`.
- Never drive an instance that was not started by this verification run.
- There are no seeded rows. The schema is the proof that the app migrated.

## Driving conventions

- Start every recipe from the baseline state unless its preconditions say otherwise.
- Treat every command as literal. Keep `RUN_ID`, paths, and flags unchanged.
- Run HTTP and database actions through `spend-recon-verify drive <feature>`.
- The database checks use `psql` inside the Postgres container this app migrated. There is no schema or audit HTTP route yet.
- Do not remove proof artifacts during cleanup. Evidence stays under `/tmp/spend-recon-verify-evidence/$RUN_ID`.

## Proof and skip reporting

- Capture the user action and the resulting state, not only the final status code.
- HTTP proof includes the command, status, headers, body, and curl stderr.
- SQL proof includes the statement, stdout, stderr, exit code, and a follow-up read where the recipe says so.
- Record the feature id and the URL or statement used with every artifact.
- Report an unreachable path with the attempted command and the unmet precondition.
- Do not report a skipped entry point as verified through a different path.
- Planned features below are not built. A 404 on those routes is not a pass.

## Feature entry contract

Each feature file starts with an H1 title and one paragraph describing the user-visible behavior. It then uses exactly four H2 sections in this order.

1. `Sub-features` lists short IDs with one line for each behavior.
2. `How to get to it (user POV)` lists every user entry point.
3. `Driving it with spend-recon-verify` starts with `Preconditions:` and uses labeled bullets that pair each user action with an exact command and observable result.
4. `Gotchas` lists traps that can waste or invalidate a verification run.

Keep implementation details out of the map. Name only user paths, stable handles, required state, commands, and observable proof.

## Features

- [Health](./health.md) covers `GET /actuator/health` reporting `UP` with no API key.
- [Prometheus scrape](./prometheus.md) covers `GET /actuator/prometheus` exposing the JVM metric.
- [Scalar API reference](./scalar.md) covers `GET /scalar` rendering the springdoc document at `/v3/api-docs`, and the absence of Swagger UI.
- [Migrated schema](./schema.md) covers Flyway V1, the six tables, no duplicate-check column, and the one-live-match index.
- [Append-only audit log](./audit-log.md) covers `UPDATE` and `DELETE` on `reconciliation_events` failing while the row stays.

## Planned features

These are specified in `docs/ARCHITECTURE.md` and are not implemented. Do not drive them and do not record a pass. A later PR replaces each entry with a feature file once the route exists.

- Ingest with idempotency. `POST /transactions` and `POST /documents`, with `X-API-Key` and `Idempotency-Key`. Not built.
- Reconcile. `POST /reconcile/{txnId}` returns the match id, status, score, and rules fired. Not built.
- Approve and reject. `POST /matches/{id}/approve` and `POST /matches/{id}/reject`. A stale `version` returns 409. Not built.
- Auto-approve audit. A `MATCHED` score at or above the threshold writes an audit event with actor `system:auto-approve`. Not built.
- Outbox. Approve writes an `outbox` row in the same transaction, and a restart still publishes it. Not built.
