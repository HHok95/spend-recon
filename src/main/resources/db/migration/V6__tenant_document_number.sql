-- The receipt-number key from V4 is per merchant. A demo tenant must be able to
-- use a number that another tenant already stored, and must not see that row.
--
-- A purge may delete an audit row only when that row's tenant has already
-- expired. The local tenant is never eligible, even if its expiry is set.

DROP INDEX documents_merchant_document_number;

CREATE UNIQUE INDEX documents_merchant_document_number
    ON documents (tenant_id, merchant_norm, document_number_norm)
    WHERE document_number_norm IS NOT NULL;

CREATE OR REPLACE FUNCTION prevent_reconciliation_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND current_setting('spendrecon.purge', true) = 'on'
       AND EXISTS (
            SELECT 1
            FROM matches m
            JOIN tenants t ON t.id = m.tenant_id
            WHERE m.id = OLD.match_id
              AND t.id <> '00000000-0000-0000-0000-000000000001'
              AND t.expires_at IS NOT NULL
              AND t.expires_at <= now()
       )
    THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'reconciliation_events is append-only';
END;
$$;
