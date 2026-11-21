-- Hok, 2026-11-21. A match with no receipt has no score. A match that holds a
-- receipt always has a score from 0 to 1. An EXCEPTION still holds its receipt,
-- so the one-live-match index is unchanged.

ALTER TABLE matches ALTER COLUMN score DROP NOT NULL;

ALTER TABLE matches
    ADD CONSTRAINT matches_score_iff_document
    CHECK ((score IS NULL) = (document_id IS NULL));

ALTER TABLE matches
    ADD CONSTRAINT matches_score_range
    CHECK (score IS NULL OR score BETWEEN 0 AND 1);

COMMENT ON INDEX matches_one_live_per_document IS 'EXCEPTION holds its receipt';
