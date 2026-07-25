-- Customer wishlist (roadmap item 3, stories/customer_wishlist.md).
--
-- A saved-for-later list per (customer, listing). The heart on a product card is one row here; the
-- portal "My wishlist" page is a read of them resolved back through the PUBLISHED catalog.
--
-- Both foreign keys CASCADE on delete, so the table self-heals: purging a customer or deleting a
-- listing takes its saved rows with it — there is no orphan state to reconcile and nothing to
-- clean up on a schedule.
--
-- The UNIQUE pair is what makes every add idempotent (ON CONFLICT DO NOTHING): hearting twice, and
-- the client-side merge replaying a guest's list on every login, both converge to one row.
CREATE TABLE customer_wishlist (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES org(id),
    customer_id         UUID NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    product_listing_id  UUID NOT NULL REFERENCES product_listing(id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (customer_id, product_listing_id)
);

-- The owner read is always "this customer's saved listings, newest first" — the index carries the
-- ordering so the page never sorts.
CREATE INDEX customer_wishlist_owner_idx ON customer_wishlist (org_id, customer_id, created_at DESC);
