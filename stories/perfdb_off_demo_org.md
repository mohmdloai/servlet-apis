# Slice: a demo org seeded from Open Food Facts (`mart-cairo`)

> A **sibling** of the ABO seeder, not a replacement. ABO gives 200 orgs of bulk so query plans are
> measured at scale; this gives **one** org that behaves like a real Egyptian mini-market, so the
> app can be driven, demoed and screenshotted against something that looks like a shop.
>
> Additive by construction: `off_load.sh` adds a tenant beside the 200 and deletes only its own
> rows on a re-run — the opposite of `run.sh`, which drops the database. Safe on a populated perfdb.

---

## Why this dataset

**Barcodes, and that is the headline.** `abo_catalog.py` writes `NULL` for every one of its 145,615
products. The app ships a scanner seam (`GET /products?barcode=` — "the scanner's exact-lookup
seam, FLOW.md §3 step 1"), scan-to-stock, in-store sale, and V16's partial-unique index on the
column, and **none of it had a single seeded row**. The only barcodes in the project were two
constants in the admin e2e mock. OFF is *keyed* on EAN-13/UPC, so all 549 seeded products carry a
real one, and `?barcode=` returns a product for the first time.

**Domain.** Restock, stock ledger, in-store sale, hold windows — that is a grocer's vocabulary, and
the e2e fixture is literally coffee beans and sugar. Groceries fit; furniture and fashion do not.

**Category display names.** OFF's taxonomy carries real English names ("Spring waters", not the
`initcap('spring-waters')` the ABO backfill had to invent).

## What it does NOT give: Arabic

The pitch for this dataset included Arabic, and that was **wrong**. Measured, not assumed:

| field | coverage |
|---|---|
| `code` (barcode) | **100%** |
| `product_name` | 86% |
| `product_name_en` | 64% |
| `image_front_url` | 78% |
| `categories_tags` | 69% |
| `brands` | 68% |
| `product_name_ar` | **14%** — and some are junk (`"."`) |
| categories taxonomy `ar` (all 14,552 nodes) | **142 = 1%** |

So Arabic is still synthesised. It is synthesised *better* than before — a grocery word bank keyed
on the OFF category tag (`AR_CATEGORY`, most-specific match wins) yields "مياه فاخر Nestlé" rather
than a transliteration or a mixed-script string — but a seeded Arabic title is still a template,
and the numbers above live in the script's docstring so nobody re-reads this as "OFF solved
Arabic". It solved **barcodes** and **domain**.

## What shipped

| file | role |
|---|---|
| `off_fetch.py` | caches a country slice to JSONL. OFF's search API is ~10 req/min |
| `off_catalog.py` | JSONL → the same TSV contract `run.sh` already COPYs |
| `off_load.sh` | additive, idempotent load of one org into perfdb |
| `off_images.sh` | fetches the real product photos and syncs them into MinIO |

Seeded result, verified through the running backend:

```
products 549 · with_barcode 549 · published 510 · categories 135 · photos 425
```

- `GET /api/public/mart-cairo` → *Mart Cairo*, `default_locale: ar`
- `GET /api/public/mart-cairo/listings?locale=ar` → 510 published, Arabic titles, presigned photos
- `GET /api/orgs/{org}/products?barcode=6223007300667` → **a product** (the seam, finally fed)
- `GET /api/public/mart-cairo/categories?locale=ar` → `مشروب غازي`, `عصير`, English where the bank
  has no word — the designed requested→default→slug fallback
- Rendered in a browser: Heinz, Danisa, Corona, Juhayna — a real Egyptian shelf

## Three bugs this found, all fixed here

1. **Id collision with ABO.** `abo_catalog.py` seeds its RNG with `42` and mints ~1M ids from it.
   Seeding this script the same way replays the *identical stream*, so every id would have
   collided with an existing row — the first `uid()` out of the script was an org id already in
   perfdb. Default seed is now `4242`, and the org id is a `uuid5` of its slug (traceable, and
   idempotent in the one place that matters).
2. **Category slug collisions.** Distinct OFF tags collapse to one slug (48-char truncation, or
   plain synonyms), and `category` is unique on `(org_id, slug)`. The map is now keyed on the
   **slug**, not the tag — two tags naming one slug are one category as far as the store is
   concerned.
3. **`DELETE FROM org` does not cascade.** The FKs to `org` are plain references, so the idempotent
   teardown had to delete children explicitly in dependency order. Scoped to the one org id
   throughout — it can never reach an ABO tenant.

## Honest limitations

- **Photo quality is crowdsourced.** Of the eight cards rendered, one is a close-up of a barcode
  and one is a shelf shot with a hand in frame. Fine for a demo tenant; not marketing photography.
- **Prices are invented** (OFF carries none) — a 241 EGP mustard is a seeded number, not a claim.
- **Only 729 of the country slice's 4,165 products were cached.** OFF's search endpoint began
  refusing (`curl` exit 56) partway through; 549 usable products is already a good demo catalog
  (`store-102` has 728 listings) and hammering a volunteer-run service for the rest is poor
  citizenship. `off_fetch.py` resumes from the cache if more are ever wanted.
- **Licence.** OFF data is ODbL, its photos CC-BY-SA. Fine for a local dev seed; **not** a licence
  to publish those photos in marketing material without attribution, and the share-alike question
  for a screenshot containing one is genuinely unsettled. Verify at the source before publishing.
