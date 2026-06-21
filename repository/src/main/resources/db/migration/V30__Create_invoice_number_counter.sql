-- Per-org per-year counter for gapless invoice numbers (INV-YYYY-NNNN).
-- Unlike order_number_counter, invoice numbers ARE required to be gapless for
-- Egyptian ETA / tax compliance: the counter is advanced via SELECT … FOR UPDATE
-- inside the SAME transaction that issues the invoice, so if that transaction
-- rolls back the increment rolls back with it — no number is ever burned.
CREATE TABLE invoice_number_counter (
    org_id    UUID   NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    year      INT    NOT NULL,
    next_val  BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (org_id, year)
);
