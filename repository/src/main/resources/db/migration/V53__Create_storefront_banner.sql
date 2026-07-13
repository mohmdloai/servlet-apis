-- C1: merchant-managed home banners (storefront_banner). Moves the storefront's home deal-banner
-- carousel out of a hard-coded frontend config (apps/storefront/src/shared/config/home-banners.ts)
-- into org-scoped, editable rows behind an admin API and a cached anonymous read. Sets the three
-- precedents the rest of the customization epic reuses: the bilingual paired-column pattern (epic
-- §1), slug-keyed structured targets (epic §2 — never a free-text href, so an open redirect is
-- unrepresentable), and server-side stale-target degradation in the public read (epic §3).
--
-- Both headline columns are nullable at the DB; the "default-locale headline required" rule is
-- service-enforced (StorefrontBannerService) so an AR-default org may ship AR-only copy. There is
-- NO price / discount / "was/now" / countdown column — ever (epic §7, the honesty rule); an
-- information_schema scan in PublicBannersIT pins that structurally.
-- See stories/storefront_banners.md (C1) and frontst/docs/storefront-customization-epic.md.
CREATE TABLE storefront_banner (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           UUID         NOT NULL REFERENCES org(id),
    -- Bilingual copy: default-locale one required (service-enforced), the other optional.
    headline_ar      TEXT,
    headline_en      TEXT,
    subheading_ar    TEXT,
    subheading_en    TEXT,
    -- Optional banner image, an org-scoped {orgId}/banner/… key (absent → branded gradient slide).
    image_object_key TEXT,
    -- Structured, slug-keyed target within the org — never a free-text URL.
    target_type      TEXT         NOT NULL CHECK (target_type IN ('category', 'listing')),
    target_slug      TEXT         NOT NULL,
    sort_order       INTEGER      NOT NULL DEFAULT 0,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Optional display window; the public read filters on now() between the (null = open) bounds.
    starts_at        TIMESTAMPTZ,
    ends_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT storefront_banner_window_chk
        CHECK (starts_at IS NULL OR ends_at IS NULL OR starts_at < ends_at)
);

-- The admin list (org_id, sort_order) and the public read's active filter both ride this index.
CREATE INDEX storefront_banner_org_active_sort_idx
    ON storefront_banner (org_id, active, sort_order);
