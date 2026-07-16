# Fix: owner org DTO must echo the SEO/og fields (C2 read-back gap)

> Follow-up fix to slice C2 ([`storefront_seo_metadata.md`](storefront_seo_metadata.md)). That slice's
> scope said "org admin responses echo the fields" (§In), but the **owner-plane** DTO `OrgResponse`
> — served by `GET`/`PUT /api/orgs/{orgId}` — was never updated. It stops at the V52 branding fields
> (`default_locale`), silently dropping the V54 SEO trio. The public profile and the admin plane were
> wired; the owner read-back was the one path missed, so the Settings → Storefront form couldn't
> re-hydrate what the owner had just saved.

---

## Symptom

In **Settings → Storefront → "Sharing & SEO"** (frontend story 31):

- A freshly uploaded og image previews correctly (from a local `URL.createObjectURL` blob), but on
  navigating away and back the preview falls back to **the org logo** — never the stored og image.
- `meta_title` / `meta_description` don't repopulate after a reload; they survive only in local form
  state within one component mount.

WhatsApp/social unfurls stay correct throughout — they read the *public* profile
(`GET /api/public/{orgSlug}`) and the stable `og-image` stream, both of which were wired in C2. Only
the **owner admin preview** was blind.

## Root cause

`api/.../dto/OrgResponse.java` never serialized `meta_title`, `meta_description`, or
`og_image_object_key`. The `Org` domain model carries all three (getters + setters, V54), and the
frontend `OrgDTO` / `toOrgSettings` already read `dto.meta_title` / `dto.meta_description` /
`dto.og_image_object_key` via `profileField(...)`. With Jackson `NON_NULL`, the absent fields arrive
`undefined` → map to `null`. In `StorefrontSeoSection.tsx`, `hasStoredOg = org.seo.ogImageObjectKey
!== null` is therefore **permanently false**, so the `ogImagePath(org.slug, org.updatedAt)` branch is
dead and `previewImage` falls back to `logoUrl` on every remount.

This is **not** a caching problem — the Next Data Cache `no-store`, the PWA service worker (excludes
`/api/*`), and the `?v=` buster all behave correctly and are uninvolved.

## Fix

Backend-only, in `OrgResponse` — add the three V54 fields, copy them in `from(Org)`, add the getters.
Jackson's SNAKE_CASE serializes them to `meta_title` / `meta_description` / `og_image_object_key` —
exactly the names the frontend already expects, so **no frontend change is needed**. `NON_NULL`
keeps an unset og key absent, which the frontend correctly reads as "no stored og image → logo
fallback" (rather than pointing the stream at nothing).

## Acceptance criteria

1. `GET /api/orgs/{orgId}` and `PUT /api/orgs/{orgId}` responses carry `meta_title`,
   `meta_description`, and `og_image_object_key` when set on the org.
2. Each of the three is **omitted** (not JSON `null`) when unset — the `NON_NULL` contract, so the
   frontend's `!== null` og-fallback logic holds.
3. No frontend change: `toOrgSettings` already maps these fields; the Settings form re-hydrates the
   meta text and the og-image preview distinguishes stored-og from logo-fallback after a reload.

## Tests

`OrgResponseSeoFieldsTest` (new) — serializes `OrgResponse.from(org)` through the app's real
SNAKE_CASE `ObjectMapper` and asserts (a) the three fields surface on the wire with their values, and
(b) they're absent when unset. This guards the exact regression: the C2 ITs (`OrgSeoMetadataIT`)
exercise `OrgService.update` and the public profile view, never the owner DTO, which is why the gap
went unnoticed.

## Related

- Slice C2: [`storefront_seo_metadata.md`](storefront_seo_metadata.md) — introduced the fields.
- PR #76 (`og_image_version`) fixed a *different* root cause on the **public** plane (a changed share
  image busting social/browser caches). This fix is the **owner** plane's read-back — distinct DTO,
  distinct symptom.
