-- Slice R2 (storefront_comments.md): listing comments — customer asks, merchant replies.
--
-- A comment is ONE customer message + at most ONE official merchant reply (no threads, epic §5).
-- It becomes public only when the merchant replies: answering IS the moderation act (epic §4) —
-- PENDING → ANSWERED (reply) | DISMISSED (silent, terminal). The CHECK ties status to the reply
-- structurally: an ANSWERED row always has a reply, and nothing else ever does. display_name is
-- frozen at write (the R1/epic §6 rule); replied_by is an internal audit FK that never serializes
-- publicly. No per-customer uniqueness — multiple questions per listing are legitimate; flood
-- control is the rate bucket + the service's <=5-PENDING-per-listing cap.
CREATE TABLE listing_comment (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID        NOT NULL REFERENCES org(id),
    product_listing_id  UUID        NOT NULL REFERENCES product_listing(id),
    customer_id         UUID        NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    body                TEXT        NOT NULL,   -- <= 1000 chars (service); plain text, verbatim
    display_name        TEXT        NOT NULL,
    reply_body          TEXT,                   -- <= 2000; the one official answer
    replied_by          UUID        REFERENCES app_user(id),
    replied_at          TIMESTAMPTZ,
    status              TEXT        NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING','ANSWERED','DISMISSED')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK ((status = 'ANSWERED') = (reply_body IS NOT NULL))   -- answered ⇔ replied, structurally
);

-- Public read: the ANSWERED pairs of one listing, newest first.
CREATE INDEX listing_comment_public_idx
    ON listing_comment (product_listing_id, status, created_at DESC);

-- Staff answer worklist: the org's queue (?status=PENDING oldest-first) / ledger.
CREATE INDEX listing_comment_queue_idx
    ON listing_comment (org_id, status, created_at);

-- "My questions": one customer's comments, newest first.
CREATE INDEX listing_comment_mine_idx
    ON listing_comment (org_id, customer_id, created_at DESC);
