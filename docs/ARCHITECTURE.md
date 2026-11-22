# spend-recon: architecture lock (v0.3, 2026-11-22)

Hok's decisions are recorded under each **Decided** line. Agents build to this file and nothing else.

## 1. Stack
These are locked unless Hok objects:
- Kotlin 2.x and JDK 21, with Spring Boot 3.x.
- Gradle with the Kotlin DSL.
- PostgreSQL 16, with Flyway for migrations.
- Spring Data JPA with Hibernate. Use `@Version` for optimistic locking, keep associations lazy, keep `open-in-view` off, and set `ddl-auto=validate` so Flyway owns the schema.
- springdoc-openapi for the OpenAPI document, Scalar for the API reference, Micrometer with a Prometheus registry, JUnit 5, Testcontainers, and Kotest property tests for `Money`.

**Decided (Hok, 2026-11-21):** JPA/Hibernate.

## 2. Package layout (hexagonal, light)
```
com.hhok.spendrecon
  domain/        Money, Transaction, Document, Match, MatchStatus, ExceptionType, rules (pure Kotlin, no Spring)
  application/   IngestService, MatchingService, ApprovalService, IdempotencyService, OutboxPublisher
  adapters/in/   REST controllers, DTOs, ApiKeyFilter, error mapping (RFC 7807 problem+json)
  adapters/out/  JPA repositories, AccountingExportPort (stub), LlmSuggestionPort (stretch)
```
Rule: `domain` imports nothing from Spring. The matching rules are plain functions, so they're unit-testable without a database.

## 3. Money
```kotlin
@JvmInline value class Currency(val code: String)      // ISO-4217, validated, uppercase
data class Money(val minor: Long, val currency: Currency) {
  operator fun plus(o: Money): Money  // throws CurrencyMismatch when currencies differ
}
```
- The minor-unit exponent comes from `java.util.Currency.defaultFractionDigits`. For example, JPY has 0 decimal places and AUD has 2.
- The API accepts `"amount": "12.34"` as a string, never a JSON number, and converts it exactly with `BigDecimal`. Too many decimal places returns 400.
- Postgres stores it as `amount_minor BIGINT` plus `currency CHAR(3)`.

**Decided (2026-11-21):** decimal strings. Hok skipped this one, so the recommendation stands unless he says otherwise.

## 4. Data model and the duplicate-receipt rule
```
card_transactions(id uuid pk, card_owner_id text, merchant_raw text, merchant_norm text,
                  amount_minor bigint, currency char(3), tax_minor bigint null, txn_date date,
                  status text, created_at timestamptz)
documents(id uuid pk, kind text check (kind in ('RECEIPT','BILL')), card_owner_id text,
          merchant_raw text, merchant_norm text, amount_minor bigint, currency char(3),
          tax_minor bigint null, doc_date date, document_number text null,
          document_number_norm text null, created_at timestamptz)
  -- unique index documents_merchant_document_number on
  --   (merchant_norm, document_number_norm) where document_number_norm is not null
  -- document_number_norm is the trimmed, lowercased number, written by the application
matches(id uuid pk, transaction_id uuid unique references card_transactions,
        document_id uuid null references documents, status text, score numeric(4,3) null,
        rules_fired text[], exception_type text null, version bigint not null default 0,
        decided_by text null, decided_at timestamptz null, created_at timestamptz)
  -- score is null exactly when document_id is null: (score IS NULL) = (document_id IS NULL)
  -- score is null, or between 0 and 1
  -- partial unique index: one live (non-rejected) match per document_id
  -- EXCEPTION holds its receipt
reconciliation_events(id bigserial pk, match_id uuid, event_type text, actor text,
        reason text, rules_fired text[], before jsonb, after jsonb, created_at timestamptz)
  -- trigger: RAISE EXCEPTION on UPDATE or DELETE (append-only)
idempotency_keys(key text, endpoint text, request_hash text, response_status int,
        response_body jsonb, created_at timestamptz, primary key (key, endpoint))
outbox(id bigserial pk, aggregate_id uuid, event_type text, payload jsonb,
       created_at timestamptz, published_at timestamptz null)
```
**Decided (Hok, 2026-11-22):** hard-reject only an exact key: the same Idempotency-Key, which stays as it is, or the same receipt or invoice number from the same merchant. A near-match is flagged POSSIBLE_DUPLICATE and routed to EXCEPTION for a person to review. A real repeat purchase is not blocked.

`document_number` is the optional `documentNumber` on `POST /documents`, the receipt or invoice number, with Unicode whitespace trimmed from both ends. A blank value is stored as null. `document_number_norm` is that trimmed value lowercased. `trimDocumentNumber` is the only trim, and the unique index is on `(merchant_norm, document_number_norm)` where the norm is not null. The index does not trim again. The merchant half is `merchant_norm` (trimmed, lowercased, internal whitespace collapsed). The key is not limited to one card owner. A violation is HTTP 409 problem+json, type `urn:spend-recon:duplicate-document`, title `Duplicate document`. Documents with no number are outside the index. Replaying an Idempotency-Key still follows section 7.

A near-match is another receipt for the same card owner with the same normalized merchant, the same amount and currency, and the same date. That other receipt is the similar document: the check compares those stored fields for equality. The numbers differ, or at least one is missing, because an equal number was rejected at ingest. The result is EXCEPTION, exception type `POSSIBLE_DUPLICATE`, and the rules fired include `R7_DUPLICATE`. The row holds the receipt the matcher would have selected. A sibling already held by a live match still counts, so the free copy is not auto-approved. Auto-approve accepts only `MATCHED`.

A repeat purchase is the same merchant and amount on a different date. The existing rules score it, and auto-approve can accept it. A different receipt number follows that same path when the date differs or the amount differs.

## 5. State machine
```
           ingest txn
               │
           matching run ──► MATCHED ──┐
               │                      ├─► approve ─► APPROVED (outbox: MatchConfirmed)
               ├────────► SUGGESTED ──┤
               │                      └─► reject  ─► REJECTED (txn goes back to unmatched)
               └────────► EXCEPTION ── approve with override reason / reject

           MATCHED with score ≥ auto-approve threshold ─► APPROVED automatically

           REJECTED ── reconcile again ──► same match row, then MATCHED / SUGGESTED / EXCEPTION
```
- Auto-approve: a `MATCHED` result whose score is at or above `recon.auto-approve.threshold` (config, default 0.95, separate from the 0.85 MATCHED cut-off) is approved in the same transaction, with actor `system:auto-approve`.
- Auditable: the auto-approve event stores the score, the threshold in force, the rules fired and the rule-set version. EXCEPTION and SUGGESTED results are never auto-approved. Setting the threshold above 1.0 turns auto-approve off.
- Metric `recon_auto_approvals_total`, so the auto-approve rate is visible.
- Every transition writes one `reconciliation_events` row in the same database transaction.
- An EXCEPTION holds its receipt until approved or rejected, when the merchant score clears the minimum in section 6.
- Matching skips a receipt already held by another live match. If no candidate remains, the result is an EXCEPTION.
- Reconciling a charge whose match is `REJECTED` locks that row and reuses it. The row moves from `REJECTED` through the normal result (`MATCHED`, `SUGGESTED`, or `EXCEPTION`, then auto-approve when the score qualifies). `document_id` and `score` become the new decision, and `@Version` increments. `transaction_id` stays unique, so a second match row is not inserted. One `reconciliation_events` row records the move off `REJECTED`. Auto-approve, when it runs, is still its own following event and its own outbox row.
- Any other existing status (`MATCHED`, `SUGGESTED`, `EXCEPTION`, `APPROVED`) still returns 409.
- A re-match does not yet skip a receipt this charge already rejected, so it may select that receipt again.

**Decided (Hok, 2026-11-21):** auto-approve above a score threshold, kept auditable as above. An EXCEPTION holds its receipt. Matching skips a receipt held by another live match. A rejected charge reuses its match row. Skipping a receipt the charge already rejected is still on hold.

## 6. Matching rules (deterministic, ordered)
| Rule id | Check | Effect |
|---|---|---|
| R1_CURRENCY | Currencies must be equal | If not, and the merchant score clears the minimum: EXCEPTION CURRENCY_MISMATCH, never auto-matched |
| R2_OWNER | Same card owner | Hard filter |
| R3_AMOUNT | `amount_minor` equal | If one candidate is unequal and its merchant score clears the minimum: EXCEPTION AMOUNT_MISMATCH |
| R4_DATE | \|txn_date − doc_date\| ≤ 3 days | Hard filter |
| R5_MERCHANT | Jaro-Winkler on `merchant_norm` | Score component |
| R6_TAX | Tax present on both and equal | Score component |
| R7_DUPLICATE | Exact receipt or invoice number for the merchant is rejected at ingest. Same merchant, amount, currency, and date (a similar document) is POSSIBLE_DUPLICATE. A different date is a normal match | 409, or EXCEPTION, never auto-approved |

Score = 0.7·merchant + 0.3·tax. A score ≥ 0.85 gives MATCHED. A lower score gives SUGGESTED, and the best candidate wins. When two candidates tie, the result is SUGGESTED with both listed.

Zero candidate receipts gives EXCEPTION NO_CANDIDATE, with `document_id` null and `score` null. That is also the result when every receipt is already held by another live match. `score` is null exactly when the match has no receipt.

A receipt that fails R1 or R3 is held only when Jaro-Winkler on `merchant_norm` is at least `recon.exception.min-merchant-score` (default 0.60, the same 0-to-1 scale as R5). If no failing receipt clears that score, the result is NO_CANDIDATE with no receipt and a null score. The exception audit event stores `minMerchantScore`, the value in force, the same way an auto-approve event stores its threshold.

**Decided (Hok, 2026-11-22):** an EXCEPTION holds a receipt only when the merchant score is at least `recon.exception.min-merchant-score`. Otherwise the result is NO_CANDIDATE.

## 7. API (all writes need `X-API-Key` and `Idempotency-Key`)
```
POST /transactions             201 {id, status}
POST /documents                201 {id}
                               409 duplicate receipt or invoice number for the merchant
POST /reconcile/{txnId}        200 {matchId, status, score, rulesFired}
                               score is JSON null when the match has no receipt
GET  /matches?status=          200 page
GET  /matches/{id}             200 (includes version)
POST /matches/{id}/approve     body {version, actor, reason?}  200 | 409 stale version
POST /matches/{id}/reject      body {version, actor, reason}   200 | 409
GET  /matches/{id}/history     200 [events]
POST /matches/{id}/suggest     (stretch) 200 draft suggestion, never auto-applied
```
**Idempotency:**
1. Insert the key row first with `INSERT ... ON CONFLICT DO NOTHING`, inside the same transaction as the business write.
2. If the key exists with the same request hash, replay the stored response.
3. If the key exists with a different hash, return 422.
4. If the key exists but is still in flight (no response stored yet), return 409 with Retry-After.

**Decided (2026-11-21):** explicit `POST /reconcile/{txnId}`. Hok skipped this one, so the recommendation stands unless he says otherwise.

## 8. Outbox
- `approve`, manual or automatic, writes the outbox row in the same transaction.
- A `@Scheduled` poller runs `SELECT ... FOR UPDATE SKIP LOCKED LIMIT 50`, publishes to `AccountingExportPort` (a logging stub), then sets `published_at`.
- Delivery is at least once. The consumer de-duplicates by outbox id.

## 9. Observability
- Metrics: `recon_matches_total{result}`, `recon_match_latency_seconds`, `recon_exceptions_total{type}`, `idempotent_replays_total`, `recon_auto_approvals_total`, `optimistic_lock_conflicts_total`, and `outbox_lag_seconds`.
- One Grafana dashboard JSON, provisioned automatically in Docker Compose.

## 10. Tests that prove the claims
- **Rules:** a unit test for each rule id.
- **Money:** property tests (round trip, and no mixed-currency adds).
- **Idempotency:** 10 parallel identical requests produce 1 row and 9 replays.
- **Race:** 2 parallel approves at the same version produce one 200 and one 409.
- **Audit:** UPDATE or DELETE on `reconciliation_events` fails.
- **Outbox:** a crash between commit and publish still publishes once the app restarts.

All of these run on Testcontainers Postgres in CI.

## 11. Pull request plan (cloud agents, grok-4.7, at most 2 in parallel)
1. Skeleton: Gradle, Boot, Flyway V1, compose, CI, health endpoint, ARCHITECTURE.md
2. Money and ingest with idempotency
3. Matching engine, rules and audit trigger. Runs in parallel with 4 once PR 2 is merged.
4. Observability and Docker Compose with Prometheus and Grafana
5. Approve and reject, optimistic locking, outbox
6. kind manifests and `make kind-up`
7. Stretch: LLM suggestion
8. README, diagram, recording script
