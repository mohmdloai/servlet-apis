# Slice: the perfdb seed's image keys must match the real key scheme

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

## Out

Seeding actual image bytes, and the catalog-vs-image mismatch that comes with it (perfdb's titles
are ABO home goods — dropping fashion photography on them reproduces, at 657,998 rows, exactly the
kind of "the picture isn't the product" defect this repo just fixed in the landing-asset pipeline).
That is its own slice, and it needs the dataset's licence settled first.
