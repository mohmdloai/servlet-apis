-- Order-scoped magic tokens — the narrow "capability" tier of the customer-portal-future.md sketch
-- (docs/notifications-plan.md §7). A transactional email carries a raw, full-entropy token in its URL;
-- this table stores only its SHA-256 hash. Each token unlocks exactly ONE resource for ONE purpose,
-- so a leaked link exposes one order, never an account. NOT a session — no login is minted here.
--
-- Phase 2 issues VIEW_ORDER only; VIEW_INVOICE (invoice-email events) and UNSUBSCRIBE (preferences,
-- Phase 3) are in the CHECK so widening the set needs no migration churn. purpose is TEXT+CHECK to
-- match the notification family.

CREATE TABLE customer_magic_token (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID        NOT NULL REFERENCES org(id),
    customer_id  UUID        NOT NULL REFERENCES customer(id) ON DELETE CASCADE,

    token_hash   VARCHAR(64) NOT NULL UNIQUE,                 -- SHA-256 hex; the raw token lives only in the URL
    purpose      TEXT        NOT NULL CHECK (purpose IN ('VIEW_ORDER', 'VIEW_INVOICE', 'UNSUBSCRIBE')),
    resource_id  UUID,                                        -- the order/invoice this token unlocks

    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,                                 -- one-shot purposes only; stays NULL for VIEW_ORDER
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Hot lookup path: validate a presented token by hash, ignoring already-consumed one-shot tokens.
CREATE INDEX customer_magic_token_lookup ON customer_magic_token (token_hash) WHERE consumed_at IS NULL;

-- Cleanup scans for an eventual purge job (delete rows well past expiry).
CREATE INDEX customer_magic_token_cleanup ON customer_magic_token (expires_at);
