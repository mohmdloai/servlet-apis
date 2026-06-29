-- Failed-fulfillment resolution tracking (sys-analysis/outbound/fulfillment.md §FAILED).
-- A FAILED fulfillment is resolved at most once — refunded OR replaced. `resolution` records the
-- choice so the two paths are mutually exclusive (and refund becomes idempotent). A replacement
-- Fulfillment points back at the one it re-ships via `replaces_fulfillment_id` (audit lineage).

ALTER TABLE fulfillment
    ADD COLUMN resolution text CHECK (resolution IN ('REFUNDED', 'REPLACED')),
    ADD COLUMN replaces_fulfillment_id uuid REFERENCES fulfillment(id);
