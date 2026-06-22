-- Cached running total of money refunded against this payment, mirroring sales_invoice.paid_amount.
-- Lets payment status derive cleanly from (unallocated_amount, refunded_amount) without summing the
-- refund/refund_allocation tables on every read. CreditNote-backed refunds move money from the
-- allocated portion into refunded_amount; direct-from-Payment refunds move it from unallocated_amount.
ALTER TABLE payment
    ADD COLUMN refunded_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0);
