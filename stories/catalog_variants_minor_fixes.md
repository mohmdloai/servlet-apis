# Fix: the four VG2/facets minor findings

> Second follow-up to slices **VG2** and **attribute facets** (`catalog_variants_commerce.md`,
> `storefront_attribute_facets.md`). Branch `121_fix/variant-and-facet-minors`. These are the four
> findings the PR #115–#117 review classed **minor** and the #118 hardening branch deliberately left
> out; #118 took the three that could corrupt or destroy data. No migration.
>
> **Method: adversarial test first.** Each defect got a test written to assert the *contract the
> shopper relies on*, run against unfixed `master` to prove the defect is real, and only then fixed.
> All four went red first — one of them not for the reason expected (see #4), which is exactly why
> the order matters.

---

## 1. Reorder handed back a cart that checkout refuses

**Adversarial test:** `VariantCommerceIT.reorder_ofALineBoughtBeforeVariantsExisted_neverHandsBackAnUnbuyableCart`
— buy a variant-less listing, let the merchant add variants to it, reorder, then *actually check out
every item reorder returned*. Asserting the contract ("whatever reorder hands back is buyable")
rather than an internal field is what makes this a proof.

**Red on master:** `ValidationException: variant is required for mug`.

A line bought before the listing had variants carries the **parent** product id. `resolveForReorder`'s
parent half matched it on `product_listing.product_id` with no check for variants having appeared
since, so it came back as a normal item naming no variant — and a variant-less line on a has-variants
listing is a hard 400. The shopper's "buy it again" produced a cart that died on Pay with no way to
fix it from the cart.

**Fix:** the parent half excludes listings with an active variant (`NOT EXISTS`), so the line falls
through to `unavailable` — which is the honest answer: the thing they bought no longer exists as a
buyable option. This mirrors what already happened to a *deactivated variant* line.

## 2. A has-variants listing advertised the parent's unbuyable stock

**Adversarial test:** `VariantCommerceIT.hasVariantsListing_withStockOnlyOnTheParent_isNeverAdvertisedInStock`
— stock the parent, leave every active variant empty, first prove **every** purchase attempt 409s,
and only then assert all three availability surfaces say `false`. Plus a control that restocks one
variant and flips all three back, so the test is about the parent being irrelevant rather than about
the listing being unconditionally out of stock.

**Red on master:** `expected: <false> but was: <true>` on the detail read.

§4 computed listing `in_stock` as a **union** of the parent and the active variants. But once a
listing has variants the parent is unbuyable *by construction* — checkout rejects a variant-less line
for it — so its stock cannot make anything on that page purchasable. The card said "In stock" and led
to a picker where every option was disabled.

**Fix:** variants present ⇒ the answer is theirs alone; absent ⇒ the parent's, exactly as before.
Applied at all three sites that must agree: list rows, the detail read, and the `availability` batch
query. The SQL branches on `EXISTS(active variant)` rather than coalescing the two arms, because the
variant subquery is a `MAX` that is NULL both when there are no variants *and* when the variants
merely have no inventory rows yet — two cases that must answer differently.

## 3. `include_facets` accepted a spelling its siblings reject

**Adversarial test:** `AttributeFacetsIT.includeFacets_isCaseSensitive_likeItsSiblingBooleanParams`
— asserts `sold=TRUE` and `include_facets=TRUE` behave the *same way*, which is the actual property
worth having; naming the convention rather than the constant means the test still means something if
the convention ever changes.

**Red on master:** `include_facets=TRUE` was accepted while `sold=TRUE` 400s.

`parseIncludeFacets` used `equalsIgnoreCase` where `parseSold`/`parseFeatured` use `equals`, and
where `CLAUDE.md` already documented "exactly `true` or absent". One endpoint answering the same wire
value two ways teaches a client a rule the next parameter breaks.

**Fix:** `equals`. The code now matches the documentation rather than the reverse.

## 4. Case-variant `attr_` keys silently dropped half a selection

**Adversarial test:** `AttributeFacetsIT.caseVariantAttrKeys_foldTogether_ratherThanOneSilentlyWinning`.

**Red on master — but note how.** The first draft asserted `attr_Size=m + attr_size=l` equals
`attr_size=m,l` and **passed**, purely because both fixtures happen to carry an L: last-wins and
merge gave the same rows. The test was rewritten to assert *both* orderings against the merged
result, on values where a dropped half is visible either way. That near-miss is the argument for
writing the adversarial test first and reading *why* it goes red: a test that passes for the wrong
reason is worse than no test.

The servlet keys its map on the raw parameter name; the service lower-cases it to canonicalize, and
then `put` **overwrote** the earlier spelling. Separately the ≤5 cap was applied to the raw map, so
six spellings of *one* axis produced a bogus "at most 5 attr_* filters" 400 (which is how the test
actually failed first).

**Fix:** fold values by canonical name — the servlet already folds a repeated same-case parameter, so
this just extends the same rule to case — and apply the caps to the **collapsed** map.

## Scope

**In:** `StorefrontService` (`normalizeAttributeFilters`, `parseIncludeFacets`, both `in_stock`
sites), `ProductListingRepositoryImpl` (`resolveAvailability`, `resolveForReorder`, new
`hasActiveVariant()`), four ITs, and the two `CLAUDE.md` lines that documented the old availability
union and the `attr_*` caps.

**Out:** nothing from the review remains — with #118 and this branch, all seven findings are closed.

## Definition of done

- [x] Each of the four tests verified **red against unfixed `master`**, with the failure read and
      understood (not merely observed), then green after the fix.
- [x] Full `*IT` battery green — the availability change touches a query several slices read.
- [x] `mvn spotless:apply` clean.
