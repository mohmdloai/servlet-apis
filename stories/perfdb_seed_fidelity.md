# Slice: perfdb seed fidelity — image keys, category names, image bytes

Three gaps that only surface when perfdb is driven as a **store** (admin UI, storefront,
screenshots) rather than read as a query-plan fixture. Each is fixed in the generator *and*
applied to the live perfdb, since the seed cannot be re-run (see §"The live perfdb" below).

---

## 1. The image keys must match the real key scheme

> `tools/seed/abo_catalog.py` mints `product_listing_image.object_key` as
> `{org}/listing/{listing}/{aboImageId}.jpg`. Production mints
> `{org}/listings/{listing}/{uuid}-{filename}` (`ObjectStorage.newImageKey`). **Singular vs plural** —
> and the plural is not cosmetic: `ObjectStorage.keyPrefix(orgId, listingId)` is
> `"{org}/listings/{listing}/"`, and `ProductListingService.attachImage` refuses any key that does not
> start with it (the cross-tenant attach guard). Every one of perfdb's 657,998 seeded keys was
> unusable by that guard.

---

## Why it went unnoticed, and why it matters now

Reads never look at the shape: `presignGet(image.getObjectKey())` signs whatever string the row
holds, so a benchmark that only *reads* the catalog cannot tell the two apart. The seed also never
uploaded bytes on purpose ("image bytes never touch a query plan"), so nothing downstream of the key
existed to disagree with it.

It matters the moment perfdb is driven as a **semi-real storefront** rather than a query-plan
fixture — seeding real image bytes for one org so screenshots and manual QA run against a store that
looks like a store. That path uses the admin UI, which is the path with the guard. Fixing the
generator *before* any bytes land is the cheap moment: re-keying afterwards means moving every
object in MinIO too.

## The change

One f-string in `abo_catalog.py` — `/listing/` → `/listings/`.

The `{uuid}-` prefix production puts on the leaf is **deliberately not copied**. It exists to stop
two merchant uploads of `photo.jpg` colliding; ABO image ids are already unique, and adding it would
make the generator's output un-reproducible from the live perfdb (which is corrected in place, see
below) without buying anything the guard checks.

## The live perfdb, corrected in place

The seed could not simply be re-run: the ABO source (`listings_*.json.gz`) is no longer on disk, and
`run.sh` **drops perfdb at line 21** and only then reaches `abo_catalog.py`'s "no listings_*.json.gz"
exit — so a run with a wrong `ABO_DIR` destroys the benchmark database and leaves an empty schema
clone (`ABO_DIR` unset is safe: `${ABO_DIR:?}` fires first). A rebuild would also regenerate 1M
orders + 5M log rows to change one column.

So the column was rewritten in place — the same end state a re-seed would produce for it, and safe
precisely because no bytes exist in MinIO yet to move:

```sql
UPDATE inventorydb.product_listing_image
   SET object_key = regexp_replace(object_key, '^([0-9a-f-]{36})/listing/', '\1/listings/')
 WHERE object_key ~ '^[0-9a-f-]{36}/listing/';
-- UPDATE 657998, ~40s
```

Anchored on the org-UUID prefix so it can only ever rewrite the path segment, never a filename.
Verified after: **0** rows still in the old form, and **0** rows failing
`object_key LIKE org_id || '/listings/' || listing_id || '/%'` — i.e. every key now satisfies
`keyPrefix()` for its own org and listing, which is the property the guard actually tests.

Reversible with the same statement in the other direction.

## Verified separately: the byte path works

Before the rewrite, one 52 KB JPEG was uploaded to `s3://catalog-images/` at a canonical key and
fetched back through a presigned GET: **HTTP 200, 53,218 bytes, `image/jpeg`, byte-identical**, host
`localhost:9100` matching `storage.properties` (a presigned signature is bound to its host — a
capture run from another device needs `MINIO_PUBLIC_ENDPOINT`). The probe object was deleted; the
bucket is empty again.

---

## 2. Categories had no names at all

**Symptom.** The admin listing editor's CATEGORIES panel renders a column of **unlabelled
checkboxes**.

**Cause.** `category` has no `name` column — the name lives in `category_translation` (V63's
paired-language pattern), and neither `abo_catalog.py` nor `run.sh` ever wrote that table. perfdb
held **24,305 categories and 0 translations**, so the UI was correctly rendering a name that was
not there.

**The change.** The generator now emits two rows per category from ABO's `product_type` — the same
value the slug is already made from:

- `en` — `humanize()`: `SHOE_RACK` → `Shoe Rack`.
- `ar` — the existing `AR_TYPES` word bank when it knows the type, else the English display name.
  Deliberately **not** a blanket `منتج` fallback: that would name most of an org's categories
  identically, which reads worse than an untranslated one — and an untranslated category is a real
  merchant state the resolver already handles (requested → default → slug).

`run.sh` gains the matching `copy category_translation "id, category_id, language, name"`.

**Applied to the live perfdb** with the same rule, deriving the name from the slug (the only thing
the existing rows carry): `INSERT 0 24305` per language, guarded by `NOT EXISTS` so it is
re-runnable. After: **0** categories without a name.

Verified through the running backend, both planes:
`GET /api/orgs/{org}/categories` → `"name":"Light Fixture"` with `translations` for `ar` + `en`;
`GET /api/public/store-102/categories?locale=ar` → `حذاء` for `shoes`, English display names
elsewhere — i.e. the fallback rule behaving exactly as designed.

---

## 3. Image bytes, for one org

**Not wired into `run.sh`** — bytes still never touch a query plan, so the benchmark path must not
pay for them. `tools/seed/image_tiles.py` is opt-in, for when perfdb is being used as a demo store.

One placeholder per image row, carrying **the product's own name**. That is the honesty constraint,
learned from the landing-asset pipeline: a tile that shows the title cannot contradict the title
beside it, and it is visibly a placeholder rather than a fake photograph.

SVG, not JPEG: no image library is installed here, the bytes are ~700 B instead of ~30 kB, and a
browser renders by **Content-Type, not by the key's extension** — so the keys keep their `.jpg`
names (the DB rows already hold them; re-keying twice is churn) and the sync overrides
`--content-type image/svg+xml`. Tint is derived from the listing id with a lightness step per
`sort_order`, so one product's gallery reads as one product while its thumbs stay distinguishable.

Applied to `store-102`: **3,272 objects, 13 MB, 9.6 s** to sync. Verified end-to-end — the public
listings read returns a presigned URL that fetches **HTTP 200, `image/svg+xml`, 799 B**, and a
headless browser renders the grid correctly. One rendering defect was caught and fixed in that
check: ABO titles contain unbreakable tokens like `PlayStation-4-DualShock-4-Controller`, which a
space-only greedy wrapper pushed straight out of the tile; the wrapper now hard-breaks
longer-than-line tokens.

Real photography remains out of scope — it needs a dataset whose licence permits the use, and
perfdb's titles are ABO home goods, so dropping fashion imagery on them would reproduce the
"picture isn't the product" defect at 657,998-row scale.
