# Slice C4: Storefront text pages (about / policies)

> A real shop answers "who are you?" and "what if something goes wrong?" before a stranger pays.
> The storefront has nowhere to say either — no about page, no policies, and the org has no columns
> for them. This slice ships the smallest honest version: a fixed-kind, bilingual, **plain-text**
> page store — an upsert per `(org, kind)` and a cached public read — reusing the bilingual
> paired-column pattern C1 established.
>
> Canonical decisions: [`frontst/docs/storefront-customization-epic.md`](../../frontst/docs/storefront-customization-epic.md)
> (§1 bilingual columns + fallback, §9 authz; §Out — no rich text). Feeds frontend story 33.

---

## Goal

STAFF writes an "About" and/or a "Policies" page (AR/EN, default-locale copy required); the
storefront reads which pages exist and renders each anonymously. Plain text only — newlines
preserved, HTML never interpreted — so merchant input is inert by construction (XSS
unrepresentable, matching the no-fake-prices philosophy: the type system carries the invariant).

## Why a fixed-kind table, not columns on `org` or a CMS

Four more TEXT columns on `org` (`about_ar/en`, `policies_ar/en`) would work today and bloat
forever — and the *next* page kind means another migration on the platform's most-loaded table.
A `kind`-keyed table adds page kinds by extending one enum, keeps `org` clean, and gives each page
its own `updated_at` (the storefront shows "last updated"). A free-form CMS (arbitrary slugs,
nesting, rich text) is exactly what v1 refuses to be — fixed kinds keep routes, footer links, and
translations enumerable.

## Design

- **Migration (next `V##`)** — `storefront_page`:
  ```
  id BIGSERIAL PK · org_id BIGINT NOT NULL REFERENCES org(id)
  kind TEXT NOT NULL CHECK (kind IN ('about','policies'))
  body_ar TEXT NULL · body_en TEXT NULL                  -- default-locale one required (service)
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
  UNIQUE (org_id, kind)
  ```
  jOOQ codegen. Page *titles* are not stored — a fixed kind has a fixed, frontend-localized title
  (en+ar catalog keys), so titles are always bilingual even when the merchant wrote one body.
- **Service** — `StorefrontPageService`: upsert per `(org, kind)` (PUT is idempotent create-or-
  replace; both bodies carried whole each time — merge-null semantics buy nothing on two fields);
  default-locale body required, other optional (400 cause-naming); body length ≤ 20,000 chars
  (400 — a policies page, not a novel); `DELETE` removes the page (kind stays valid, page just
  ceases to exist).
- **Admin API** — `StorefrontPageHandler` under `OrgServlet`:
  ```
  GET    /api/orgs/{orgId}/storefront/pages            (VIEWER — all existing pages, both bodies, updated_at)
  PUT    /api/orgs/{orgId}/storefront/pages/{kind}     (STAFF — upsert {body_ar?, body_en?})
  DELETE /api/orgs/{orgId}/storefront/pages/{kind}     (STAFF)
  ```
  Unknown `{kind}` → 400 (the enum is the contract, not a 404 — the path is well-formed, the value
  isn't).
- **Public read** — on `PublicStorefrontServlet`:
  ```
  GET /api/public/{orgSlug}/pages           → [{kind, updated_at}]      (the footer's link source)
  GET /api/public/{orgSlug}/pages/{kind}    → {kind, body_ar?, body_en?, updated_at}
  ```
  Both `Cache-Control: public, max-age=300` (the profile's cadence — pages change rarely; and the
  slower window is why the *list* exists: the footer renders links without fetching bodies).
  Unknown org → opaque 404; existing org + kind never written → 404 (the page genuinely doesn't
  exist); unknown kind value → 400; non-GET → 405; `rl:pub-read` bucket.
  Bodies ship **verbatim both locales** (the C1 convention — client resolves the §1 fallback;
  cache stays one entry per org).

## Scope

### In
Migration + codegen; service + validation; admin handler; the two public reads; DTOs.

### Out (deferred)
More kinds (contact/shipping/FAQ — one CHECK + catalog key each, when asked for); rich
text/markdown (plain text is the *feature* — inert by construction); per-page SEO; custom slugs or
arbitrary pages; version history.

## Authorization

VIEWER read / STAFF write (merchandising-adjacent content, epic §9 — deliberately not OWNER: the
person writing shop copy is staff). Public reads anonymous with the standard boundary.

## Acceptance criteria

1. `PUT …/pages/about` creates then replaces idempotently; missing default-locale body → 400;
   > 20,000 chars → 400; unknown kind → 400; DELETE removes (subsequent public read → 404).
2. Public list returns exactly the kinds that exist with `updated_at`; detail returns both bodies
   verbatim (newlines intact — assert a multi-line body round-trips byte-identical; no HTML
   stripping, no interpretation — storage is inert).
3. Whitelist: no `id`/`org_id` in public bodies (JSON scan); `max-age=300` on both reads; unknown
   org → opaque 404; never-written kind → 404 vs unknown kind → 400 (the two are distinct).
4. Cross-org isolation: org A's PUT never affects org B's read (the UNIQUE key is `(org_id, kind)`).

## Tests

`StorefrontPagesIT`: the matrix above end-to-end (admin upsert/delete → public list/detail),
newline round-trip, the 400/404 distinction, isolation. Existing suites untouched.
Verified live through Tomcat: write AR-only about on an AR-default org, curl list + detail.

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 33** — footer links + `/pages/{kind}` routes + the admin editor | everything |
| Future kinds (contact/shipping/FAQ) | one CHECK-constraint value + one catalog key each |
