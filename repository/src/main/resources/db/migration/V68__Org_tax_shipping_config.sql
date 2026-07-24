-- Config-complete tax + shipping fee (roadmap item 5, stories/org_tax_shipping_config.md).
-- The money fields threaded through orders/invoices already compute (per-line tax_rate, order
-- tax_total) but were pinned to 0 in SalesOrderService. Make them per-org settings:
--   * org.tax_rate      — the fraction (0.1400 = 14%) applied to every order line at placement.
--   * org.shipping_fee  — a flat per-order delivery fee for ONLINE/PHONE orders (never IN_STORE).
-- Shipping is deliberately a SCALAR on the order/invoice, not an order line: sales_order_line
-- requires a product FK and the reservation engine iterates every line, so a synthetic shipping
-- line would 404/409 at placement. The invoice carries it on the FIRST live invoice of the order
-- (so the sum of live invoice grand totals still equals the order grand total).
ALTER TABLE org
    ADD COLUMN tax_rate     NUMERIC(6,4)  NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate <= 1),
    ADD COLUMN shipping_fee NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (shipping_fee >= 0);

ALTER TABLE sales_order
    ADD COLUMN shipping_total NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (shipping_total >= 0);

ALTER TABLE sales_invoice
    ADD COLUMN shipping_total NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (shipping_total >= 0);
