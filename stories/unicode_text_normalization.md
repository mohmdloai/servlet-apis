# Slice: Unicode text normalization at the ingress boundary (Arabic-correct, 50-year-stable)

> **Status: SHIPPED** (migration **V62**). Delivered: `common/.../text/Text.java`
> (`normalizeText`/`normalizeNumeric`/`foldForSearch`/`normalizeEmail`/`normalizeCurrency`) + full
> `TextTest`; every enumerated ingress field routed through it and the five duplicated helpers
> deleted; email unified to NFC+lowercase+trim (incl. login lookup) with a collision-guarded backfill;
> generated `customer/product.name_search` columns (SQL `fold_search` mirror) + `pg_trgm` indexes +
> Arabic-aware product search; ICU (`und-x-icu`) collation on the name columns; idempotent V62
> backfill. End-to-end `UnicodeNormalizationIT` (TestContainers) green. Operational reference +
> libicu runbook + column-typing checklist: `docs/unicode-normalization.md`.
>
> Motivation: the backend stores UTF-8 end-to-end (DB `UTF8`, servlets `StandardCharsets.UTF_8`,
> Jackson UTF-8 JSON) but performs **no Unicode normalization** anywhere (`grep Normalizer` → 0
> hits). For Latin/English that is survivable; for Arabic it silently corrupts identity, dedup,
> search and sort. This slice makes the backend Arabic-correct the durable way: **normalize once, at
> the edge, into a canonical form** — and consolidates the five ad-hoc `trimOrNull` / `normalize*`
> helpers currently duplicated across services into one utility.

---

## Goal

Every piece of externally-supplied text is normalized to a **canonical storage form** at the single
ingress boundary (DTO → domain mapping), and every numeric-semantic field additionally has its
digits folded to ASCII. Human-facing sorts use a linguistically-correct collation.

Done means:
- `"أحمد"` typed two different ways (precomposed vs combining, or via presentation-forms block)
  stores as **one** byte sequence → unique constraints and equality actually work.
- A phone typed `٠١٠٠٦١٢٣٥٨٤` (Arabic-Indic digits) stores and dedups identically to `01006123584`.
- A customer searching `"احمد"` finds `"أحمد"` / `"إحمد"` / `"آحمد"`.
- `ORDER BY name` on Arabic text sorts in Arabic collation order, not code-point order.
- Invisible bidi/zero-width control characters cannot enter identifier fields.
- No user-supplied text is ever normalized more than once, or in the middle of the pipeline.

---

## The model: two forms, one ingress

The whole design rests on separating two representations and computing each **exactly once, on the
way in**:

| Form | What | Used for | Rule |
|---|---|---|---|
| **Storage form** | Faithful **NFC**, bidi-controls stripped, whitespace collapsed. Preserves the user's intent incl. Arabic diacritics (tashkeel). | Display, receipts, invoices, the value returned in responses. | Applied to **all** free-text at ingress. |
| **Match form** | Aggressively folded key: NFC + strip tashkeel + strip tatweel + unify alef/yaa/taa variants + fold digits + casefold Latin. Lossy, never displayed. | Equality/dedup where semantically appropriate, search indexes, `UNIQUE`-by-meaning. | Derived from the storage form; stored in a separate `*_search` column, never shown. |

**Why NFC, not NFKC:** NFKC would collapse legitimate distinctions (e.g. presentation-form
ligatures *and* superscripts/full-width forms) destructively and is not idempotent-safe for display.
NFC is the canonical, loss-free, forward-stable choice for storage. NFKC-style aggression belongs
**only** in the match form, and only for the specific, enumerated folds below — never as a blanket
`Normalizer.normalize(s, NFKC)`.

**Why "once, at the edge":** normalization is idempotent only if applied consistently; applying it in
some paths and not others produces two canonical forms of "the same" string and reintroduces exactly
the bug it prevents. The boundary is the DTO→domain mapper layer (next to today's `trimOrNull`).

---

## The utility (new: `common/.../text/Text.java`)

A single stateless utility in the `common` module (alongside `Pagination`), replacing the scattered
per-service helpers. Pure functions, no I/O, fully unit-tested.

```java
public final class Text {

  /** Canonical STORAGE form for human free-text (names, notes, addresses, product/category names).
   *  NFC · strip bidi & zero-width controls · collapse internal whitespace runs to one space · trim
   *  · empty → null. Idempotent. Preserves Arabic diacritics and letter identity. */
  public static String normalizeText(String s);

  /** STORAGE form for numeric-semantic text (phone, external refs, barcodes). normalizeText PLUS
   *  fold Arabic-Indic (U+0660–0669) and Extended/Persian-Arabic (U+06F0–06F9) digits to ASCII 0–9.
   *  Does NOT strip non-digits (a phone may carry '+', spaces are already collapsed). */
  public static String normalizeNumeric(String s);

  /** MATCH form for search/dedup. normalizeText PLUS: strip tashkeel (harakat U+064B–0652, U+0670),
   *  strip tatweel/kashida (U+0640), unify alef (أإآٱ→ا), yaa (ى→ي), taa marbuta (ة→ه), hamza
   *  seats folded, fold digits, Unicode casefold for Latin. Lossy — never displayed or stored as the
   *  value, only as a derived *_search key. */
  public static String foldForSearch(String s);

  /** ASCII e-mail normalization (lowercase, trim). Replaces AccountService.normalizeEmail /
   *  SalesOrderService.normalize — behaviour-preserving. E-mail is ASCII-domain in v1; IDN/EAI
   *  (Unicode local-parts) is explicitly OUT (see Scope). */
  public static String normalizeEmail(String s);
}
```

Exact character classes to strip as "bidi & zero-width controls": U+200B–200F (ZWSP, ZWNJ*, ZWJ*,
LRM, RLM), U+202A–202E (bidi embeddings/overrides), U+2066–2069 (isolates), U+FEFF (BOM/ZWNBSP).
**Caveat:** ZWNJ (U+200C) and ZWJ (U+200D) are *semantically meaningful in Arabic/Persian
orthography* — strip them from **identifier** fields (phone, refs) but **preserve** them in
display-text fields (names). The utility exposes both: `normalizeText` keeps ZWNJ/ZWJ,
`normalizeNumeric` (identifier context) removes them. This distinction is a required, not optional,
part of "no gaps."

---

## Where it hooks in (every ingress point — enumerated, no gaps)

Applied in the DTO→domain mappers / service input normalization, replacing existing ad-hoc helpers:

| Field | Function | Current helper it replaces |
|---|---|---|
| Customer `name`, `address` | `normalizeText` | `SalesOrderService.trimOrNull` |
| Customer `phone` | `normalizeNumeric` | `SalesOrderService.trimOrNull` |
| Customer `email` | `normalizeEmail` | `SalesOrderService.normalize`, `AccountService.normalizeEmail` |
| Walk-in `customer_name` / `customer_phone` (from `capture_walk_in_customer.md`) | `normalizeText` / `normalizeNumeric` | *(new — fold this in before that story ships)* |
| Org name, Category name, Product name/description | `normalizeText` | inline trims |
| Sales-order / refund / credit-note `notes` | `normalizeText` | inline trims |
| Product `barcode` | `normalizeNumeric` | `ProductService.normalizeBarcode` |
| Payment/txn `provider_ref`, `currency` | `normalizeNumeric` / `normalizeCurrency` (kept) | `PaymentTransactionService`/`RefundService` |
| User's display name (auth/registration) | `normalizeText` | inline |

Every one of the five duplicated helpers found in the codebase
(`trimOrNull` ×3, `normalize`/`normalizeEmail` ×2, `normalizeBarcode`, `normalizeCurrency`) is either
routed through `Text` or deleted. Consolidation is in scope — leaving duplicates is a gap.

---

## Search / match columns

For fields that need Arabic-aware search or meaning-based uniqueness (v1: **customer name**,
**product name**), add a derived `*_search` column populated with `foldForSearch(...)` at write time
and indexed:

- `customer.name_search text` (+ `btree`/`pg_trgm` index for prefix/substring search).
- `product.name_search text` (+ index).
- Uniqueness stays keyed on the existing explicit identity (customer = `(org_id, email)`; product =
  its existing key) — the search column is for **retrieval**, not a new uniqueness rule. Introducing
  meaning-based uniqueness (e.g. dedup customers on folded name) is a **separate product decision**,
  explicitly OUT here.

`pg_trgm` for substring search is Latin-oriented but works acceptably on the folded Arabic key; a
future slice may evaluate a proper FTS config. Noted, not built.

---

## Collation (sort order)

DB collate is currently `en_US.UTF-8` (glibc) → Arabic sorts ~code-point order. **ICU is already
installed** (`und-x-icu`, `ar-x-icu` present). For human-facing name sorts, apply an ICU collation
**per-column or per-query**, not a DB-wide recreate:

- Use `und-x-icu` (Unicode root) for the bilingual app — sane order for Arabic *and* Latin — or
  `ar-x-icu` if strictly Arabic ordering is preferred for name columns.
- Applied to `customer.name`, `product.name`, `category.name` sort paths.

**Operational reality (must be documented, part of "no gaps"):** ICU collations are *versioned*. A
`libicu` upgrade can change sort order and Postgres marks dependent indexes as needing `REINDEX`
(`pg_collation` version mismatch warning). This is expected and manageable — the deliverable
includes a one-paragraph runbook: "on libicu major upgrade → `REINDEX` the ICU-collated name
indexes; no data change, only sort key regeneration."

---

## Column typing rules (audit + guardrail)

- All human text stays `text` (already true) — **never** `varchar(n)` sized in bytes (one Arabic
  char = up to 4 UTF-8 bytes; precomposed vs combining changes code-point count). Any length cap is
  by `char_length()`, never `octet_length()`.
- Add a lightweight review checklist item so future migrations don't reintroduce byte-sized columns.

---

## Backfill (existing data)

A one-off migration normalizes rows written before this slice, so old and new data share one
canonical form (otherwise pre-slice Arabic rows never match post-slice input):

- `V{next}__Backfill_text_normalization.sql` (or a guarded batch job for large tables): rewrite
  `name`/`notes`/`address`/`phone`/`barcode` through the same NFC + digit-fold logic and populate
  `*_search` columns.
- Must be **idempotent** (re-runnable) and run **inside** the same release as the code, so no window
  exists where new normalized input can't match un-normalized stored rows.
- For Postgres-side NFC, use `normalize(col, NFC)` (Postgres 13+ `normalize()` — available on this
  server); digit-folding via `translate(...)`. Search-key backfill mirrors `foldForSearch`.

---

## Scope

### In
- `common/.../text/Text.java` with `normalizeText` / `normalizeNumeric` / `foldForSearch` /
  `normalizeEmail`, fully unit-tested; all four idempotent.
- Route every enumerated ingress field through it; delete the five duplicated helpers.
- `*_search` columns + indexes for customer & product name; populate at write time.
- ICU (`und-x-icu`) collation on the human-facing name sort paths.
- Backfill migration (idempotent) for existing rows + search columns.
- Byte-sizing audit of existing text columns (guardrail note in the migration-review checklist).
- Runbook paragraph: libicu upgrade → `REINDEX` ICU indexes.

### Out (deferred — named, not silently omitted)
- **Meaning-based uniqueness** (dedup customers/products on folded name) — a product call, not a
  normalization concern.
- **Full-text search config / ranking** — `pg_trgm` on the folded key is v1; a proper FTS/`tsvector`
  Arabic config is a later slice.
- **IDN / EAI e-mail** (Unicode domains or local-parts) — e-mail stays ASCII-domain, lowercased;
  Unicode e-mail is a separate slice with its own validation rules.
- **Server-emitted message localization** — services still return English strings today; the durable
  fix is backend returns stable codes + params and the frontend (next-intl, RTL) translates. Tracked
  separately; this slice does **not** localize messages, only normalizes stored/searched *data*.
- **Kashida-insertion / shaping / presentation-layer rendering** — a frontend concern; the backend
  stores logical NFC code points only.
- **Locale-tagged multilingual content** (an entity carrying both an Arabic *and* an English name) —
  a data-model decision (BCP-47-keyed translations), not text normalization.

---

## Security notes

- Stripping bidi overrides (U+202A–202E) from identifiers closes a homograph/display-spoofing vector
  (a `provider_ref` or name that renders differently than it sorts/compares).
- E-mail single-address validation (`EmailAddresses.isSingleValid`) is unchanged and still runs
  *after* `normalizeEmail` — normalization must not weaken the anti-injection guard on the mailed
  order-link recipient (`SalesOrderService.resolveCustomer`).

---

## Tests

`common/src/test/java/.../text/TextTest.java` (pure unit, no container) — the correctness core:
- **NFC**: precomposed vs combining Arabic → identical output; presentation-forms (U+FE8D etc.) →
  canonical letters; output is idempotent (`f(f(x)) == f(x)`) for all three functions.
- **Digit fold**: `٠١٢٣٤٥٦٧٨٩` and `۰۱۲۳۴۵۶۷۸۹` → `0123456789`; `normalizeText` does **not** fold
  digits, `normalizeNumeric` does.
- **Search fold**: `أحمد`/`إحمد`/`آحمد`/`احمد` → one key; tashkeel `مُحَمَّد` → `محمد`; tatweel
  `محـــمد` → `محمد`; taa marbuta `فاطمة`↔`فاطمه`; yaa `على`↔`علي`.
- **Bidi/zero-width**: RLM/LRM/RLO stripped from `normalizeNumeric`; ZWNJ **preserved** by
  `normalizeText` (Persian names) but **removed** by `normalizeNumeric`; BOM stripped everywhere.
- **Whitespace**: internal runs collapse to one space; leading/trailing trimmed; all-blank → null;
  a NBSP (U+00A0) treated as whitespace.
- **Astral/emoji safety**: a 4-byte code point (emoji) passes through storage form intact and does
  not corrupt length/normalization.
- **Latin regression**: ASCII names/e-mails/barcodes behave exactly as the helpers they replace
  (guards against breaking the English path).

Integration (TestContainers, existing suites extended):
- Customer upsert with the *same* Arabic name in two encodings resolves to **one** row (proves NFC at
  the constraint).
- Walk-in phone in Arabic-Indic digits stores as ASCII and equals the Latin-digit form
  (ties to `capture_walk_in_customer.md`).
- `ORDER BY name` under ICU returns Arabic names in collation order, not code-point order.
- Search by `احمد` returns rows stored as `أحمد` via the `name_search` index.
- Backfill migration is idempotent (run twice → no further change) and old rows post-backfill match
  new normalized input.

---

## Rollout phasing (safe order)

1. Ship `Text` + unit tests + route ingress through it (new writes canonical). No schema change yet.
2. Add `*_search` columns + ICU indexes + backfill migration in the **same** release (no
   un-normalized window).
3. Switch name sorts/search to the new columns/collation.
4. Delete the superseded per-service helpers.

Each phase is independently revertible; step 2's backfill is idempotent so a redeploy is safe.
