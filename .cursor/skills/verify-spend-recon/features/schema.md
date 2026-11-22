# Migrated schema

After the API is up, the database it migrated contains the tables and the one-live-match index. There is no duplicate-check column on documents. There is no HTTP route for the schema; the operator reads it with `psql` in the Postgres container. The Flyway check follows the highest `V<n>` file, so a later migration is part of the same proof.

## Sub-features

- `schema.flyway` — the highest successful version in `flyway_schema_history` equals the highest `V<n>__*.sql` file, and no migration failed.
- `schema.tables` — `card_transactions`, `documents`, `matches`, `reconciliation_events`, `idempotency_keys`, `outbox`, `tenants`, and `demo_sessions` exist.
- `schema.no-content-hash` — `documents` has no `content_hash` column.
- `schema.live-match-index` — unique index `matches_one_live_per_document` excludes `REJECTED`.
- `schema.outbox-index` — partial index `outbox_unpublished` covers rows where `published_at` is null.
- `schema.status-index` — index `matches_status` is on `matches.status`.

## How to get to it (user POV)

- Start the API so Flyway runs, then inspect database `spendrecon` in the Compose Postgres service. The helper's `drive schema` is that inspection.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The database is the empty one this launch migrated. Do not point `psql` at another Postgres.

- **Read the migrated schema.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive schema` queries the Compose database. Result: `card_transactions`, `documents`, `matches`, `reconciliation_events`, `idempotency_keys`, `outbox`, `tenants`, and `demo_sessions` are listed, the `content_hash` count is `0`, the live-match index contains `UNIQUE` and `REJECTED`, `outbox_unpublished` contains `published_at IS NULL`, `matches_status` indexes `status`, and the highest successful Flyway version matches the highest migration file with no failed row. Output is under `/tmp/spend-recon-verify-evidence/$RUN_ID/schema/`.

## Gotchas

- This is not an HTTP feature. Passing `/actuator/health` does not prove the index or the missing column.
- The duplicate-document check is still an open design. Do not add `content_hash` in order to make a later recipe pass.
- A host `psql` on `localhost:5432` is the same database only while this stack owns that port.
