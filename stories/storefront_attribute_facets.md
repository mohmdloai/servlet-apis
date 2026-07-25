# Slice: Attribute facets at search (`attr_*` filters + facet counts)

> Roadmap item 7 (Tier 2) — rides the variants epic's normalized attribute substrate
> ([`docs/catalog-variants-architecture.md`](../docs/catalog-variants-architecture.md) §7).
> Requires VG1 + VG2 (built alone it has nothing to filter). "1386 results — filter by Size /
> Color / Brand": structured multi-select facets with per-value counts, extending the B3 read with
> the same no-silent-coercion discipline. Feeds frontend story 67. **No migration** (the
> `(attribute_value_id, variant_id)` index shipped in V70).

---

## Goal

`GET /api/public/{orgSlug}/listings?attr_size=m,l&attr_color=red` narrows the catalog to listings
with a matching **active variant** (OR within an attribute, AND across attributes), and — when the
catalog page asks — the envelope carries per-value counts so the filter UI can render
"M (12) · L (4)" honestly.

## Filter grammar (extends the B3 parse — cause-naming 400s, AND-composition)

- **`attr_{attributeSlug}={valueSlug},{valueSlug}…`** — comma list, OR within the attribute; AND
  across `attr_*` params and with `q`/`category`/price/`featured`/`sold`/`sort` (incl.
  `best_selling`).
- Malformed shape (empty value list, blank slug, > 10 values per attribute, > 5 `attr_*` params)
  → **400** naming the parameter. **Unknown** attribute or value slug → resolves to the empty set
  (the category-filter convention: filtering on a thing that doesn't exist finds nothing — a
  merchant deleting an attribute must not 400 every stale bookmarked URL).
- Semantics: a listing matches when **EXISTS an active variant** carrying a selected value for
  every selected attribute (one EXISTS per attribute over
  `product_variant ⋈ variant_attribute_value`). Parent-only (no-variant) listings match no
  `attr_*` filter.

## Facet counts (`include_facets=true`)

- Opt-in via `?include_facets=true` (exactly `true` or absent, else 400) — **only the catalog page
  pays**; strips/home/availability readers keep the lean envelope and the cache-key space stays
  small.
- Envelope gains, alongside `data`/`total`:
  ```
  facets: [{ attribute: {slug, label}, values: [{slug, label, count, selected}] }]
  ```
  Labels locale-resolved (the `?locale=` machinery). Attributes ordered by slug; values by count
  DESC then slug. Attributes with zero in-scope values are omitted.
- **Count semantics** (standard multi-select): each attribute's counts are computed with the
  *other* attributes' selections applied but **not its own** (so sibling values stay reachable);
  `COUNT(DISTINCT product_listing_id)` so a listing with two red variants counts once. Base scope
  = the full B3 predicate (q/category/price/sold/etc).
- **Query shape**: reuse `filterConditions` verbatim as the filtered listing-id set; one grouped
  query for all unselected attributes + one per selected attribute (bounded at ≤ 5 by the
  grammar). No jsonb, no materialized view; `max-age=60` absorbs load like every other read.
  (First use of grouped facet-count SQL in the repo — keep it in
  `ProductListingRepositoryImpl` beside `soldUnits`.)

## Explicitly NOT in this slice

A **rating facet** — `rating_avg` is a computed aggregate, not a listing column; materializing it
is a separate denormalization decision (architecture §7). Brand-as-a-special-case (Brand is just
an attribute). Any admin surface (attributes are managed through VG1's variant editor). Frontend
(story 67).

## Tests (`AttributeFacetsIT` — model: `StorefrontSearchIT`, the 530-line grammar template)

1. **Filtering**: `attr_size=m` narrows to listings with an active M variant; `attr_size=m,l` ORs;
   `attr_size=m&attr_color=red` ANDs (M **and** red must be satisfiable by variants of the
   listing); composes with `q` + price + category + `sort=best_selling`; an **inactive** variant's
   values don't match; a DRAFT listing never appears.
2. **Grammar**: malformed shapes → 400 naming the param; unknown attribute/value slug → empty
   result, 200; `include_facets=maybe` → 400.
3. **Counts**: multi-select semantics (selecting `size=m` keeps L's count visible and correct);
   DISTINCT-listing counting (two red variants on one listing → red counts 1); counts respect the
   rest of the predicate (a price band shrinks them); zero-value attributes omitted; `selected`
   flags accurate; ar labels under `?locale=ar`.
4. **Lean-envelope regression**: without `include_facets` the envelope is byte-identical to today
   (existing `StorefrontSearchIT` + `BestSellersSortIT` run unchanged).

## Definition of done

All four IT groups green; existing search/best-seller suites untouched and green; spotless clean;
full `-Dtest='*IT'` sweep green. No migration, no codegen.
