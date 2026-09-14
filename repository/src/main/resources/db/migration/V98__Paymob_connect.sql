-- Slice 1 of the Paymob card epic: connect a per-org merchant account.
-- See stories/paymob_connect.md and docs/paymob-card-epic.md.
--
-- This migration adds the enum value and the credential table only — no payment can be taken yet.
-- The first row using 'paymob_card' is written by slice 2 (V99), several migrations later.

-- PostgreSQL 17 permits ADD VALUE inside Flyway's transaction, but the new value cannot be used in
-- that same transaction — same one-line shape as V37 and V93, the two prior enum additions.
ALTER TYPE payment_provider ADD VALUE IF NOT EXISTS 'paymob_card';

-- Per-tenant Paymob merchant credentials — the OWNER decision that each org connects its own
-- account (org.instapay_handle's shape) rather than the platform holding merchant money.
--
-- This is the SECOND per-tenant secret this system stores (org_whatsapp_config, V82, was the
-- first) and it follows that precedent exactly: *_encrypted columns are AES-GCM ciphertext under a
-- platform key (SecretBox), sealed under their OWN env var (PAYMOB_CREDENTIAL_KEY, not
-- WHATSAPP_TOKEN_KEY), never read back out by any path.
CREATE TABLE org_paymob_config (
    org_id                 UUID PRIMARY KEY REFERENCES org(id) ON DELETE CASCADE,

    -- Public by design: handed to the shopper's browser to open Unified Checkout.
    public_key             TEXT NOT NULL,

    -- Charges cards as the merchant. AES-GCM under PAYMOB_CREDENTIAL_KEY. Never read back out.
    secret_key_encrypted   TEXT NOT NULL,

    -- Verifies webhook signatures. Equally fatal if leaked: whoever holds it can forge a
    -- "payment succeeded" this system would believe. Same treatment, same reason.
    hmac_secret_encrypted  TEXT NOT NULL,

    -- Which of the merchant's Paymob integrations to charge. Capture-on-sale — an auth-only
    -- integration will not settle, and slice 2 warns rather than mis-recording it.
    card_integration_id    INTEGER NOT NULL,

    -- Paymob is regional and the host differs per country (accept./uae./ksa./oman./pakistan.).
    -- Stored rather than global: a platform serving two countries has orgs in both. Ships with a
    -- single permitted value on purpose — every other region is unverified until an org actually
    -- onboards there.
    region                 TEXT NOT NULL DEFAULT 'EGYPT' CHECK (region IN ('EGYPT')),

    status                 TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    connected_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE org_paymob_config IS
    'Per-merchant Paymob card account. One row per connected org; absence means the org offers no card method and the storefront profile simply omits it from payment_methods.';
COMMENT ON COLUMN org_paymob_config.secret_key_encrypted IS
    'AES-GCM ciphertext under PAYMOB_CREDENTIAL_KEY. Never returned by any read path, never logged.';
COMMENT ON COLUMN org_paymob_config.hmac_secret_encrypted IS
    'AES-GCM ciphertext under PAYMOB_CREDENTIAL_KEY. Verifies inbound webhooks; never returned by any read path.';

-- No index: the PK is the only access path (resolve one org's config), same as org_whatsapp_config.
