-- Payment claims carry the order the shopper said they paid (stories/payment_claim_verify.md).
--
-- claimed_sales_order_id is deliberately NULLABLE and deliberately NOT payment.sales_order_id:
-- the payment column answers "where did the money go" (written by reconciliation, once the
-- transfer is verified); this column answers "which order did the shopper SAY this transfer pays"
-- (written when the claim is filed) and survives that answer being wrong. Only rows born as a
-- shopper claim set it — an admin's free-form record and an in-store tender leave it NULL.
--
-- not_found_reason is the manager's answer when the bank shows nothing under the reference
-- (verification_status NOT_FOUND); cleared again when the claim is re-opened or verified.
ALTER TABLE payment_transaction
  ADD COLUMN claimed_sales_order_id UUID REFERENCES sales_order(id),
  ADD COLUMN not_found_reason       TEXT;

-- The order page's "customer says they paid" card and the record path's pending-claim guard both
-- ask "open claims on THIS order" — a partial index over the two open states keeps that a
-- point lookup regardless of how large the verified ledger grows.
CREATE INDEX idx_txn_claim_open ON payment_transaction (claimed_sales_order_id)
  WHERE verification_status IN ('UNVERIFIED', 'NOT_FOUND');
