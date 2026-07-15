-- Slice R1 (storefront_reviews.md): verified-purchase listing reviews.
--
-- A review is customer-authored content on a public listing: rating 1..5 + optional plain-text
-- body, written on the portal plane (eligibility = a DELIVERED fulfillment line for the listing's
-- product, checked in the service), moderated on the staff plane (PENDING → APPROVED | REJECTED),
-- and served anonymously (APPROVED only) with a computed aggregate. display_name is frozen from
-- customer.name at write time (the invoice customer_name snapshot precedent) so the public row
-- never joins back to the CRM record. One review per (customer, listing) — a resubmission is an
-- edit and returns the row to PENDING (re-moderation); the UNIQUE key is what the service upserts
-- on. See frontst/docs/storefront-reviews-epic.md §2-§3, §6, §8.
CREATE TABLE listing_review (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID        NOT NULL REFERENCES org(id),
    product_listing_id  UUID        NOT NULL REFERENCES product_listing(id),
    customer_id         UUID        NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    rating              SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body                TEXT,                -- <= 2000 chars (service); plain text, stored verbatim
    display_name        TEXT        NOT NULL,
    status              TEXT        NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (customer_id, product_listing_id)
);

-- Public read + aggregate: APPROVED rows of one listing, newest first.
CREATE INDEX listing_review_public_idx
    ON listing_review (product_listing_id, status, created_at DESC);

-- Staff moderation worklist: the org's queue (?status=PENDING oldest-first) / ledger.
CREATE INDEX listing_review_moderation_idx
    ON listing_review (org_id, status, created_at);
