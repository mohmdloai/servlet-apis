-- Idempotency for restock lives on the ledger row the operation already writes — consistent with the
-- project-wide idempotency surface (FLOW.md §2: the key sits on the business row, e.g.
-- sales_order.idempotency_key, payment_transaction (provider, provider_ref)), not a side table. The
-- inventory_log insert IS the claim: a caller-supplied Idempotency-Key on restock is recorded here,
-- and the partial unique index makes a replay a no-op (backend story product_barcode_lookup.md
-- §Idempotent restock; the seam the deferred offline stock-take queue replays against).
--
-- Nullable so every other movement (reserve/release/ship/sale/adjust) writes NULL and never collides;
-- the ledger row already carries stock_delta + product_id, so a replay can be fingerprint-checked
-- (same key + different qty/product ⇒ a client bug surfaced as 409, not silently swallowed).
ALTER TABLE inventory_log ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX inventory_log_org_idempotency_key_unique
    ON inventory_log (org_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
