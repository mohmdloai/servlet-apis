-- Slice B of the notification-reach epic: WhatsApp as a third delivery channel.
-- See stories/whatsapp_channel.md and frontst/docs/notification-reach-epic.md.
--
-- V44 line 52 annotated this column `-- sms/whatsapp: future`. This is that future. Unlike a new
-- notification TYPE (open TEXT, no migration), a new CHANNEL costs a schema change — the asymmetry
-- the epic exists to make visible.

ALTER TABLE notification_delivery
    DROP CONSTRAINT notification_delivery_channel_check;

ALTER TABLE notification_delivery
    ADD CONSTRAINT notification_delivery_channel_check
        CHECK (channel IN ('in_app', 'email', 'whatsapp'));

-- The opt-out table constrains `channel` independently (V46) — widen it too, or a customer could
-- never turn WhatsApp off and the PUT would 500 on a constraint violation. Found by a test that
-- tried to opt out; the two CHECKs are separate columns on separate tables and nothing but this
-- comment keeps them in step.
ALTER TABLE notification_preference
    DROP CONSTRAINT notification_preference_channel_check;

ALTER TABLE notification_preference
    ADD CONSTRAINT notification_preference_channel_check
        CHECK (channel IN ('in_app', 'email', 'whatsapp'));

-- The channel-specific subtype, mirroring notification_delivery_email.
--
-- WhatsApp business-initiated messages are PARAMETERIZED TEMPLATES, not free text: outside the
-- 24-hour customer-service window Meta only accepts a pre-approved template name plus ordered
-- body parameters. So this stores what was actually sent as a template invocation — the rendered
-- sentence lives on `notification.body` for the feed and the email, and cannot be reused here.
--
-- `template_language` is stored per row rather than derived, because Meta approves a template per
-- language: `order_shipped` in `ar` and in `en` are two separately-approved artefacts, and a
-- delivery record that cannot say which one it invoked is not an audit trail.
CREATE TABLE notification_delivery_whatsapp (
    delivery_id         UUID PRIMARY KEY REFERENCES notification_delivery(id) ON DELETE CASCADE,
    to_number           TEXT NOT NULL,              -- E.164, from customer.phone_e164 (V79)
    template_name       TEXT NOT NULL,
    template_language   VARCHAR(5) NOT NULL,
    template_params     JSONB,                      -- ordered body parameters, as sent
    provider_message_id TEXT,                       -- Meta's wamid, for reconciling a webhook later
    provider_status     TEXT,                       -- delivered/read/failed, once webhooks land
    provider_error      TEXT
);

-- Per-tenant WhatsApp Business Account credentials.
--
-- OWNER DECISION: per-merchant WABA, not a platform sender — every message carries the merchant's
-- own number and brand. The cost of that decision lands here: this is the FIRST per-tenant secret
-- this system stores. SMTP credentials are process-wide env vars; an access token that can send
-- messages AS a merchant cannot be.
--
-- `access_token_encrypted` is ciphertext, never the raw token: AES-GCM under a platform key from
-- the WHATSAPP_TOKEN_KEY env var (see common/crypto/SecretBox). The column is named for what it
-- holds so that a future reader cannot mistake it for a plaintext credential, and no read path
-- returns it — not to the merchant who supplied it, not to a platform ADMIN.
--
-- A separate table rather than columns on `org`: this is an integration with its own lifecycle
-- (connect, verify, fail, disconnect) that most orgs will never have, and widening the single
-- hottest row in the schema with five mostly-NULL columns to serve it would be the wrong trade.
CREATE TABLE org_whatsapp_config (
    org_id                 UUID PRIMARY KEY REFERENCES org(id) ON DELETE CASCADE,
    waba_id                TEXT NOT NULL,
    phone_number_id        TEXT NOT NULL,
    display_phone_number   TEXT,                    -- what the shopper sees it come from
    access_token_encrypted TEXT NOT NULL,
    status                 TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    connected_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE org_whatsapp_config IS
    'Per-merchant WhatsApp Business Account. One row per connected org; absence means the org has no WhatsApp channel and channelsFor() simply omits it.';
COMMENT ON COLUMN org_whatsapp_config.access_token_encrypted IS
    'AES-GCM ciphertext under WHATSAPP_TOKEN_KEY. Never returned by any read path, never logged.';

-- No index on org_whatsapp_config: the PK is the only access path (resolve one org's config).
-- The delivery subtype needs none either — V44's idx_delivery_pending already covers the sweeper's
-- (channel, created_at) WHERE status='PENDING' hot path for every channel including this one.
