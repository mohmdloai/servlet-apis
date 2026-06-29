-- Dispute audit fields for the post-allocation dispute lifecycle (sys-analysis/outbound/payment.md
-- §Disputed; state-machines.md F). status already carries DISPUTED (V4); these record *when* and
-- *why* a payment was disputed, symmetric with sales_order.cancelled_at. Nullable — set on dispute,
-- retained after an uphold as the historical record of the resolved dispute.
ALTER TABLE payment
    ADD COLUMN disputed_at    timestamptz,
    ADD COLUMN dispute_reason text;
