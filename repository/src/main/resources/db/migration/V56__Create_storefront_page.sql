-- C4: merchant-managed storefront text pages (storefront_page). Gives the storefront its trust
-- prose — an "about" and a "policies" page per org — as a fixed-kind, bilingual, PLAIN-TEXT store.
-- A kind-keyed table (rather than four TEXT columns on the platform's most-loaded table `org`, or a
-- free-form CMS) adds a page kind by extending one CHECK, keeps `org` clean, and gives each page its
-- own updated_at (the storefront shows "last updated"). See stories/storefront_pages.md (C4) and
-- frontst/docs/storefront-customization-epic.md (§1 bilingual paired columns, §Out no rich text).
--
-- Both body columns are nullable at the DB; the "default-locale body required" rule is
-- service-enforced (StorefrontPageService) so an AR-default org may ship AR-only copy. Bodies are
-- rendered whitespace-preserved and NEVER interpreted as HTML — XSS is unrepresentable because the
-- content is inert by construction (the type system, not a sanitizer, carries the invariant). There
-- is deliberately no `title` column — a fixed kind has a fixed, frontend-localized title (always
-- bilingual even when only one body is written).
CREATE TABLE storefront_page (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     UUID         NOT NULL REFERENCES org(id),
    -- The closed page-kind set. Adding a kind (contact/shipping/FAQ) is one CHECK value + one
    -- frontend catalog key — never a migration on `org`.
    kind       TEXT         NOT NULL CHECK (kind IN ('about', 'policies')),
    -- Bilingual copy: default-locale one required (service-enforced), the other optional.
    body_ar    TEXT,
    body_en    TEXT,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- One page per (org, kind) — the upsert key.
    UNIQUE (org_id, kind)
);
