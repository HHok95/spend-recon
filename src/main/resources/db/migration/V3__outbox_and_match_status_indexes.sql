-- The poller reads unpublished rows in id order. This partial index is that queue.
CREATE INDEX outbox_unpublished
    ON outbox (id)
    WHERE published_at IS NULL;

-- GET /matches?status= filters on this column.
CREATE INDEX matches_status
    ON matches (status);
