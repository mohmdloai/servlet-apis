-- Per-org per-year counter for human-readable order numbers (SO-YYYY-NNNNN).
-- is NOT required to be gapless.
CREATE TABLE order_number_counter (
    org_id    UUID   NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    year      INT    NOT NULL,
    next_val  BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (org_id, year)
);
