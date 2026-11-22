-- One tenant per demo session. The local row never expires, so Compose and the
-- existing API keep a single book. Queries filter on tenant_id.
--
-- reconciliation_events stays append-only. DELETE is allowed only while this
-- job sets spendrecon.purge, so an expired tenant can be removed. UPDATE is
-- still rejected.

CREATE TABLE tenants (
    id          UUID PRIMARY KEY,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ
);

INSERT INTO tenants (id, expires_at)
VALUES ('00000000-0000-0000-0000-000000000001', NULL);

CREATE TABLE demo_sessions (
    token_hash  TEXT        PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenants (id),
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX demo_sessions_tenant ON demo_sessions (tenant_id);

ALTER TABLE card_transactions
    ADD COLUMN tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES tenants (id);

ALTER TABLE documents
    ADD COLUMN tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES tenants (id);

ALTER TABLE matches
    ADD COLUMN tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES tenants (id);

ALTER TABLE idempotency_keys
    ADD COLUMN tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES tenants (id);

ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (tenant_id, key, endpoint);

CREATE INDEX card_transactions_tenant ON card_transactions (tenant_id);
CREATE INDEX documents_tenant ON documents (tenant_id);
CREATE INDEX matches_tenant ON matches (tenant_id);

CREATE INDEX tenants_expired
    ON tenants (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE OR REPLACE FUNCTION prevent_reconciliation_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('spendrecon.purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'reconciliation_events is append-only';
END;
$$;
