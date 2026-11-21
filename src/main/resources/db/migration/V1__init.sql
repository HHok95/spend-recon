-- V1 schema from docs/ARCHITECTURE.md section 4.
--
-- Columns the architecture marks "null" are nullable. The other columns are
-- NOT NULL. response_status and response_body are nullable so an idempotency
-- key can be inserted before the response exists (section 7).
--
-- documents has no duplicate-detection column. That design is still open.

CREATE TABLE card_transactions (
    id              UUID PRIMARY KEY,
    card_owner_id   TEXT        NOT NULL,
    merchant_raw    TEXT        NOT NULL,
    merchant_norm   TEXT        NOT NULL,
    amount_minor    BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,
    tax_minor       BIGINT,
    txn_date        DATE        NOT NULL,
    status          TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE documents (
    id              UUID PRIMARY KEY,
    kind            TEXT        NOT NULL CHECK (kind IN ('RECEIPT', 'BILL')),
    card_owner_id   TEXT        NOT NULL,
    merchant_raw    TEXT        NOT NULL,
    merchant_norm   TEXT        NOT NULL,
    amount_minor    BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,
    tax_minor       BIGINT,
    doc_date        DATE        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE matches (
    id              UUID PRIMARY KEY,
    transaction_id  UUID          NOT NULL UNIQUE REFERENCES card_transactions (id),
    document_id     UUID          REFERENCES documents (id),
    status          TEXT          NOT NULL,
    score           NUMERIC(4, 3) NOT NULL,
    rules_fired     TEXT[]        NOT NULL DEFAULT '{}',
    exception_type  TEXT,
    version         BIGINT        NOT NULL DEFAULT 0,
    decided_by      TEXT,
    decided_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- One live (non-rejected) match per document. A rejected row stays for history
-- and frees the document. Matches with no document are not part of the index.
CREATE UNIQUE INDEX matches_one_live_per_document
    ON matches (document_id)
    WHERE document_id IS NOT NULL
      AND status <> 'REJECTED';

CREATE TABLE reconciliation_events (
    id           BIGSERIAL PRIMARY KEY,
    match_id     UUID        NOT NULL REFERENCES matches (id),
    event_type   TEXT        NOT NULL,
    actor        TEXT        NOT NULL,
    reason       TEXT,
    rules_fired  TEXT[]      NOT NULL DEFAULT '{}',
    before       JSONB,
    after        JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only audit log. A later pull request writes one row per transition.
CREATE FUNCTION prevent_reconciliation_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'reconciliation_events is append-only';
END;
$$;

CREATE TRIGGER reconciliation_events_append_only
    BEFORE UPDATE OR DELETE ON reconciliation_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_reconciliation_event_mutation();

CREATE TABLE idempotency_keys (
    key             TEXT        NOT NULL,
    endpoint        TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (key, endpoint)
);

CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_id  UUID        NOT NULL,
    event_type    TEXT        NOT NULL,
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
