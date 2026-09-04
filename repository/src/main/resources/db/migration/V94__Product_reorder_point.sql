-- Reorder point (stories/reorder_point.md): the merchant's "tell me when I'm down to N", per
-- product. NULL = no rule (every existing product); 0 = tell me when it's gone. Compared against
-- available (stock_qty - reserved_qty), like the overview's low/out filters. No backfill, no index:
-- the reorder worklist is a per-org filter over the product→inventory join that is already
-- indexed, and NULL rows fall out of the predicate.
ALTER TABLE product ADD COLUMN reorder_point INT
    CONSTRAINT product_reorder_point_non_negative CHECK (reorder_point >= 0);
