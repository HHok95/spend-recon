-- Exact duplicate key (Hok, 2026-11-22). The same receipt or invoice number from
-- the same merchant cannot be stored twice. The index normalises the number:
-- trim, then lowercase. NULL and blank numbers are outside the index, so
-- documents with no number are not exact duplicates of each other.
--
-- document_number is new and nullable. Rows already in the table have NULL,
-- so they are not in the index and this migration does not fail on them.

ALTER TABLE documents
    ADD COLUMN document_number TEXT;

CREATE UNIQUE INDEX documents_merchant_document_number
    ON documents (merchant_norm, lower(btrim(document_number)))
    WHERE document_number IS NOT NULL
      AND btrim(document_number) <> '';
