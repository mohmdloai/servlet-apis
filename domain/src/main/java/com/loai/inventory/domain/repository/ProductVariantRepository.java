package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ProductVariant;
import com.loai.inventory.domain.model.VariantAttribute;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence for the variant substrate (slice VG1, {@code stories/catalog_variants_model.md}): the
 * {@code product_variant} bridge, the org-level {@code attribute}/{@code attribute_value} axes with
 * their translations, and the {@code variant_attribute_value} option combinations.
 *
 * <p>Every write here runs inside the caller's transaction — the admin surface is one atomic
 * <b>set-replace</b>, so a failure anywhere (a duplicate SKU on the fourth variant, a cap breach)
 * must leave the whole set untouched.
 *
 * <p>Nothing in this interface deletes a variant. Removal is {@code active = false} (see {@link
 * #deactivateVariantsNotIn}) because a variant's child product may already be referenced by order
 * lines and inventory rows.
 */
public interface ProductVariantRepository {

  // --- reads ---

  /**
   * Every variant of a listing — <b>including inactive ones</b> — in curated order ({@code
   * sort_order}, then {@code variant_key}). The admin read shows deactivated rows with a badge; the
   * public read (VG2) filters to active itself.
   */
  List<ProductVariant> findByListing(UUID orgId, UUID listingId);

  /**
   * The option combination of each variant, as {@code variantId → [attribute_value_id]}, batch-
   * loaded in one query so a listing with 100 variants costs one round trip, not 100.
   */
  Map<UUID, List<UUID>> findValueIdsByVariant(Collection<UUID> variantIds);

  /** One {@code attribute_value} resolved together with the axis it belongs to. */
  record ValueRef(UUID valueId, UUID attributeId, String attributeSlug, String valueSlug) {}

  /**
   * Resolve {@code attribute_value} ids to their {@code (attribute slug, value slug)} pair — the
   * building block of a variant's option map. Ids that do not exist are simply absent.
   */
  List<ValueRef> findValueRefs(Collection<UUID> valueIds);

  /**
   * Full definitions for the given axes: slug, per-language names, and <b>every</b> value defined
   * on them (org-level, so the editor can offer the existing vocabulary rather than re-typing it).
   * Ordered by attribute slug, then value slug, so the admin view is stable.
   */
  List<VariantAttribute> findAttributes(UUID orgId, Collection<UUID> attributeIds);

  // --- attribute / value upserts (org-level, additive) ---

  /**
   * Get-or-create the org's {@code attribute} row for {@code slug}, returning its id. Idempotent —
   * re-PUTting the same axes creates nothing.
   */
  UUID upsertAttribute(UUID orgId, String slug);

  /** Get-or-create an {@code attribute_value} on an axis, returning its id. Idempotent. */
  UUID upsertAttributeValue(UUID attributeId, String slug);

  /**
   * Set the display name of an axis in one language ({@code attribute_translation}). Upsert on
   * {@code (attribute_id, language)} — re-authoring a name overwrites it; a null/blank name is a
   * no-op (leave whatever is stored, never blank it).
   */
  void upsertAttributeName(UUID attributeId, String language, String name);

  /** Same as {@link #upsertAttributeName} for a value ({@code attribute_value_translation}). */
  void upsertAttributeValueName(UUID valueId, String language, String name);

  // --- variant writes ---

  ProductVariant insertVariant(ProductVariant variant);

  /** Update the mutable fields of an existing variant (price, order, active flag). */
  void updateVariant(UUID id, BigDecimal salesPrice, int sortOrder, boolean active);

  /**
   * Flip {@code active = false} on every variant of the listing whose key is not in {@code
   * keptKeys} — the removal half of the set-replace. Never deletes: the child product may back
   * order lines and inventory. An empty {@code keptKeys} deactivates the whole set.
   */
  void deactivateVariantsNotIn(UUID orgId, UUID listingId, Collection<String> keptKeys);

  /** Replace a variant's option combination wholesale. */
  void replaceVariantValues(UUID variantId, Collection<UUID> valueIds);

  // --- guards (architecture §5 items 11–12) ---

  /**
   * True when {@code productId} is the <b>child</b> of some variant — the listing-create guard
   * (architecture §5 #11). A variant's product can never acquire a listing of its own; it is sold
   * through its parent's.
   */
  boolean existsByProductId(UUID orgId, UUID productId);

  /**
   * True when {@code parentProductId} is the product of a listing that still has at least one
   * <b>active</b> variant — the product-delete guard (§5 #12). Deleting the parent out from under a
   * live variant set would strand children that are still on sale, so it is a 409 naming the
   * remedy.
   */
  boolean existsActiveVariantForParentProduct(UUID orgId, UUID parentProductId);

  /**
   * True when the listing itself still has at least one <b>active</b> variant — the listing-delete
   * guard, the sibling of {@link #existsActiveVariantForParentProduct}. {@code
   * product_variant.product_listing_id} is {@code ON DELETE CASCADE}, so without this a listing
   * delete silently dissolves a live variant set: the bridge rows vanish, and the stocked child
   * products they named are left as ordinary listing-less products that the §5 #11 guard would then
   * let acquire listings of their own. Active-only on purpose — deactivating the set first is the
   * merchant's deliberate "these are no longer on sale", which is exactly what the product-delete
   * guard already treats as consent.
   */
  boolean existsActiveVariantForListing(UUID orgId, UUID listingId);
}
