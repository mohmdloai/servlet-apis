package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.ATTRIBUTE;
import static com.loai.inventory.repository.generated.Tables.ATTRIBUTE_TRANSLATION;
import static com.loai.inventory.repository.generated.Tables.ATTRIBUTE_VALUE;
import static com.loai.inventory.repository.generated.Tables.ATTRIBUTE_VALUE_TRANSLATION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_VARIANT;
import static com.loai.inventory.repository.generated.Tables.VARIANT_ATTRIBUTE_VALUE;

import com.loai.inventory.domain.model.ProductVariant;
import com.loai.inventory.domain.model.VariantAttribute;
import com.loai.inventory.domain.model.VariantAttributeValue;
import com.loai.inventory.domain.repository.ProductVariantRepository;
import com.loai.inventory.repository.generated.tables.records.ProductVariantRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Postgres/jOOQ implementation of the variant substrate (slice VG1).
 *
 * <p>Every statement is org-scoped, and the attribute/value upserts lean on the schema's UNIQUE
 * constraints ({@code (org_id, slug)}, {@code (attribute_id, slug)}) via {@code ON CONFLICT DO
 * NOTHING} + re-select, so a re-PUT of an identical payload creates nothing new.
 */
public final class ProductVariantRepositoryImpl implements ProductVariantRepository {

  private final DSLContext dsl;

  public ProductVariantRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  // --- reads ---

  @Override
  public List<ProductVariant> findByListing(UUID orgId, UUID listingId) {
    return dsl.selectFrom(PRODUCT_VARIANT)
        .where(
            PRODUCT_VARIANT.ORG_ID.eq(orgId).and(PRODUCT_VARIANT.PRODUCT_LISTING_ID.eq(listingId)))
        .orderBy(PRODUCT_VARIANT.SORT_ORDER.asc(), PRODUCT_VARIANT.VARIANT_KEY.asc())
        .fetch()
        .map(ProductVariantRepositoryImpl::toVariant);
  }

  @Override
  public Map<UUID, List<UUID>> findValueIdsByVariant(Collection<UUID> variantIds) {
    if (variantIds == null || variantIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<UUID>> byVariant = new HashMap<>();
    dsl.select(VARIANT_ATTRIBUTE_VALUE.VARIANT_ID, VARIANT_ATTRIBUTE_VALUE.ATTRIBUTE_VALUE_ID)
        .from(VARIANT_ATTRIBUTE_VALUE)
        .where(VARIANT_ATTRIBUTE_VALUE.VARIANT_ID.in(variantIds))
        .forEach(
            r -> byVariant.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(r.value2()));
    return byVariant;
  }

  @Override
  public List<ValueRef> findValueRefs(Collection<UUID> valueIds) {
    if (valueIds == null || valueIds.isEmpty()) {
      return List.of();
    }
    return dsl.select(ATTRIBUTE_VALUE.ID, ATTRIBUTE.ID, ATTRIBUTE.SLUG, ATTRIBUTE_VALUE.SLUG)
        .from(ATTRIBUTE_VALUE)
        .join(ATTRIBUTE)
        .on(ATTRIBUTE.ID.eq(ATTRIBUTE_VALUE.ATTRIBUTE_ID))
        .where(ATTRIBUTE_VALUE.ID.in(valueIds))
        .fetch(r -> new ValueRef(r.value1(), r.value2(), r.value3(), r.value4()));
  }

  @Override
  public List<VariantAttribute> findAttributes(UUID orgId, Collection<UUID> attributeIds) {
    if (attributeIds == null || attributeIds.isEmpty()) {
      return List.of();
    }
    // Three small queries rather than one wide join: an axis has ≤ 40 values and ≤ 2 languages, so
    // the join would multiply rows by language for no gain and the regrouping would be fiddlier.
    Map<UUID, String> slugs = new LinkedHashMap<>();
    dsl.select(ATTRIBUTE.ID, ATTRIBUTE.SLUG)
        .from(ATTRIBUTE)
        .where(ATTRIBUTE.ORG_ID.eq(orgId).and(ATTRIBUTE.ID.in(attributeIds)))
        .orderBy(ATTRIBUTE.SLUG.asc())
        .forEach(r -> slugs.put(r.value1(), r.value2()));
    if (slugs.isEmpty()) {
      return List.of();
    }

    Map<UUID, Map<String, String>> attrNames = new HashMap<>();
    dsl.select(
            ATTRIBUTE_TRANSLATION.ATTRIBUTE_ID,
            ATTRIBUTE_TRANSLATION.LANGUAGE,
            ATTRIBUTE_TRANSLATION.NAME)
        .from(ATTRIBUTE_TRANSLATION)
        .where(ATTRIBUTE_TRANSLATION.ATTRIBUTE_ID.in(slugs.keySet()))
        .forEach(
            r ->
                attrNames
                    .computeIfAbsent(r.value1(), k -> new LinkedHashMap<>())
                    .put(r.value2(), r.value3()));

    Map<UUID, Map<String, String>> valueNames = new HashMap<>();
    dsl.select(
            ATTRIBUTE_VALUE_TRANSLATION.ATTRIBUTE_VALUE_ID,
            ATTRIBUTE_VALUE_TRANSLATION.LANGUAGE,
            ATTRIBUTE_VALUE_TRANSLATION.NAME)
        .from(ATTRIBUTE_VALUE_TRANSLATION)
        .join(ATTRIBUTE_VALUE)
        .on(ATTRIBUTE_VALUE.ID.eq(ATTRIBUTE_VALUE_TRANSLATION.ATTRIBUTE_VALUE_ID))
        .where(ATTRIBUTE_VALUE.ATTRIBUTE_ID.in(slugs.keySet()))
        .forEach(
            r ->
                valueNames
                    .computeIfAbsent(r.value1(), k -> new LinkedHashMap<>())
                    .put(r.value2(), r.value3()));

    Map<UUID, List<VariantAttributeValue>> valuesByAttribute = new HashMap<>();
    dsl.select(ATTRIBUTE_VALUE.ID, ATTRIBUTE_VALUE.ATTRIBUTE_ID, ATTRIBUTE_VALUE.SLUG)
        .from(ATTRIBUTE_VALUE)
        .where(ATTRIBUTE_VALUE.ATTRIBUTE_ID.in(slugs.keySet()))
        .orderBy(ATTRIBUTE_VALUE.SLUG.asc())
        .forEach(
            r ->
                valuesByAttribute
                    .computeIfAbsent(r.value2(), k -> new ArrayList<>())
                    .add(
                        new VariantAttributeValue(
                            r.value1(),
                            r.value2(),
                            r.value3(),
                            valueNames.getOrDefault(r.value1(), Map.of()))));

    List<VariantAttribute> out = new ArrayList<>(slugs.size());
    slugs.forEach(
        (id, slug) ->
            out.add(
                new VariantAttribute(
                    id,
                    orgId,
                    slug,
                    attrNames.getOrDefault(id, Map.of()),
                    valuesByAttribute.getOrDefault(id, List.of()))));
    return out;
  }

  // --- attribute / value upserts ---

  @Override
  public UUID upsertAttribute(UUID orgId, String slug) {
    dsl.insertInto(ATTRIBUTE)
        .set(ATTRIBUTE.ID, UUID.randomUUID())
        .set(ATTRIBUTE.ORG_ID, orgId)
        .set(ATTRIBUTE.SLUG, slug)
        .onConflictDoNothing()
        .execute();
    return dsl.select(ATTRIBUTE.ID)
        .from(ATTRIBUTE)
        .where(ATTRIBUTE.ORG_ID.eq(orgId).and(ATTRIBUTE.SLUG.eq(slug)))
        .fetchOne(ATTRIBUTE.ID);
  }

  @Override
  public UUID upsertAttributeValue(UUID attributeId, String slug) {
    dsl.insertInto(ATTRIBUTE_VALUE)
        .set(ATTRIBUTE_VALUE.ID, UUID.randomUUID())
        .set(ATTRIBUTE_VALUE.ATTRIBUTE_ID, attributeId)
        .set(ATTRIBUTE_VALUE.SLUG, slug)
        .onConflictDoNothing()
        .execute();
    return dsl.select(ATTRIBUTE_VALUE.ID)
        .from(ATTRIBUTE_VALUE)
        .where(ATTRIBUTE_VALUE.ATTRIBUTE_ID.eq(attributeId).and(ATTRIBUTE_VALUE.SLUG.eq(slug)))
        .fetchOne(ATTRIBUTE_VALUE.ID);
  }

  @Override
  public void upsertAttributeName(UUID attributeId, String language, String name) {
    if (name == null || name.isBlank()) {
      return; // never blank an authored name by omitting it
    }
    dsl.insertInto(ATTRIBUTE_TRANSLATION)
        .set(ATTRIBUTE_TRANSLATION.ID, UUID.randomUUID())
        .set(ATTRIBUTE_TRANSLATION.ATTRIBUTE_ID, attributeId)
        .set(ATTRIBUTE_TRANSLATION.LANGUAGE, language)
        .set(ATTRIBUTE_TRANSLATION.NAME, name)
        .onConflict(ATTRIBUTE_TRANSLATION.ATTRIBUTE_ID, ATTRIBUTE_TRANSLATION.LANGUAGE)
        .doUpdate()
        .set(ATTRIBUTE_TRANSLATION.NAME, name)
        .execute();
  }

  @Override
  public void upsertAttributeValueName(UUID valueId, String language, String name) {
    if (name == null || name.isBlank()) {
      return;
    }
    dsl.insertInto(ATTRIBUTE_VALUE_TRANSLATION)
        .set(ATTRIBUTE_VALUE_TRANSLATION.ID, UUID.randomUUID())
        .set(ATTRIBUTE_VALUE_TRANSLATION.ATTRIBUTE_VALUE_ID, valueId)
        .set(ATTRIBUTE_VALUE_TRANSLATION.LANGUAGE, language)
        .set(ATTRIBUTE_VALUE_TRANSLATION.NAME, name)
        .onConflict(
            ATTRIBUTE_VALUE_TRANSLATION.ATTRIBUTE_VALUE_ID, ATTRIBUTE_VALUE_TRANSLATION.LANGUAGE)
        .doUpdate()
        .set(ATTRIBUTE_VALUE_TRANSLATION.NAME, name)
        .execute();
  }

  // --- variant writes ---

  @Override
  public ProductVariant insertVariant(ProductVariant variant) {
    ProductVariantRecord record =
        dsl.insertInto(PRODUCT_VARIANT)
            .set(PRODUCT_VARIANT.ORG_ID, variant.orgId())
            .set(PRODUCT_VARIANT.PRODUCT_LISTING_ID, variant.productListingId())
            .set(PRODUCT_VARIANT.PRODUCT_ID, variant.productId())
            .set(PRODUCT_VARIANT.VARIANT_KEY, variant.variantKey())
            .set(PRODUCT_VARIANT.SALES_PRICE, variant.salesPrice())
            .set(PRODUCT_VARIANT.SORT_ORDER, variant.sortOrder())
            .set(PRODUCT_VARIANT.ACTIVE, variant.active())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into product_variant returned no record");
    }
    return toVariant(record);
  }

  @Override
  public void updateVariant(UUID id, BigDecimal salesPrice, int sortOrder, boolean active) {
    dsl.update(PRODUCT_VARIANT)
        .set(PRODUCT_VARIANT.SALES_PRICE, salesPrice)
        .set(PRODUCT_VARIANT.SORT_ORDER, sortOrder)
        .set(PRODUCT_VARIANT.ACTIVE, active)
        .set(PRODUCT_VARIANT.UPDATED_AT, OffsetDateTime.now())
        .where(PRODUCT_VARIANT.ID.eq(id))
        .execute();
  }

  @Override
  public void deactivateVariantsNotIn(UUID orgId, UUID listingId, Collection<String> keptKeys) {
    var condition =
        PRODUCT_VARIANT
            .ORG_ID
            .eq(orgId)
            .and(PRODUCT_VARIANT.PRODUCT_LISTING_ID.eq(listingId))
            .and(PRODUCT_VARIANT.ACTIVE.isTrue());
    if (keptKeys != null && !keptKeys.isEmpty()) {
      condition = condition.and(PRODUCT_VARIANT.VARIANT_KEY.notIn(keptKeys));
    }
    dsl.update(PRODUCT_VARIANT)
        .set(PRODUCT_VARIANT.ACTIVE, false)
        .set(PRODUCT_VARIANT.UPDATED_AT, OffsetDateTime.now())
        .where(condition)
        .execute();
  }

  @Override
  public void replaceVariantValues(UUID variantId, Collection<UUID> valueIds) {
    dsl.deleteFrom(VARIANT_ATTRIBUTE_VALUE)
        .where(VARIANT_ATTRIBUTE_VALUE.VARIANT_ID.eq(variantId))
        .execute();
    if (valueIds == null || valueIds.isEmpty()) {
      return;
    }
    for (UUID valueId : valueIds) {
      dsl.insertInto(VARIANT_ATTRIBUTE_VALUE)
          .set(VARIANT_ATTRIBUTE_VALUE.VARIANT_ID, variantId)
          .set(VARIANT_ATTRIBUTE_VALUE.ATTRIBUTE_VALUE_ID, valueId)
          .onConflictDoNothing()
          .execute();
    }
  }

  // --- guards ---

  @Override
  public boolean existsByProductId(UUID orgId, UUID productId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT_VARIANT)
            .where(PRODUCT_VARIANT.ORG_ID.eq(orgId).and(PRODUCT_VARIANT.PRODUCT_ID.eq(productId))));
  }

  @Override
  public boolean existsActiveVariantForParentProduct(UUID orgId, UUID parentProductId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT_VARIANT)
            .join(PRODUCT_LISTING)
            .on(PRODUCT_LISTING.ID.eq(PRODUCT_VARIANT.PRODUCT_LISTING_ID))
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(orgId)
                    .and(PRODUCT_LISTING.PRODUCT_ID.eq(parentProductId))
                    .and(PRODUCT_VARIANT.ACTIVE.isTrue())));
  }

  // --- mappers ---

  private static ProductVariant toVariant(ProductVariantRecord r) {
    return new ProductVariant(
        r.getId(),
        r.getOrgId(),
        r.getProductListingId(),
        r.getProductId(),
        r.getVariantKey(),
        r.getSalesPrice(),
        r.getSortOrder() == null ? 0 : r.getSortOrder(),
        Boolean.TRUE.equals(r.getActive()));
  }
}
