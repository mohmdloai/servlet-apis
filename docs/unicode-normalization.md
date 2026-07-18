# Unicode text normalization

Reference for the Arabic-correct text-normalization slice (`stories/unicode_text_normalization.md`,
shipped in **V62**). The backend now normalizes every externally-supplied string to a canonical form
**once, at the ingress boundary**, so Arabic identity, dedup, search and sort behave correctly.

## The three forms

Implemented once in `common/.../text/Text.java` (pure, JDK-only, fully unit-tested in `TextTest`) and
mirrored in SQL by the immutable `fold_search(text)` function (V62):

| Form | Function | Rule | Used for |
|---|---|---|---|
| **Storage (text)** | `Text.normalizeText` | NFC · surgical fold of the Arabic presentation-forms blocks · strip bidi/zero-width controls (keep ZWNJ/ZWJ) · collapse whitespace · trim | names, addresses, notes, org/category/product names, headlines |
| **Storage (numeric)** | `Text.normalizeNumeric` | as above **plus** fold Arabic-Indic/Persian digits → ASCII and strip the joiners | phone, barcode, provider refs, tracking numbers |
| **Match key** | `Text.foldForSearch` / SQL `fold_search` | storage form **plus** strip tashkeel/tatweel/standalone-hamza, unify alef/waw-hamza/yaa-hamza/alef-maqsura/taa-marbuta, fold digits, casefold Latin | the derived `*_search` columns, meaning-based retrieval |

**Why NFC, not NFKC:** NFC is loss-free and forward-stable for display; NFKC would destroy legitimate
distinctions (full-width Latin, superscripts, non-Arabic ligatures). The only compatibility folding in
the storage form is *surgical* — restricted to the two Arabic presentation-forms blocks — so those
distinctions are preserved. NFKC-style aggression lives only in the match key.

Email is unified to **NFC + lowercase + trim** everywhere (registration, login lookup, admin/member
create, sales/portal), so an address keys identically on every plane. V62 backfills existing
`app_user`/`customer` emails to that form (collision-guarded).

## Ingress is the single boundary

Normalization happens in the DTO→domain / service-input layer, replacing the five ad-hoc helpers that
used to be copy-pasted across services (`trimOrNull` ×3, `normalize`/`normalizeEmail`,
`normalizeBarcode`, `normalizeCurrency`). Do **not** re-normalize downstream — applying it in some
paths and not others reintroduces two canonical forms of "the same" string.

## Search columns (`customer.name_search`, `product.name_search`)

`GENERATED ALWAYS AS (fold_search(name)) STORED` — Postgres computes the folded key for every existing
row on `ADD COLUMN` and recomputes it on every write, so **the key can never drift from `name`** and no
application write-path code is needed. Indexed with `pg_trgm` GIN for substring search. Product search
(`ProductRepositoryImpl.searchCondition`) matches `name_search` against `fold_search(term)` *additively*
(never removes a result), so `"احمد"` finds `"أحمد"`. Uniqueness is unchanged (customer = `(org, email)`,
product = `(org, sku)`); the search column is for retrieval only.

## Collation (sort order)

`customer/product/category/org.name` are `text COLLATE "und-x-icu"`, so `ORDER BY name` sorts in
Unicode/Arabic collation order, not code-point order. `und-x-icu` is **deterministic**: ordering uses
ICU, equality stays byte-exact — no unique constraint or equality lookup changes behaviour.

### Runbook — libicu major upgrade

A `libicu` major upgrade can change ICU sort order; Postgres then reports a collation-version mismatch
for objects depending on `und-x-icu`. There are currently **no indexes on the ICU-collated name
columns**, so no `REINDEX` is required today. If/when a name index is added, after a libicu upgrade:
`REINDEX` that index, then `ALTER COLLATION "und-x-icu" REFRESH VERSION;` to clear the warning. This
regenerates sort keys only — **no data changes**.

## Column-typing guardrail (migration review checklist)

- Human text columns are `text`, **never `varchar(n)`**. (Postgres `varchar(n)` counts *characters*,
  not bytes, but precomposed-vs-combining input changes the code-point count, so a byte/char cap can
  still reject "the same" string; and NFC-at-ingress already bounds growth.) Any length limit is a
  service-layer `char_length` rule, never `octet_length`, and never the column type.
- A new migration that adds a human-text column as `varchar(n)` is a review reject → use `text`.
- A new human-text ingress field must route through `Text` in the service layer; a new
  numeric-semantic field through `Text.normalizeNumeric`.

## Scope notes (deferred, named)

Meaning-based uniqueness (dedup on folded name), a proper FTS/`tsvector` Arabic config, IDN/EAI Unicode
email, and server-message localization are out of scope — see the story's Scope section. Product-listing
`title`/`marketing_copy` normalization is a natural follow-up (held out here because `marketing_copy`
can carry intentional line breaks that the whitespace-collapsing storage form would flatten).
