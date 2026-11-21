# Migrated schema

After the API is up, the database it migrated contains the V1 tables and the one-live-match index. There is no duplicate-check column on documents. There is no HTTP route for the schema; the operator reads it with `psql` in the Postgres container.

## Sub-features

- `schema.flyway` — `flyway_schema_history` has successful version `1`.
- `schema.tables` — `card_transactions`, `documents`, `matches`, `reconciliation_events`, `idempotency_keys`, and `outbox` exist.
- `schema.no-content-hash` — `documents` has no `content_hash` column.
- `schema.live-match-index` — unique index `matches_one_live_per_document` excludes `REJECTED`.

## How to get to it (user POV)

- Start the API so Flyway runs, then inspect database `spendrecon` in the Compose Postgres service. The helper's `drive schema` is that inspection.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The database is the empty one this launch migrated. Do not point `psql` at another Postgres.

- **Read the migrated schema.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive schema` queries the Compose database. Result: the six tables are listed, the `content_hash` count is `0`, the index definition contains `UNIQUE` and `REJECTED`, and the Flyway version is `1`. Output is under `/tmp/spend-recon-verify-evidence/$RUN_ID/schema/`.

## Gotchas

- This is not an HTTP feature. Passing `/actuator/health` does not prove the index or the missing column.
- The duplicate-document check is still an open design. Do not add `content_hash` in order to make a later recipe pass.
- A host `psql` on `localhost:5432` is the same database only while this stack owns that port.
