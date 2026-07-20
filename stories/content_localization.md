# Epic: Content localization — per-language translation rows (BCP-47, N-language, one pattern)

> **Status: SLICED (2026-07-20)** — decisions locked, sliced into six stories (§Story slate). Grounded
> against the live code, not the sketch below: real tables use **UUID** PKs (not the `BIGSERIAL` in the
> SQL sketch); V62 left `fold_search()` as the surviving function but added a generated `name_search`
> only to `product`/`customer` (so `product_listing` has plain-ILIKE search and `category` has none —
> both gain per-language search here); the public route has **no `{locale}` segment** (see the
> Resolution contract — locale arrives as an explicit `?locale=` query param). Depends on and composes
> with the shipped
> Unicode-normalization slice (`unicode_text_normalization.md`, V62): every localized value still goes
> through the same NFC ingress form and the same `fold_search` for its search key — this epic only
> changes *where* localized text lives (one row per language) and *how* it is resolved on read.
>
> Motivation: the storefront is bilingual (`org.default_locale` ∈ `ar`/`en`), but merchant-authored
> **content** is stored three inconsistent ways today — single column (`product_listing.title`,
> `category.name`), paired `_ar`/`_en` columns (`storefront_banner`, `storefront_page`), and none of
> it lets one entity serve an Arabic *and* an English value to the shopper who prefers each. This epic
> collapses all customer-facing content onto **one** pattern: a per-entity `*_translation` table keyed
> by BCP-47 `language`, so any number of languages is an INSERT (never a migration), integrity is
> DB-enforced, and the V62 folded-search index is one generated column that works for every language.

---

## Why tables (not JSONB, not more paired columns)

The decisive constraint is the V62 search/sort design. `name_search` is `GENERATED ALWAYS AS
(fold_search(name)) STORED` — a generated column produces exactly one value. With a language *row*,
that one generated column + one `pg_trgm` GIN index folds and indexes **every** language for free. A
JSONB `{ar,en}` value would force either a generated column per hard-coded language (killing the
N-language flexibility) or an unindexable cross-language `->>'..' OR` scan. Paired columns cap at two
languages and are already the inconsistency this epic removes. Tables give N-language flexibility,
DB-enforced integrity (`UNIQUE(entity, language)`, required default), and clean per-language search —
all three at once.

---

## Scope — which fields are localized

**Customer-facing content (in — this is the surface bilingual shoppers see):**

| Entity | Localized fields | Searched/sorted? | Source today |
|---|---|---|---|
| `product_listing` | `title`, `marketing_copy` | yes — storefront B3 searches both | single columns |
| `category` | `name` | yes — storefront nav + admin | single column (V62 `name_search`) |
| `storefront_banner` | `headline`, `subheading` | no | paired `_ar`/`_en` |
| `storefront_page` | `body` | no | paired `_ar`/`_en` |

**Decisions — CONFIRMED 2026-07-20 (all deferred items stay out of v1):**
- **Internal `product.name` / `product.description`** — **OUT** (stays single-column). `product` is the
  back-office record (admin, inventory, order lines, invoices/PDFs); the shopper never sees it — they
  see `product_listing.title` (in scope). V62 already makes the internal name Arabic-correct. Flip only
  if bilingual staff genuinely need the internal catalog in both languages.
  - ⚠️ **Order-line item-name flag (verify in L2):** the customer-facing invoice/receipt/PDF item line
    must render the *resolved listing title*, not the internal `product.name` — else a bilingual
    customer could get an English item line on an Arabic invoice. Fix (if needed) = snapshot the
    resolved `listing.title` onto the order line at checkout; **confirm what the order line snapshots
    today before L2 ships** (if it already snapshots the listing title, nothing to do).
- **`product_listing.slug`** — **OUT** (stays language-neutral, `UNIQUE(org, slug)`). Per-language SEO
  slugs are an SEO project (routing + canonical + hreflang + sitemap), not storage — its own later slice.
- **Org SEO `meta_title`/`meta_description`** + **`payment_instructions`** — **OUT** (respect V54
  one-per-store). Per-locale SEO meta is only useful *together with* per-language slugs (crawlers index
  per-URL) — bundle both into one future "localized SEO" slice if wanted.

**Out (deferred, named):** meaning-based cross-language dedup; RTL shaping/rendering (frontend);
locale *detection* (frontend — URL segment / `Accept-Language`); a proper FTS config; per-language
media (localized images).

---

## The model — one table per localized entity

```sql
CREATE TABLE product_listing_translation (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id  UUID NOT NULL REFERENCES product_listing(id) ON DELETE CASCADE,
    language    TEXT NOT NULL CHECK (language IN ('ar','en')),   -- BCP-47; widening = one CHECK edit
    title       TEXT NOT NULL COLLATE "und-x-icu",               -- ICU sort per language (V62)
    marketing_copy TEXT,
    -- The V62 fold, per language: one generated key + one GIN index serves any N languages.
    title_search   TEXT GENERATED ALWAYS AS (fold_search(title)) STORED,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (listing_id, language)
);
CREATE INDEX product_listing_translation_search_idx
    ON product_listing_translation USING gin (title_search gin_trgm_ops);
```

`category_translation(category_id, language, name, name_search STORED, …)`,
`storefront_banner_translation(banner_id, language, headline, subheading, …)`, and
`storefront_page_translation(page_id, language, body, …)` follow the identical shape (only the
non-searched tables omit the generated `*_search` column + GIN index).

**Invariants (mirroring the existing banner/page rule "default-locale required, other optional"):**
- Exactly one row per `(entity, language)` — the `UNIQUE`.
- The `org.default_locale` row is **required** (service-enforced on write; a create/update without it
  is a 400) — an entity can never have zero renderable content.
- Other-language rows are optional; a missing one falls back on read (below).
- Every localized value is NFC-normalized at ingress by the same `Text` util; `*_search` is the same
  `fold_search`. This epic adds no new normalization — it reuses V62 per row.

---

## Resolution contract (read)

- **Public storefront** returns a **single resolved value** per field:
  requested locale → `org.default_locale` → (guaranteed present); the resolver never returns null for a
  required field. **Locale source (CONFIRMED):** the backend public route has **no `{locale}` segment**
  (that is a frontend URL concept) — the requested locale arrives as an explicit **`?locale=ar|en`**
  query param on every public content read (`listings`, `categories`, `banners`, `pages`). Unset →
  `org.default_locale`; an unknown/unsupported value → **400** (cause-naming, never a silent default —
  matches the storefront `?sort=` convention). `Cache-Control` values unchanged, but the cache key now
  varies by locale (add `Vary`/locale to the key). **This reverses the shipped C1/C4 "ship both locales,
  client resolves" decision for `banners`/`pages`** — after L4 those reads return a single resolved
  value like every other content read (uniform contract; L5 storefront passes its route locale through).
- **Admin** returns **all** translations (the edit form needs every language), e.g. listing detail
  carries `translations: [{language, title, marketing_copy}]`.
- **Search** (storefront B3 + admin) folds the term with `fold_search` and matches the per-language
  `*_search` **within the resolved locale's rows**, so `"احمد"` finds `"أحمد"` in that locale; results
  de-dupe to one row per listing. Reuses the additive-match approach from
  `ProductRepositoryImpl.searchCondition`.

---

## Migration — expand · migrate · contract (never a breaking single step)

1. **Expand** (`V{n}`): create the `*_translation` tables; **backfill** — single-column entities →
   one `default_locale` row per entity; paired `_ar`/`_en` entities → an `ar` row and an `en` row for
   each non-empty side. Old columns still present and authoritative. Idempotent (guarded inserts).
2. **Cutover** (code, same release or next): services write **and** read translations; reads resolve
   by locale. Optionally dual-write the legacy columns one release for rollback safety.
3. **Contract** (`V{n+k}`, once nothing reads them): `DROP` the legacy columns
   (`product_listing.title`/`marketing_copy`, `category.name` + its V62 `name_search`,
   `storefront_banner.headline_*`/`subheading_*`, `storefront_page.body_*`). The V62 single
   `name_search`/ICU collation on any now-localized entity is superseded by the per-language ones;
   `product.name_search` stays iff `product` stays single-column (recommended).

Backfill sketch (paired → rows), idempotent:
```sql
INSERT INTO storefront_banner_translation (banner_id, language, headline, subheading)
SELECT id, 'ar', headline_ar, subheading_ar FROM storefront_banner b
WHERE (headline_ar IS NOT NULL OR subheading_ar IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM storefront_banner_translation t
                  WHERE t.banner_id = b.id AND t.language = 'ar');
-- …and the 'en' side; single-column entities backfill one row at org.default_locale.
```

---

## Read/write API changes (per entity)

- **Admin writes** accept `translations: [{language, …}]` (or a `{ar:{…}, en:{…}}` map); validate the
  default-locale row is present and each value length-capped; NFC via `Text` unchanged. `PUT` replaces
  the set; a `DELETE` of the default-locale translation is a 409.
- **Admin reads** embed all translations (batch-loaded, one query per page — the listing-image
  precedent, no N+1).
- **Public reads** (`/api/public/{orgSlug}/listings[/{slug}]`, `/categories`, storefront profile
  banners/pages) resolve to the requested locale; response shape gains the resolved field, drops the
  paired fields.
- **Frontend** (`frontst`): the admin editor grows a per-language tab/section per localized field
  (paired-column forms already do two inputs — this generalizes them); the storefront passes its
  route locale to the public reads. UI-chrome i18n (`apps/admin/messages`, next-intl) is unrelated and
  unchanged.

---

## Acceptance criteria

- Creating/updating a listing without the org's default-locale `title` → **400**; with it plus an
  optional second language → both persist as two rows.
- Public listing read in `en` returns the English title; with no English row it **falls back** to the
  default-locale title (never null).
- Searching `"احمد"` on the storefront returns a listing whose `ar` title is `"أحمد"` (per-language
  `title_search`), and `ORDER BY title` within a locale is ICU order.
- Adding a third language (`fr`) is a set of INSERTs — **no schema migration**.
- Banner/page render **identically** before and after the paired→table migration for existing data.
- Backfill is idempotent (re-run → no change); the contract migration leaves no reader of the dropped
  columns.

## Tests

- **Unit**: resolver fallback (requested → default → present); default-required validation; the
  translations DTO round-trip.
- **Integration (TestContainers)**: default-required 400; two-language create + locale-resolved read +
  fallback; Arabic per-language search hit; ICU sort within locale; paired→table backfill parity
  (rendered output unchanged); idempotent backfill; third-language insert needs no DDL.

## Story slate (sliced 2026-07-20 — safe expand·cutover·contract order)

Six stories. Each backend story owns its own branch/PR (`{PR#}_feat/…`, number resolved at branch time)
and is independently revertible; the expand backfill is idempotent so a redeploy is safe. The three
cutover stories (L2–L4) are **code-only** (no schema) and can land in any order after L1 — each is gated
only on L1's tables existing. L6 (contract) is gated on L2–L4 **and** L5 all reading translations.

| # | Story file | Scope | Migration | Gated on |
|---|---|---|---|---|
| **L1** | `content_localization_1_expand.md` | 4 `*_translation` tables (UUID PK) + generated `*_search` + GIN on the two searched ones; idempotent backfill from single/paired columns. No reader/writer change. jOOQ codegen. | **V63** | — |
| **L2** | `content_localization_2_listings.md` | `product_listing` cutover: admin writes/reads `translations:[{language,title,marketing_copy}]` (default-locale required → 400); public read resolves by `?locale=`; per-language Arabic search (`title_search`+`fold_search`) + ICU sort within locale. Dual-writes legacy `title`/`marketing_copy` one release. **Verify order-line item-name snapshot.** | none (code) | L1 |
| **L3** | `content_localization_3_categories.md` | `category` cutover: `translations:[{language,name}]`; public + nav resolve by `?locale=`; per-language `name_search`. Dual-writes legacy `category.name`. | none (code) | L1 |
| **L4** | `content_localization_4_banners_pages.md` | `storefront_banner` + `storefront_page` paired→table: admin `translations[]`; **public reads switch to single-resolved by `?locale=`** (reverses C1/C4); render-parity for existing data. Dual-writes legacy paired columns. | none (code) | L1 |
| **L5** | `content_localization_5_frontend.md` *(frontst)* | Admin per-language editor tabs per localized field; storefront passes its route locale to every public read as `?locale=`. Authored + implemented in the `frontst` repo. | — | L2–L4 |
| **L6** | `content_localization_6_contract.md` | Drop legacy/paired columns (`product_listing.title`/`marketing_copy`, `category.name` + its V62 ICU/collation, `storefront_banner.headline_*`/`subheading_*`, `storefront_page.body_*`) + any superseded single-search. `product.name_search` stays (product is single-column). | **V6x** | L2–L5 |

Ordering: L1 → (L2 ∥ L3 ∥ L4) → L5 → L6. The dual-write in L2–L4 keeps the legacy columns authoritative
for one release so L6 is a pure drop with no reader left behind.
