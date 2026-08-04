#!/usr/bin/env python3
"""Open Food Facts -> catalog TSVs for ONE org (the demo-storefront seeder).

A sibling of abo_catalog.py, not a replacement: ABO gives 200 orgs of bulk for query plans, this
gives one org that looks like a real Egyptian mini-market. It exists because three things the app
ships are untestable against ABO data:

  * BARCODES. `abo_catalog.py` writes NULL for every one of its 145,615 products, so the scanner
    seam (`GET /products?barcode=`), scan-to-stock and the in-store sale have no seeded data at
    all. OFF is *keyed* on EAN-13/UPC, so every row here carries a real, checksum-valid barcode.
  * DOMAIN. Restock, stock ledger, in-store sale and hold windows are a grocery's vocabulary
    (the e2e fixture is literally coffee beans and sugar), not furniture's.
  * CATEGORY NAMES. OFF's category taxonomy carries real English display names ("Spring waters",
    not "spring-waters") — better than the `initcap(slug)` names the ABO backfill had to invent.

MEASURED COVERAGE of the Egypt slice (4,165 products, 100-product sample), because the pitch for
this dataset was partly wrong and the numbers should live next to the code:

    code               100%     <- the reason to do this
    product_name        86%
    product_name_en     64%
    image_front_url     78%
    categories_tags     69%
    brands              68%
    product_name_ar     14%     <- and some of those are junk (".")

And the categories taxonomy, measured over all 14,552 nodes: 62% carry an English name, **142
(1%) carry an Arabic one**.

So this dataset does NOT solve Arabic, for products or for categories — it solves BARCODES and
DOMAIN. Arabic is still synthesised here, but from a grocery word bank keyed on the OFF category
root (AR_CATEGORY below), which at least yields real Arabic nouns that match what the product is
rather than a transliteration or a mixed-script string.

Deterministic: all randomness comes from one seeded RNG, so the same input makes the same rows.
Stdlib only.

Usage:
    off_catalog.py <off.jsonl> <out-dir> --owner <app_user.id> [--taxonomy <categories.json>]
Env knobs: ORG_SLUG (default mart-cairo), ORG_NAME, SEED (default 4242 — see below), LIMIT.
"""
import argparse
import json
import os
import random
import re
import unicodedata
import uuid

SLUG_RE = re.compile(r"[^a-z0-9]+")

ap = argparse.ArgumentParser()
ap.add_argument("source")
ap.add_argument("out_dir")
ap.add_argument("--owner", required=True, help="app_user.id to grant OWNER on the new org")
ap.add_argument("--taxonomy", help="OFF categories taxonomy json (for real category names)")
args = ap.parse_args()

ORG_SLUG = os.environ.get("ORG_SLUG", "mart-cairo")
ORG_NAME = os.environ.get("ORG_NAME", "Mart Cairo")
LIMIT = int(os.environ.get("LIMIT", "0"))
# NOT 42. abo_catalog.py seeds its RNG with 42 and mints ~1M ids from it; seeding this script the
# same way replays the identical stream, so every id here would collide with an ABO row already in
# perfdb — starting with the org itself. Verified the hard way: at SEED=42 the first uid() out of
# this script was an org id that already exists.
rng = random.Random(int(os.environ.get("SEED", "4242")))

# The org id is derived from its slug instead of drawn from the stream, so re-running is idempotent
# in the one place that matters and the id is traceable back to the org it names.
ORG_NS = uuid.UUID("6f3d9a2c-0000-4000-8000-000000000001")


def uid():
    """Deterministic UUIDv4-shaped id from the seeded RNG (abo_catalog.py's helper, verbatim)."""
    return str(uuid.UUID(int=rng.getrandbits(128), version=4))


def slugify(s, fallback):
    s = SLUG_RE.sub("-", (s or "").lower()).strip("-")[:48].strip("-")
    return s or fallback


def clean(s):
    """OFF is crowdsourced: names arrive with stray whitespace, newlines and one-character junk."""
    if not s:
        return None
    s = unicodedata.normalize("NFC", s).replace("\t", " ").replace("\n", " ").strip()
    s = re.sub(r"\s+", " ", s)
    return s if len(s) >= 2 else None


def has_arabic(s):
    return any("؀" <= ch <= "ۿ" for ch in s or "")


AR_ADJ = ["طازج", "أصلي", "فاخر", "مستورد", "بلدي", "مميّز"]

# Arabic grocery nouns keyed on OFF category tags, general → specific. The taxonomy's own `ar`
# names cover 1% of nodes, so this bank is what actually puts Arabic letters into the seeded
# titles (and therefore through V62's fold_search / name_search). Matched by substring against
# every tag on the product, most specific first.
AR_CATEGORY = [
    ("waters", "مياه"), ("juices", "عصير"), ("sodas", "مشروب غازي"), ("teas", "شاي"),
    ("coffees", "قهوة"), ("milks", "حليب"), ("yogurts", "زبادي"), ("cheeses", "جبن"),
    ("butters", "زبدة"), ("eggs", "بيض"), ("breads", "خبز"), ("biscuits", "بسكويت"),
    ("chocolates", "شوكولاتة"), ("candies", "حلوى"), ("ice-creams", "آيس كريم"),
    ("chips", "شيبس"), ("nuts", "مكسرات"), ("rices", "أرز"), ("pastas", "مكرونة"),
    ("flours", "دقيق"), ("sugars", "سكر"), ("salts", "ملح"), ("spices", "بهارات"),
    ("olive-oils", "زيت زيتون"), ("vegetable-oils", "زيت"), ("honeys", "عسل"),
    ("jams", "مربى"), ("sauces", "صلصة"), ("canned-foods", "معلبات"), ("meats", "لحوم"),
    ("poultries", "دواجن"), ("fishes", "أسماك"), ("legumes", "بقوليات"),
    ("fruits", "فواكه"), ("vegetables", "خضروات"), ("cereals", "حبوب"),
    ("dairies", "ألبان"), ("beverages", "مشروبات"), ("snacks", "سناكس"),
]


def ar_noun(tags):
    """The Arabic noun for a product, from its OFF tags — most specific match wins."""
    for tag in reversed(tags):
        stem = tag.split(":", 1)[-1]
        for needle, word in AR_CATEGORY:
            if needle in stem:
                return word
    return "منتج"

files = {}


def w(table, *cols):
    files[table].write("\t".join("\\N" if c is None else str(c) for c in cols) + "\n")


# ── category names from the OFF taxonomy (en + ar), else a humanised slug ──────────────
tax = {}
if args.taxonomy and os.path.exists(args.taxonomy):
    with open(args.taxonomy, encoding="utf-8") as fh:
        raw = json.load(fh)
    for tag, node in raw.items():
        names = node.get("name") or {}
        tax[tag] = (clean(names.get("en")), clean(names.get("ar")))


def category_names(tag):
    """(en, ar) for an OFF category tag: the taxonomy's own names where it has them, then the
    Arabic word bank, then the English display name. Never a generic fallback word — that would
    name most of the org's categories identically (abo_catalog.ar_category_name has the reasoning)."""
    en, ar = tax.get(tag, (None, None))
    if not en:
        en = " ".join(p.capitalize() for p in tag.split(":", 1)[-1].replace("-", " ").split())
    if not ar:
        stem = tag.split(":", 1)[-1]
        ar = next((w for needle, w in AR_CATEGORY if needle in stem), None)
    return en, (ar or en)


def main():
    os.makedirs(args.out_dir, exist_ok=True)
    for name in [
        "org", "user_org_role", "category", "category_translation", "product",
        "product_listing", "product_listing_translation", "product_listing_category",
        "product_listing_image", "inventory",
        # Not a table: `{object_key}\t{source_url}` for off_images.sh to fetch. Kept beside the
        # TSVs so the key a row claims and the key the bytes land on are written once, together.
        "images_manifest",
    ]:
        files[name] = open(os.path.join(args.out_dir, name + ".tsv"), "w", encoding="utf-8")

    org_id = str(uuid.uuid5(ORG_NS, ORG_SLUG))
    # default_locale ar: this is an Egyptian shop, and it makes the storefront exercise the
    # requested->default->slug resolution rather than always finding an English row.
    w("org", org_id, ORG_NAME, ORG_SLUG, "true", "ar")
    w("user_org_role", args.owner, org_id, "OWNER")

    cat_ids = {}          # category slug -> category_id
    seen_codes = set()    # barcode is org-unique (V16 partial index) — OFF has duplicates
    seen_slugs = set()
    n_products = n_listings = n_images_expected = 0

    with open(args.source, encoding="utf-8") as fh:
        for line in fh:
            if LIMIT and n_products >= LIMIT:
                break
            item = json.loads(line)

            code = clean(item.get("code"))
            # Keep only well-formed GTINs: OFF holds internal/test codes too, and a barcode that
            # cannot be scanned is worse than none for the flow this dataset exists to exercise.
            if not code or not code.isdigit() or len(code) not in (8, 12, 13, 14):
                continue
            if code in seen_codes:
                continue

            title_en = clean(item.get("product_name_en")) or clean(item.get("product_name"))
            if not title_en:
                continue
            brand = (clean(item.get("brands")) or "").split(",")[0].strip()
            qty = clean(item.get("quantity"))

            tags = [t for t in (item.get("categories_tags") or []) if t.startswith("en:")]
            tag = tags[-1] if tags else "en:groceries"   # most specific wins
            # Keyed on the SLUG, not the tag: `category` is unique on (org_id, slug), and distinct
            # OFF tags do collapse to one slug (a 48-char truncation, or plain synonyms). Two tags
            # that name the same slug are one category as far as the store is concerned — keying on
            # the tag instead emits a duplicate row and the COPY dies.
            cat_slug = slugify(tag.split(":", 1)[-1], "misc")
            if cat_slug not in cat_ids:
                cid = uid()
                cat_ids[cat_slug] = cid
                en, ar = category_names(tag)
                w("category", cid, org_id, None, cat_slug)
                w("category_translation", uid(), cid, "en", en)
                w("category_translation", uid(), cid, "ar", ar)

            slug = slugify(f"{title_en}-{code[-6:]}", f"item-{code}")
            if slug in seen_slugs:
                continue
            seen_slugs.add(slug)
            seen_codes.add(code)

            # OFF carries no prices — synthesised, deterministically, like the ABO seeder.
            base = round(rng.uniform(8, 480), 2)
            sales = round(base * rng.uniform(1.08, 1.55), 2)

            product_id = uid()
            desc = " • ".join(x for x in [brand or None, qty] if x) or None
            # product: id, name, description, base_price, sku, org_id, barcode
            w("product", product_id, title_en[:500], desc, base, f"OFF-{code}", org_id, code)

            listing_id = uid()
            r = rng.random()
            status = "PUBLISHED" if r < 0.92 else ("DRAFT" if r < 0.98 else "ARCHIVED")
            published_at = (
                f"2026-{rng.randint(1, 7):02d}-{rng.randint(1, 28):02d} "
                f"{rng.randint(0, 23):02d}:{rng.randint(0, 59):02d}:00+00"
            ) if status == "PUBLISHED" else None
            featured = None
            if status == "PUBLISHED":
                n_listings += 1
                if n_listings <= 8:
                    featured = n_listings
            w("product_listing", listing_id, org_id, product_id, slug, sales, status,
              published_at, featured)
            w("product_listing_category", listing_id, cat_ids[cat_slug])

            # en from OFF; ar real when the product carries one, else built from the category's
            # own Arabic name + the brand — synthetic, but real Arabic letters through
            # fold_search/name_search (V62), and never a bare transliteration.
            title_ar = clean(item.get("product_name_ar"))
            if not title_ar or not has_arabic(title_ar):
                title_ar = " ".join(x for x in [ar_noun(tags or [tag]), rng.choice(AR_ADJ), brand] if x)
            w("product_listing_translation", uid(), listing_id, "en", title_en[:255], desc)
            w("product_listing_translation", uid(), listing_id, "ar", title_ar[:255], None)

            stock = 0 if rng.random() < 0.08 else rng.randint(1, 120)
            w("inventory", product_id, stock, 0, org_id)

            # One image per product — OFF's front photo. The key follows ObjectStorage.newImageKey's
            # scheme exactly (PLURAL "listings"; see stories/perfdb_seed_fidelity.md §1), so the
            # cross-tenant attach guard accepts it if these rows are ever touched through the API.
            img_url = clean(item.get("image_front_url"))
            if img_url:
                object_key = f"{org_id}/listings/{listing_id}/{code}.jpg"
                w("product_listing_image", uid(), org_id, listing_id, object_key,
                  title_en[:200], 0)
                w("images_manifest", object_key, img_url)
                n_images_expected += 1
            n_products += 1

    for fh in files.values():
        fh.close()
    print(f"org={ORG_SLUG} ({org_id})  products={n_products}  published={n_listings}  "
          f"categories={len(cat_ids)}  with-a-photo={n_images_expected}")
    print("owner:", args.owner)


main()
