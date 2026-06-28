-- Replace the plain UNIQUE on sales_invoice.fulfillment_id with a PARTIAL unique index that
-- ignores VOID rows. Rationale: voiding an invoice and re-issuing a corrected one against the
-- same fulfillment leaves the voided row in place. Under the old constraint the second (live)
-- invoice would collide on fulfillment_id; the partial index enforces "at most one LIVE invoice
-- per fulfillment" while allowing any number of VOID siblings.
ALTER TABLE sales_invoice DROP CONSTRAINT IF EXISTS sales_invoice_fulfillment_id_key;

CREATE UNIQUE INDEX sales_invoice_fulfillment_id_live_uq
  ON sales_invoice (fulfillment_id)
  WHERE status <> 'VOID';
