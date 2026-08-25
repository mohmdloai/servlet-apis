# Slice: Category image (`category.image_object_key` + `image_url` on the public nav)

> Storefront growth — the first of two "what the API can back" slices behind the home-page
> comparison of 2026-08-25: the mock's category circles carry product photos; ours carry a letter,
> because `NavCategory` is `{name, slug, parent_slug}` and nothing else. This slice gives a category
> an image the merchant uploads, exactly the way a banner has one (`storefront_banners.md`, C1).
> Pairs with `collection_image.md` (next migration) and one frontend story consuming both.

---

## Goal

STAFF attaches an image to a category (upload via presign, attach the key on create/update); the
admin reads carry the key + a presigned preview; the public category nav carries a presigned
`image_url` per node so the storefront's category circles can show it — and stays exactly as today
when a category has none.

## Decision: an uploaded image, not a derived thumbnail

The cheap alternative — the nav read picking "the first published listing's thumbnail" per
category — was rejected: it is an extra query per nav read on a `max-age=60` route, it changes
under the merchant's feet whenever stock moves, and it puts a product photo where a category
identity belongs. An uploaded image is one nullable column and a prefix, and it reuses the banner /
logo / listing-image presign machinery without a new moving part. No fallback is invented
server-side: absent → `image_url: null` → the storefront keeps its neutral circle.

Sizing/cropping guidance is the frontend's (square, ≥ 256 px); the server stores what it is given,
as it does for banners.

## Migration — **V85** (V84 is highwater)

```sql
ALTER TABLE category ADD COLUMN image_object_key TEXT;
```

Nullable, no default, no index (never queried by). Same type as `storefront_banner.image_object_key`.

## Key discipline (the banner precedent)

- `ObjectStorage.newCategoryKey(orgId, filename)` → `{orgId}/category/{uuid}-{sanitized-name}`;
  `categoryKeyPrefix(orgId)` = `{orgId}/category/`.
- Write-time guard in `CategoryService`: a non-blank key must start with **this org's** category
  prefix, else 400 `image_object_key does not belong to this org`. Another org's key, our own
  banner/logo key, and a bare filename are all refused.
- The stored value is a key, never a URL. Reads presign a GET (900 s TTL by config), the way the
  banner list, the logo and listing images already do.

## Admin API (`CategoryHandler`, `/api/orgs/{orgId}/categories`)

- `POST /presign` (STAFF) — body `{filename, content_type?}` → `{upload_url, object_key,
  expires_in_seconds}` (`ImagePresignResponse`; the shared `ImagePresign` record). No row written.
- `POST /` and `PUT /{id}` accept `image_object_key`:
  - create: absent/blank = no image;
  - **update merges the banner way**: absent (null) = unchanged, blank = clear, value = replace.
    A PUT is otherwise a full replace here (slug, parent, translations), but the image field is
    merged so a client that predates images — today's admin category form — cannot wipe one by
    saving a name.
- `CategoryResponse` gains `image_object_key` (raw, admin-only) + `image_url` (presigned preview,
  null when none), on the list and the detail.

## Public surface

`GET /api/public/{orgSlug}/categories` — each node gains `image_url` (presigned GET, null when
none). Still slugs only; no key, no id. Cache header unchanged (`max-age=60`, well inside the
presign TTL — the banner read runs the same arithmetic at `max-age=300`).

## Explicitly NOT in this slice

- No derived fallback image, no server-side resizing, no image on `PublicCategoryRefResponse`
  (a listing's category chips are text).
- No admin UI — the frontend story pairs the uploader (`BannerImageField`'s pattern) with the
  category form and the storefront `CategoryRail`.
- Collections: `collection_image.md`.

## Tests (`CategoryImageIT`; models: `StorefrontBannerAdminIT` for the guard, `CollectionsIT` for the public wiring)

1. **Presign** mints an `{orgId}/category/` key with an `http` upload URL and a positive TTL; a
   blank filename is a 400.
2. **Guard**: another org's category key, our own banner-prefixed key, and a bare filename are
   each a 400 on create.
3. **Roundtrip + merge**: create with a key → detail and list carry the key and an `http`
   preview; update without the field keeps it; update with a new key replaces it; update with a
   blank clears it and the preview goes null.
4. **Public nav**: a category with an image serves an `image_url` containing its key's basename;
   a category without serves `image_url: null`.

Existing suites (`CategoryCrudIT`, `CategoryTranslationIT`, `ProductListingIT`, `AttributeFacetsIT`)
gain the storage argument on the service constructor and must stay green.

## Definition of done

V85 + codegen · domain/repository/service/DTO/handler/public-nav changes · `AppConfig` wiring ·
`CategoryImageIT` green with the four existing category suites · `mvn spotless:apply` ·
branch `161_feat/category-image`.
