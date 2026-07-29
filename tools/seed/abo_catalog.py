#!/usr/bin/env python3
"""ABO -> catalog TSVs (phase 2 of the performance case study, docs/query-index-audit.md §5).

Parses the Amazon Berkeley Objects listings metadata (listings_*.json.gz) and emits
COPY-ready TSVs for the catalog side of the schema: org, app_user, user_org_role,
category, product, product_listing, product_listing_translation (en from ABO + synthetic
ar so the V62/V63 paired-language machinery is exercised), product_listing_category,
product_listing_image (real ABO image ids minted into our object-key scheme; the bytes
themselves are NOT uploaded — image bytes never touch a query plan), inventory, customer.

Deterministic: all randomness comes from one seeded RNG, so the same inputs produce the
same database. Stdlib only — no pip installs.

Env knobs: ABO_DIR (default ./listings/metadata), OUT_DIR (default ./out),
ORGS (default 200), CUSTOMERS_PER_ORG (default 1000), SEED (default 42).
"""

import glob
import gzip
import json
import os
import random
import re
import uuid

ABO_DIR = os.environ.get("ABO_DIR", "./listings/metadata")
OUT_DIR = os.environ.get("OUT_DIR", "./out")
ORGS = int(os.environ.get("ORGS", "200"))
CUSTOMERS_PER_ORG = int(os.environ.get("CUSTOMERS_PER_ORG", "1000"))
SEED = int(os.environ.get("SEED", "42"))

rng = random.Random(SEED)


def uid() -> str:
    """Deterministic UUIDv4-shaped id from the seeded RNG."""
    return str(uuid.UUID(int=rng.getrandbits(128), version=4))


def tsv(v):
    """Escape one value for text-format COPY (tab-separated, \\N = NULL)."""
    if v is None:
        return r"\N"
    s = str(v)
    return (
        s.replace("\\", "\\\\").replace("\t", " ").replace("\n", " ").replace("\r", " ")
    )


def pick_lang(entries, prefer="en"):
    """ABO fields are [{language_tag, value}, ...]; prefer an en_* tag, else first."""
    if not entries:
        return None
    for e in entries:
        if e.get("language_tag", "").startswith(prefer):
            return e.get("value")
    return entries[0].get("value")


SLUG_RE = re.compile(r"[^a-z0-9]+")


def slugify(s, fallback):
    s = SLUG_RE.sub("-", (s or "").lower()).strip("-")[:48].strip("-")
    return s or fallback


# A small Arabic word bank keyed by ABO product_type so the synthetic ar titles carry
# real Arabic letters through fold_search/name_search (V62) instead of lorem ipsum.
AR_TYPES = {
    "SHOES": "حذاء", "SHIRT": "قميص", "DRESS": "فستان", "PANTS": "بنطال",
    "WATCH": "ساعة", "CHAIR": "كرسي", "TABLE": "طاولة", "SOFA": "أريكة",
    "LAMP": "مصباح", "RUG": "سجادة", "BAG": "حقيبة", "HAT": "قبعة",
    "JACKET": "سترة", "RING": "خاتم", "NECKLACE": "قلادة", "BED": "سرير",
}
AR_ADJ = ["أنيق", "عصري", "فاخر", "مريح", "كلاسيكي", "رياضي", "يدوي", "أصلي"]

AR_FIRST = ["أحمد", "محمد", "فاطمة", "سارة", "خالد", "منى", "يوسف", "هدى", "عمر", "ليلى"]
EN_FIRST = ["Adam", "Nour", "Omar", "Sara", "Karim", "Dina", "Hassan", "Mariam", "Tarek", "Laila"]
LAST = ["حسن", "علي", "Ibrahim", "Mostafa", "السيد", "Fahmy", "عبدالله", "Saad", "يوسف", "Ramzy"]


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    files = {
        name: open(os.path.join(OUT_DIR, name + ".tsv"), "w", encoding="utf-8")
        for name in [
            "org", "app_user", "user_org_role", "category", "product",
            "product_listing", "product_listing_translation", "product_listing_category",
            "product_listing_image", "inventory", "customer",
        ]
    }

    def w(name, *cols):
        files[name].write("\t".join(tsv(c) for c in cols) + "\n")

    # ── orgs + staff ─────────────────────────────────────────────────────────────
    org_ids = []
    for i in range(ORGS):
        org_id = uid()
        org_ids.append(org_id)
        locale = "ar" if i % 2 == 0 else "en"
        # org: id, name, slug, active, default_locale (rest take column defaults)
        w("org", org_id, f"Store {i} Trading", f"store-{i}", "t", locale)
        for r, role in enumerate(["OWNER", "MANAGER", "STAFF", "STAFF"]):
            user_id = uid()
            # app_user: id, email, password_hash, actor_type (seed users never log in)
            w("app_user", user_id, f"user{r}@store-{i}.seed.test",
              "$2a$10$seedseedseedseedseedseedseedseedseedseedseedseedsee", "USER")
            w("user_org_role", user_id, org_id, role)

    # ── ABO catalog, round-robin across orgs ─────────────────────────────────────
    seen = set()
    cat_ids = {}          # (org_id, product_type) -> category_id
    published_per_org = {o: 0 for o in org_ids}
    n_products = n_images = 0

    paths = sorted(glob.glob(os.path.join(ABO_DIR, "listings_*.json.gz")))
    if not paths:
        raise SystemExit(f"no listings_*.json.gz under {ABO_DIR}")

    for path in paths:
        with gzip.open(path, "rt", encoding="utf-8") as fh:
            for line in fh:
                item = json.loads(line)
                item_id = item.get("item_id")
                if not item_id or item_id in seen:
                    continue
                name = pick_lang(item.get("item_name"))
                if not name:
                    continue
                seen.add(item_id)

                org_id = org_ids[n_products % ORGS]
                n_products += 1
                ptype = (item.get("product_type") or [{}])[0].get("value") or "MISC"
                brand = pick_lang(item.get("brand")) or ""
                bullets = [b.get("value") for b in item.get("bullet_point") or [] if b.get("value")]
                copy_en = " • ".join(bullets[:6]) or None

                key = (org_id, ptype)
                if key not in cat_ids:
                    cat_id = uid()
                    cat_ids[key] = cat_id
                    # category: id, org_id, parent_category_id, slug
                    w("category", cat_id, org_id, None, slugify(ptype, "misc"))

                product_id = uid()
                price = round(rng.uniform(20, 8000), 2)
                # product: id, name, description, base_price, sku, org_id, barcode
                w("product", product_id, name[:500], copy_en, price, item_id, org_id, None)

                listing_id = uid()
                r = rng.random()
                status = "PUBLISHED" if r < 0.90 else ("DRAFT" if r < 0.97 else "ARCHIVED")
                sales_price = round(price * rng.uniform(1.05, 1.6), 2)
                slug = f"{slugify(name, 'item')}-{item_id.lower()}"
                # literal timestamp (COPY takes values, not expressions), spread over ~18 months
                published_at = (
                    f"2025-{rng.randint(1, 12):02d}-{rng.randint(1, 28):02d} "
                    f"{rng.randint(0, 23):02d}:{rng.randint(0, 59):02d}:00+00"
                ) if status == "PUBLISHED" else None
                featured = None
                if status == "PUBLISHED":
                    published_per_org[org_id] += 1
                    if published_per_org[org_id] <= 8:
                        featured = published_per_org[org_id]
                # product_listing: id, org_id, product_id, slug, sales_price, status,
                #                  published_at, featured_sort
                w("product_listing", listing_id, org_id, product_id, slug, sales_price,
                  status, published_at, featured)
                w("product_listing_category", listing_id, cat_ids[key])

                # translations: en from ABO; ar synthetic but real-Arabic so the folded
                # generated columns + trigram indexes see representative text
                w("product_listing_translation", uid(), listing_id, "en", name[:500], copy_en)
                ar_noun = AR_TYPES.get(ptype, "منتج")
                ar_title = f"{ar_noun} {rng.choice(AR_ADJ)} {brand} {n_products}".strip()
                w("product_listing_translation", uid(), listing_id, "ar", ar_title, None)

                imgs = [item.get("main_image_id")] + (item.get("other_image_id") or [])
                # ABO sometimes repeats main_image_id inside other_image_id — dedupe, keep order
                imgs = list(dict.fromkeys(i for i in imgs if i))
                for sort, img in enumerate(imgs[:6]):
                    # product_listing_image: id, org_id, listing_id, object_key, alt_text, sort_order
                    # The key must match ObjectStorage.keyPrefix() — "{org}/listings/{listing}/" ,
                    # PLURAL. ProductListingService.attachImage rejects anything else as a
                    # cross-tenant key, so a "listing/" key is unusable the moment perfdb is driven
                    # through the real admin UI rather than read-only benchmarks. The uuid- prefix
                    # production puts on the leaf is a collision guard for merchant filenames; ABO
                    # image ids are already unique, so it is omitted deliberately.
                    w("product_listing_image", uid(), org_id, listing_id,
                      f"{org_id}/listings/{listing_id}/{img}.jpg", name[:80], sort)
                    n_images += 1

                if rng.random() < 0.85:  # tracked
                    stock = 0 if rng.random() < 0.10 else rng.randint(1, 400)
                    # inventory: product_id, stock_qty, reserved_qty, org_id
                    w("inventory", product_id, stock, 0, org_id)

    # ── customers (mixed Arabic/Latin names for the V62 search machinery) ────────
    for i, org_id in enumerate(org_ids):
        for c in range(CUSTOMERS_PER_ORG):
            first = rng.choice(AR_FIRST if c % 2 == 0 else EN_FIRST)
            # customer: id, email, org_id, name, phone, address
            w("customer", uid(), f"cust{c}@store-{i}.seed.test", org_id,
              f"{first} {rng.choice(LAST)}", f"+2010{rng.randint(10000000, 99999999)}",
              f"{rng.randint(1, 200)} Tahrir St, Cairo")

    for f in files.values():
        f.close()
    print(f"orgs={ORGS} products={n_products} images={n_images} "
          f"customers={ORGS * CUSTOMERS_PER_ORG} categories={len(cat_ids)}")


if __name__ == "__main__":
    main()
