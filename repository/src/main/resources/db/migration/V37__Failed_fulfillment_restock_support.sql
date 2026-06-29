-- Failed-fulfillment resolution support (sys-analysis/outbound/fulfillment.md §FAILED).
-- A SHIPPED fulfillment that never arrives moves to FAILED. When the goods physically come back
-- to the warehouse the admin records a return: a positive inventory_log entry tagged with this
-- reason (the spec's due_to='failed_fulfillment'). returned_at makes that restock idempotent —
-- the goods come back at most once, so a second return is rejected rather than double-counting.

ALTER TYPE stock_reason ADD VALUE IF NOT EXISTS 'RESTOCKED_FAILED_FULFILLMENT';

ALTER TABLE fulfillment ADD COLUMN returned_at timestamptz;
