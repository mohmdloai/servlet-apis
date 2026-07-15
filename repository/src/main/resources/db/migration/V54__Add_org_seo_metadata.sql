-- C2: storefront SEO & social metadata (customization epic slice C2). Three nullable columns on
-- org that shape how a pasted store link unfurls in WhatsApp/Instagram/TikTok: a merchant-written
-- meta_title / meta_description, and og_image_object_key — a {orgId}/og/… key (service-guarded,
-- epic §4) whose bytes are streamed by the STABLE GET /api/public/{orgSlug}/og-image route
-- (never a presigned URL in a meta tag — social crawlers cache past the ~900s presign TTL, epic §6).
--
-- Deliberately mono-lingual (v1): og/meta scraping is locale-less — a crawler fetches one canonical
-- set per URL and social caches don't vary by reader — so one meta_title/meta_description per store,
-- matching the payment_instructions precedent. Length limits are enforced in the service
-- (meta_title ≤ 70, meta_description ≤ 200), generous over the ~60/~160 display-truncation the
-- frontend guides toward. All nullable so a pre-C2 org still serves a valid profile (fallbacks in
-- the frontend's resolveSeo: org name / localized default / logo-backed og route).
-- See stories/storefront_seo_metadata.md (C2) and frontst/docs/storefront-customization-epic.md §6.
ALTER TABLE org
    ADD COLUMN meta_title          TEXT,
    ADD COLUMN meta_description    TEXT,
    ADD COLUMN og_image_object_key TEXT;
