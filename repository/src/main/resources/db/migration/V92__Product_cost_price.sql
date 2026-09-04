-- What the stock cost: product.cost_price + the per-line cost snapshot (stories/product_cost_and_margin.md).
--
-- product has carried base_price alone since V1. The valuation report therefore multiplies stock by
-- the SELLING price (named retail_value on purpose), top products rank by revenue, and nobody can
-- see margin, profit or what the shelf is actually worth. These two columns are the whole schema
-- behind the fix; the reads that consume them live in ReportRepositoryImpl.
--
-- product.cost_price — what a unit cost the merchant, typed on the product (one current cost, not a
--   moving average: restock is a bare quantity and there is no purchase order to hang a unit cost
--   on; weighted-average costing is a strict addition later). NULLABLE ON PURPOSE: NULL means
--   "not costed", a different fact from 0.00 ("free"), and every report that consumes it states
--   how much of the shelf it covers instead of pricing uncosted stock at zero. No default.
--
-- sales_order_line.unit_cost — product.cost_price frozen at placement, the unit_price rule one column
--   over: a cost edit tomorrow does not rewrite last month's margin. NULL when the product was
--   uncosted at the time of sale. EXISTING ROWS STAY NULL — backfilling history with today's cost
--   would fabricate margins that were never measured; those units read as "uncosted" in the reports.
--
-- Both are nullable adds with no default: catalog-only, O(1) regardless of row count. No index — the
-- valuation is already a full scan of the org's inventory, and the profit sums ride the existing
-- sales_order + line join as two more FILTERed aggregates (measured on perfdb, capture in
-- tools/seed/results/product_cost_173.txt).

ALTER TABLE product
    ADD COLUMN cost_price NUMERIC(12, 2)
        CONSTRAINT ck_product_cost_price_non_negative CHECK (cost_price IS NULL OR cost_price >= 0);

ALTER TABLE sales_order_line
    ADD COLUMN unit_cost NUMERIC(14, 2)
        CONSTRAINT ck_sol_unit_cost_non_negative CHECK (unit_cost IS NULL OR unit_cost >= 0);
