-- Storefront branding + payment instructions: the small set of per-org identity a public,
-- anonymous storefront and its checkout confirmation render (Souq-style, one skin over many
-- merchants). All nullable except default_locale (which has a safe default) so a pre-branding org
-- still serves a valid public profile — null logo/theme/pay-text, landing locale 'ar'.
-- logo_object_key is already carried by the billing profile (V51) and reused here for the logo.
-- See stories/storefront_org_profile.md (B1) and frontst/docs/storefront-commerce-epic.md.
ALTER TABLE org
    ADD COLUMN theme_color          VARCHAR(7),
    ADD COLUMN instapay_handle      VARCHAR(255),
    ADD COLUMN payment_instructions TEXT,
    ADD COLUMN default_locale       VARCHAR(5) NOT NULL DEFAULT 'ar'
        CHECK (default_locale IN ('ar', 'en'));
