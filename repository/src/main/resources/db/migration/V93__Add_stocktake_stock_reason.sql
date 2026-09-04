-- Stocktake (stories/stocktake_count.md). A physical count's variance is posted through the
-- existing adjust action; this value lets the ledger say it was a count, not an ad-hoc fix —
-- "-2 · Stocktake" beside "-2 · Adjusted" — so a shrinkage read is WHERE reason = 'STOCKTAKE'.
-- No table, no column, no index: the session lives on the client until it posts, the
-- Idempotency-Key rides V50's inventory_log.idempotency_key, and the guard is service-side.
-- Same shape as V37 (the last value added to this enum).
ALTER TYPE stock_reason ADD VALUE IF NOT EXISTS 'STOCKTAKE';
