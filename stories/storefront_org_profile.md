# Slice: Public storefront profile (per-org branding + payment instructions)

> The storefront is multi-tenant and **branded per org** (the Souq.com reference is one skin over many
> merchants). Today an `org` has `name` + `slug` and nothing a public page can render as identity —
> no logo, no theme colour, no "how do I pay you" text. This slice adds the small set of org columns a
> branded storefront and its checkout confirmation need, and exposes them on one anonymous, cacheable
> endpoint.
>
> Canonical contract & decisions: [`frontst/docs/storefront-commerce-epic.md`](../../frontst/docs/storefront-commerce-epic.md).
> Sibling of [`public_storefront_read_api.md`](public_storefront_read_api.md) (same anonymous read-model
> pattern). Feeds frontend story 24 (shell/branding) and story 29 + [`public_checkout.md`](public_checkout.md)
> (the confirmation's payment instructions).

---

## Goal

`GET /api/public/{orgSlug}` — an anonymous, CDN-cacheable endpoint returning the org's public
storefront identity: display name, logo, theme colour, locale defaults, currency, and the **payment
instructions** a shopper follows after placing a `PENDING_PAYMENT` order (the InstaPay handle + note).

Done means: the storefront header renders the org's name + logo + accent colour, the checkout
confirmation shows "Send via InstaPay to `<handle>`, put your order number in the note", and none of
this leaks any internal org field (id, owner, thresholds, `order_ttl_minutes`, member counts).

---

## Why on the org, and why one endpoint

- **Branding is org identity, not a user preference.** The admin app's accent/surface theming
  (`tokens.ts` `ACCENTS`/`SURFACES`) is a *per-user* setting; a storefront's look is a *per-org*
  property every anonymous visitor sees. So these are new **org columns**, not reused user prefs.
- **Payment instructions must be self-serve.** In v1 a shopper pays by manual InstaPay transfer
  (`accept_online_payment.md`); the confirmation has to tell them the org's handle + the "put the order
  number in the note" instruction. That text is org-specific and belongs on the org.
- **One profile call, not four.** The shell needs name/logo/theme/locales in a single request on first
  paint; splitting them across endpoints would fan out. Mirror `StorefrontService.resolveOrg` (by-slug,
  active-only) and whitelist the output like the read API.

---

## Design

- **New org columns** (migration): `logo_object_key VARCHAR` (nullable; object-storage key, presigned
  on read like listing images), `theme_color VARCHAR(7)` (nullable hex, e.g. `#1E5AA8`),
  `instapay_handle VARCHAR` (nullable), `payment_instructions TEXT` (nullable; free text shown at
  confirmation), `default_locale VARCHAR(5) NOT NULL DEFAULT 'ar'` (`CHECK IN ('ar','en')`). No new
  table — these hang off `org`.
- **`StorefrontService.profile(orgSlug)`**: `orgRepo.findBySlug(orgSlug).filter(Org::isActive)
  .orElseThrow(NotFoundException)`; presign `logo_object_key` (null → null logo); return a whitelisted
  `StorefrontProfileView`.
- **`PublicStorefrontServlet`** gains the bare `/api/public/{orgSlug}` route (currently it routes
  `/{orgSlug}/listings` and `/{orgSlug}/categories`; add the no-suffix case). GET-only.
  `Cache-Control: public, max-age=300` (branding changes rarely — longer than the 60s catalog TTL, but
  below the presigned logo-URL TTL of 900s so a cached profile never carries a dead logo URL).
- **Admin write path** (so an owner can set branding): extend the existing owner/admin org-update
  endpoints — `PUT /api/orgs/{orgId}` and `PATCH /api/admin/orgs/{orgId}` — to accept the new fields;
  logo upload reuses the presigned-object-storage pattern already used for listing images
  (`POST /api/orgs/{orgId}/logo/presign` → PUT to storage → `PATCH` sets `logo_object_key`). These are
  **STAFF/OWNER**-gated, org-scoped writes — not on the public surface.

---

## API contract

### Public read
```
GET /api/public/{orgSlug}                → 200 StorefrontProfileResponse   (Cache-Control: public, max-age=300)
```
```json
{
  "name": "Acme Store",
  "slug": "acme",
  "logo_url": "<presigned GET URL or null>",
  "theme_color": "#1E5AA8",
  "default_locale": "ar",
  "supported_locales": ["ar", "en"],
  "currency": "EGP",
  "instapay_handle": "acme@instapay",
  "payment_instructions": "Send via InstaPay to acme@instapay and put your order number in the note."
}
```
- `supported_locales` is constant `["ar","en"]` in v1 (both ship live per the epic); `default_locale`
  is the org's chosen landing locale.
- `currency` is constant `"EGP"`.
- 404 on unknown or inactive slug (opaque — same as the read API). 405 on non-GET.

### Admin write (org-scoped, gated — not public)
`PUT /api/orgs/{orgId}` (OWNER) and `PATCH /api/admin/orgs/{orgId}` (ADMIN) accept optional
`theme_color`, `instapay_handle`, `payment_instructions`, `default_locale`; null leaves each unchanged;
`OrgResponse` echoes them. `theme_color` validated as `^#[0-9A-Fa-f]{6}$` (400 otherwise);
`default_locale` ∈ `{ar,en}` (400 otherwise). Logo via presign:
`POST /api/orgs/{orgId}/logo/presign` (STAFF) → `{url, object_key}`; `PATCH …` with `logo_object_key`
(org key-prefix enforced, mirroring listing images).

---

## Scope

### In
- Migration `V{n}__Add_org_storefront_branding.sql`: the five columns above; jOOQ regen.
- `Org` domain fields + `OrgRepositoryImpl` read/update; `OrgResponse` gains the fields.
- `StorefrontService.profile(orgSlug)` + `StorefrontProfileView` + `StorefrontProfileResponse` DTO.
- `PublicStorefrontServlet` bare-`{orgSlug}` route + `max-age=300`.
- Admin write plumbing on `PUT /api/orgs/{orgId}` and `PATCH /api/admin/orgs/{orgId}`; the logo
  presign endpoint reusing the image-presign machinery.
- Validation: `theme_color` hex, `default_locale` enum.

### Out (deferred)
- **Banner / hero images, social links, custom domains, favicon** — v1 ships name/logo/theme/pay-text;
  richer merchant theming is a later slice.
- **Per-locale org name / instructions** — one name, one instruction string in v1.
- **A branding editor UI in admin** — a later admin story; this slice ships the columns + API.
- **CDN/stable public logo bucket** — v1 presigns (like listing images).

---

## Authorization

- Public read: none (anonymous; by-slug active-only; whitelisted).
- Writes: `PUT /api/orgs/{orgId}` OWNER, `PATCH /api/admin/orgs/{orgId}` ADMIN, logo presign STAFF —
  all existing gates; no new authorization model.

---

## Acceptance criteria

- [ ] `GET /api/public/{orgSlug}` on an active org → `200` with name/slug/theme/locales/currency +
      `logo_url` (presigned, or null when unset) + `payment_instructions`/`instapay_handle`;
      `Cache-Control: public, max-age=300`.
- [ ] The response contains **no** internal org field: no `id`, `owner`, `refund_approval_threshold`,
      `order_ttl_minutes`, `active`, member counts, or timestamps.
- [ ] Unknown or **inactive** slug → `404` (opaque). Non-GET → `405`.
- [ ] `PUT /api/orgs/{orgId}` (OWNER) sets `theme_color`/`instapay_handle`/`payment_instructions`/
      `default_locale`; null leaves each unchanged; a just-set value is immediately visible on the
      public profile. Invalid `theme_color` / `default_locale` → `400`.
- [ ] Logo presign (STAFF) returns a scoped PUT URL; `PATCH` with the returned `object_key` sets
      `logo_object_key`; the public profile then presigns a GET URL for it.
- [ ] A pre-migration org (no branding set) serves a valid profile: null `logo_url`, null theme,
      default locale `ar`, null payment fields — the storefront degrades gracefully.

---

## Tests

`api/.../catalog/StorefrontProfileIT.java` (TestContainers; presign offline):
- active org → 200 whitelisted profile, `max-age=300`, presigned logo when set / null when unset;
- no-leak scan (no `order_ttl_minutes`/`refund_approval_threshold`/`id`/`active` in body);
- unknown + inactive slug → 404; non-GET → 405;
- owner sets branding via `PUT /api/orgs/{orgId}` → reflected on the public profile in the next read;
- invalid `theme_color` (`bad`, `#12`, `#GGGGGG`) → 400; invalid `default_locale` → 400;
- pre-migration/default org → valid profile with nulls/`ar`.

---

## What this unblocks

| Next | Depends on this |
|---|---|
| **Frontend story 24 (shell + branding)** | name/logo/theme/locales for the header + theme provider |
| **`public_checkout.md` response** | `payment_instructions` in the confirmation |
| **Frontend story 29 (confirmation)** | InstaPay handle + instruction text |
