# Append-only audit log

An operator inserts one reconciliation event and then cannot change or remove it. The stored reason stays `created`. This recipe uses `psql` on the database the API migrated. `GET /matches/{id}/history` is the HTTP read, and the reconcile recipe drives it.

## Sub-features

- `audit.update-fails` — `UPDATE reconciliation_events` fails and the error text contains `append-only`.
- `audit.delete-fails` — `DELETE FROM reconciliation_events` fails and the error text contains `append-only`.
- `audit.row-unchanged` — a following `SELECT` still returns reason `created`.

## How to get to it (user POV)

- Connect to database `spendrecon` on the Compose Postgres and run the statements in this recipe. The inserted match is an `EXCEPTION` of type `NO_CANDIDATE` with a null score, because a score is stored only when the match holds a receipt.

## Driving it with spend-recon-verify

Preconditions:

- `RUN_ID` is set and `launch` then `doctor` succeeded for that id.
- The database is empty of these ids. The recipe inserts transaction `11111111-1111-1111-1111-111111111111`, match `22222222-2222-2222-2222-222222222222`, and one event. A second drive on the same database fails the insert on the primary key. That failure is not a pass. Launch a new `RUN_ID`.

- **Reject changes to history.** `.cursor/skills/verify-spend-recon/scripts/spend-recon-verify drive audit` inserts the transaction, match, and event, then runs the `UPDATE` and the `DELETE`. Result: both statements exit non-zero with `append-only` in stderr, and `SELECT reason` returns `created`. Files are under `/tmp/spend-recon-verify-evidence/$RUN_ID/audit/`.

## Gotchas

- Do not catch the SQL error and report success without the `append-only` text. A missing table fails too, and that is not this feature.
- `DELETE` succeeds only when `spendrecon.purge` is `on` and the event belongs to a tenant whose expiry is already in the past. The local tenant is never eligible. This recipe does not set the switch. `UPDATE` still fails when the setting is on.
- `TRUNCATE` is not what this recipe proves. The trigger covers `UPDATE` and `DELETE`.
- Cleanup drops the database with the container. It must not delete the evidence directory, which is the only remaining proof.
