# Slice: Collection image (`collection.image_object_key` + `image_url` on the public rail)

> Storefront growth — the second "what the API can back" slice from the home-page comparison of
> 2026-08-25: the mock's collection tiles carry an icon; ours are text chips, because
> `PublicCollectionResponse` is `{slug, name}`. Same move as `category_image.md`, on the
> `collection` aggregate (`storefront_collections.md`, roadmap item 8). Stacked on the category
> slice (shares `ImagePresign` / `ImagePresignResponse`); one frontend story consumes both.

---

## Goal

STAFF attaches an image to a collection (presign → upload → attach the key on create/update); the
admin reads carry the key + a presigned preview; the public collections rail carries a presigned
`image_url` per row so the storefront's collection tiles can show it — text-only as today when
a collection has none.

## Decision: an image, not an icon vocabulary

An `icon` enum (a fixed set of glyph names the storefront maps to SVGs) was considered: cheaper to
pick, but every merchant would choose from the same twenty icons and the storefront would carry a
glyph catalogue forever. An uploaded image is the same column and prefix the banner and category
already use, and a merchant who wants an icon uploads one. No fallback is invented server-side.

## Migration — **V86** (V85 is highwater on this stack)

```sql
ALTER TABLE collection ADD COLUMN image_object_key TEXT;
```

## Key discipline

`ObjectStorage.newCollectionKey(orgId, filename)` → `{orgId}/collection/{uuid}-{name}`;
`collectionKeyPrefix(orgId)`. Write-time guard in `CollectionService`: a non-blank key must start
with this org's collection prefix, else 400 `image_object_key does not belong to this org`. Stored
as a key, presigned on read (900 s TTL by config).

## Admin API (`CollectionHandler`, `/api/orgs/{orgId}/collections`)

- `POST /presign` (STAFF) — `{filename, content_type?}` → `{upload_url, object_key,
  expires_in_seconds}`.
- `POST /` and `PUT /{id}` accept `image_object_key`: create absent/blank = none; **update merges
  the banner way** (absent = unchanged, blank = clear, value = replace) — the one merged field on
  an otherwise full-replace PUT, so today's admin collection form cannot wipe an image by saving a
  name.
- `CollectionResponse` gains `image_object_key` + `image_url` (list + detail + write echo).

## Public surface

`GET /api/public/{orgSlug}/collections` — each row gains `image_url` (presigned, null when none).
Still no id, sort order or membership size. The "at least one PUBLISHED listing" rule is unchanged;
`max-age=300` stays inside the presign TTL.

## Explicitly NOT in this slice

- No image on the collection landing page's header (`/col/{slug}` reads the rail row; the frontend
  can reuse it) — no new read.
- No admin/storefront UI — the paired frontend story.

## Tests (`CollectionsIT`, a new image group; model: `CategoryImageIT`)

1. Presign mints an `{orgId}/collection/` key with an `http` upload URL; blank filename is a 400.
2. Guard: another org's key, our own category-prefixed key, and a bare filename are each a 400.
3. Roundtrip + merge: create with a key → list and detail carry the key and an `http` preview;
   update without the field keeps it; a new key replaces it; blank clears it (preview → null).
4. Public rail: a collection with an image (and a published member) serves an `image_url`
   containing the key's basename; one without serves `image_url: null`.

`CollectionsIT` and `CollectionHandlerAuthTest` gain the storage argument / the extra overload
argument and must stay green.

## Definition of done

V86 + codegen · domain/repository/service/DTO/handler/public-rail changes · `AppConfig` wiring ·
`CollectionsIT` (with the image group) + `CollectionHandlerAuthTest` green · `mvn spotless:apply`
· branch `162_feat/collection-image`, stacked on `161_feat/category-image` (merge in order).
