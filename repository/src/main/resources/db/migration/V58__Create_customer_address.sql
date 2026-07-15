-- Slice P4 (portal_addresses_reorder.md): the customer's reusable saved-address book.
--
-- Distinct from customer.address, which is a single free-text line frozen onto an order at checkout
-- (an order snapshot). This table lets a logged-in customer keep several labelled addresses and mark
-- exactly one as their default, without ever mutating a past order's snapshot. Orders never FK to it,
-- so a delete here is always safe. Scoped (org_id, customer_id); a partial unique index enforces the
-- one-default-per-customer invariant at the database, so a race can never leave two defaults.
CREATE TABLE customer_address (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID        NOT NULL REFERENCES org(id),
    customer_id  UUID        NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    label        VARCHAR(120),
    recipient    VARCHAR(200),
    phone        VARCHAR(40),
    address      TEXT        NOT NULL,
    is_default   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX customer_address_owner_idx ON customer_address (org_id, customer_id);

-- At most one default per customer — enforced by the DB, not just the service.
CREATE UNIQUE INDEX customer_address_one_default_idx
    ON customer_address (org_id, customer_id)
    WHERE is_default;
