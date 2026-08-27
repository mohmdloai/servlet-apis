-- Refund from the receipt — a counter return in one transaction (stories/counter_return.md).
--
-- The credit-note primitives (V25/V26) already model "credit goods previously billed, then refund
-- against the note". The counter return composes them — issue → refund → execute → restock — in
-- one transaction, and needs three facts the desk-issued note never had to carry:
--
--   discount_total  — the returned lines' share of the invoice's discount (a counter discount, V88,
--                     or a coupon). A credit-note line carries the invoice line's GROSS unit price,
--                     while the invoice's cap is its NET grand total; without this column a return
--                     on a discounted sale either over-credits (100 for goods paid 90) or is refused
--                     outright ("300 exceeds 270"). total = subtotal + tax_total − discount_total.
--                     Existing notes backfill to 0 and their arithmetic is unchanged.
--   restocked_at    — set when the return put the goods back on the shelf (the shape of
--                     fulfillment.returned_at); NULL for restock:false and for every desk-issued
--                     note. The inventory_log rows (reason RETURNED, minted in V5 and never written
--                     until now) are the detail; this is the flag a screen reads without scanning
--                     the ledger.
--   idempotency_key — the counter path moves money on one tap, so a retried tap must return the
--                     prior result, never a second note. Nullable: only the counter path writes it.

ALTER TABLE credit_note
    ADD COLUMN discount_total  NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (discount_total >= 0),
    ADD COLUMN restocked_at    TIMESTAMPTZ,
    ADD COLUMN idempotency_key TEXT;

CREATE UNIQUE INDEX credit_note_idem_idx ON credit_note (org_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN credit_note.discount_total IS
    'The credited lines'' prorated share of the invoice discount (round(discount × line_gross / invoice.subtotal) HALF_EVEN; the return completing the invoice takes the exact remainder). total = subtotal + tax_total − discount_total. 0 on every desk-issued note.';
COMMENT ON COLUMN credit_note.restocked_at IS
    'When a counter return put the goods back on the shelf (inventory_log reason RETURNED, order-linked). NULL when the return kept the goods out of stock or the note was issued from the admin form.';
COMMENT ON COLUMN credit_note.idempotency_key IS
    'The Idempotency-Key of the counter return that issued this note; a replay returns it instead of issuing again. NULL for desk-issued notes.';
