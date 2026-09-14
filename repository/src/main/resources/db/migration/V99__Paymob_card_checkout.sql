-- Slice 2 of the Paymob card epic: pay an online order by card.
-- See stories/paymob_card_checkout.md and docs/paymob-card-epic.md.
--
-- One new concept — the payment_intent, an ATTEMPT to collect — and nothing else. The money that
-- actually moves is a payment_transaction (V22) + a payment like every other rail; the webhook that
-- records it dedupes on payment_transaction's UNIQUE (provider, provider_ref) exactly as InstaPay.

CREATE TABLE payment_intent (
    id                 UUID             PRIMARY KEY,
    org_id             UUID             NOT NULL REFERENCES org(id),
    -- An intent has no meaning without its order (an attempt to collect for it); a transaction is
    -- money that really moved and outlives everything — hence CASCADE here and never there.
    sales_order_id     UUID             NOT NULL REFERENCES sales_order(id) ON DELETE CASCADE,

    provider           payment_provider NOT NULL,          -- 'paymob_card' today

    -- What we asked Paymob to collect, frozen at creation. The webhook is checked against THIS,
    -- not only against the order: an intent minted for an older, cheaper total must never settle a
    -- repriced order (epic §Money).
    amount             NUMERIC(14,2)    NOT NULL CHECK (amount > 0),
    currency           CHAR(3)          NOT NULL DEFAULT 'EGP',

    -- Paymob's handles. intention_id is what slice 3's inquiry is made by. paymob_order_id is the
    -- Paymob-side order the intention created — it is the ONE identifier of ours that appears in
    -- the webhook's SIGNED field list (obj.order.id), so the callback is bound to its intent by a
    -- signed value, not only by the unsigned merchant_order_id echo of special_reference.
    -- client_secret is what the shopper's browser needs to open Unified Checkout; kept so a second
    -- tap inside the TTL returns the same checkout URL instead of minting a second intention.
    intention_id       TEXT,
    paymob_order_id    TEXT,
    client_secret      TEXT,

    -- Our id echoed back by Paymob. Paymob rejects a duplicate per merchant account, which is why
    -- this is the row's UUID and not the order number: a second attempt needs a second value.
    special_reference  TEXT             NOT NULL,

    status             TEXT             NOT NULL
                       CHECK (status IN ('PENDING', 'SETTLED', 'FAILED', 'EXPIRED')),

    -- Set when a webhook attributes a transaction to this intent (a settlement OR a declined
    -- attempt). Not a FK to payment_transaction: the transaction is recorded by provider_ref and
    -- may exist before it is matched here.
    settled_txn_ref    TEXT,

    expires_at         TIMESTAMPTZ      NOT NULL,
    created_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ      NOT NULL DEFAULT now(),

    UNIQUE (org_id, special_reference)
);

COMMENT ON TABLE payment_intent IS
    'An attempt to collect an order''s outstanding amount through a PSP (Paymob card, V99). One row per intention minted; the money itself is a payment_transaction.';
COMMENT ON COLUMN payment_intent.amount IS
    'The amount quoted to the PSP, frozen at creation. A webhook whose amount disagrees is recorded as an ORPHAN transaction, never settled.';
COMMENT ON COLUMN payment_intent.client_secret IS
    'Paymob''s per-intention checkout secret. Handed to the shopper''s browser by design (it opens Unified Checkout for this one intention); not a merchant credential.';

-- Slice 3's sweeper reads (status, expires_at); the partial index keeps it off the settled majority.
CREATE INDEX idx_payment_intent_pending ON payment_intent (expires_at)
    WHERE status = 'PENDING';

-- POST …/pay reuses a live PENDING intent for the same order rather than minting a second one;
-- Postgres does not index FK columns on its own.
CREATE INDEX idx_payment_intent_order ON payment_intent (sales_order_id);
