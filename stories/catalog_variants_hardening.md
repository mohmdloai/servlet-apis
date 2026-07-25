# Fix: variant identity, price coherence, and the listing-delete guard

> Follow-up to slices **VG1/VG2** (`catalog_variants_model.md`, `catalog_variants_commerce.md`;
> decisions in [`docs/catalog-variants-architecture.md`](../docs/catalog-variants-architecture.md)).
> Branch `118_fix/catalog-variants-hardening`. Three defects found reviewing PRs #115–#117 **after**
> they merged, all in VG1's write path. No migration — nothing here changes the schema.
> Frontend pair: `frontst` branch `66_fix/tier2-review-defects` (story 69), independent.

---

## The reality this corrects

Each of the three is silent — none produces an error, all three produce wrong data that nobody is
told about. That is what makes them worth a branch of their own rather than a backlog note.

### 1. A variant price edit desynced the in-store price

`mintChildProduct` seeds the child product's `base_price` from the variant's price, but
`updateChildProduct` had no price parameter at all — it carried name, SKU and barcode and stopped
there. The storefront charges `product_variant.sales_price`; an in-store or phone order line
snapshots `product.base_price` (`SalesOrderService`). So the first time a merchant re-priced a
variant through the rail, that variant acquired **two prices**: the new one in the shop window, the
original one at the counter for anyone scanning its barcode. Permanently, and invisibly — both
numbers look deliberate.

**Fix:** `updateChildProduct` takes the variant's `salesPrice` and writes it to the child, exactly
as the mint does. Pinned by `priceEdit_followsThroughToTheChildProduct_soOneVariantHasOnePrice`.

### 2. `generateKey` was ambiguous for hyphenated value slugs

The key was `String.join("-", optionSlugs)`, and value slugs may themselves contain hyphens. So
`(color=navy-x, size=l)` and `(color=navy, size=x-l)` both generated `"navy-x-l"`. Within one
payload that is a confusing 400 ("duplicate variant key"); **across** PUTs it is worse — a brand-new
combination can generate a key that already belongs to a different variant, and the set-replace
matches on key, so it silently rewrites that variant's options and its child product, which may
already carry stock and order-line history.

**Fix:** join with `_`, which is outside the slug alphabet and therefore cannot appear inside a value
slug — making the generated key an injective function of the combination. Because `variant_key` is a
**wire-visible identity the admin rail round-trips**, a client-supplied key is now validated against
`VARIANT_KEY_PATTERN` (the slug grammar plus `_`) rather than the plain slug pattern; otherwise the
server would reject the very key it had just minted. Pinned by
`generatedKeys_areUnambiguousForHyphenatedValueSlugs`, which asserts both halves: two combinations
stay two variants, and re-PUTting a generated key updates in place.

Existing keys are unaffected (the new grammar is a strict superset, and single-axis keys never
contained a join). Deployed before any real multi-axis `variant_key`s exist, so nothing is migrated.

### 3. Deleting a listing dissolved its live variant set

`ProductListingService.delete` had no variant check, and V70's
`product_variant.product_listing_id … ON DELETE CASCADE` means the delete wipes the bridge rows —
active ones included. The variants vanish from sale with no error, and their stocked child products
are stranded as ordinary listing-less products, which the §5 #11 guard (`existsByProductId`) then
happily lets acquire listings of their own: the exact double identity that guard exists to prevent.

**Fix:** mirror the product-delete guard (§5 #12) — a listing with at least one **active** variant is
a 409 naming the remedy, via the new `ProductVariantRepository.existsActiveVariantForListing`.
Active-only on purpose: deactivating the set first is the merchant's explicit "these are off sale",
the same consent the product guard already takes, and it keeps the "deactivate, then delete" path
that `parentDelete_409sWhileVariantsAreActive_thenFallsBackToTodaysRules` already relies on. Pinned
by `listingDelete_409sWhileVariantsAreActive_ratherThanCascadingTheSetAway`.

## Scope

**In:** `ProductVariantService` (price sync, key grammar), `ProductListingService.delete` (guard),
`ProductVariantRepository` + impl (one new `exists` read), three ITs in `CatalogVariantsIT`.

**Out:** the minor findings from the same review, which are behaviour-preserving and belong with
their own slices — reorder of pre-variant parent lines 400ing at checkout, a has-variants listing
with only parent stock reading `in_stock:true`, `include_facets` being case-insensitive where its
sibling parsers are not, and case-variant `attr_` keys overwriting each other.

## Definition of done

- [x] `mvn -o test` green (194 unit tests) and the full `*IT` battery green (763 tests) — no
      existing test needed changing, which is the claim that these are fixes and not behaviour
      changes.
- [x] Each of the three new tests verified to **fail** against the pre-fix code (they were written
      first and reproduced the defect exactly: a stale `base_price`, `duplicate variant key:
      navy-x-l`, and a delete that silently succeeded).
- [x] `mvn spotless:apply` clean.
