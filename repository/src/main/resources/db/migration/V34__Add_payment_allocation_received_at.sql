-- payment_allocation carries received_at — copied from its parent Payment at insert time — so the
-- FIFO refund-unwind order (received_at ASC, id ASC, per sys-analysis/outbound/refund.md) reads
-- straight off the allocation row being sorted, instead of joining back to payment on every read.
-- Backfill existing rows from their payment before enforcing NOT NULL.
ALTER TABLE payment_allocation
    ADD COLUMN received_at TIMESTAMPTZ;

UPDATE payment_allocation pa
   SET received_at = p.received_at
  FROM payment p
 WHERE pa.payment_id = p.id;

ALTER TABLE payment_allocation
    ALTER COLUMN received_at SET NOT NULL;
