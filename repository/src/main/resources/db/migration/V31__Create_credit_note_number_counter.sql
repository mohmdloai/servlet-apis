-- Gapless per-org per-year sequence for CreditNote numbers (CN-YYYY-NNNN), a sales-side document
-- that Egyptian tax compliance requires to be gapless — separate from the invoice sequence.
-- Mirrors invoice_number_counter (V30): the issuing transaction locks its row FOR UPDATE so the
-- increment rolls back with the transaction and no number is ever burned.
CREATE TABLE credit_note_number_counter (
    org_id    UUID   NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    year      INT    NOT NULL,
    next_val  BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (org_id, year)
);
