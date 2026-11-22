-- Exact duplicate key (Hok, 2026-11-22). The same receipt or invoice number from
-- the same merchant cannot be stored twice.
--
-- document_number is the submitted number after Unicode whitespace is trimmed.
-- document_number_norm is that value lowercased. The application writes both
-- from one function, and this index does not trim or case-fold again.
-- NULL means the document has no number. Those rows are outside the index.
--
-- Both columns are new and nullable. Rows already in the table get NULL,
-- so they are not in the index and this migration does not fail on them.

ALTER TABLE documents
    ADD COLUMN document_number TEXT,
    ADD COLUMN document_number_norm TEXT;

CREATE UNIQUE INDEX documents_merchant_document_number
    ON documents (merchant_norm, document_number_norm)
    WHERE document_number_norm IS NOT NULL;
