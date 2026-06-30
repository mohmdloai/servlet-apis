package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_CATEGORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_IMAGE;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductListingImage;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.repository.generated.tables.records.ProductListingCategoryRecord;
import com.loai.inventory.repository.generated.tables.records.ProductListingImageRecord;
import com.loai.inventory.repository.generated.tables.records.ProductListingRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProductListingRepositoryImpl implements ProductListingRepository {
  private static final Logger log = LoggerFactory.getLogger(ProductListingRepositoryImpl.class);
  private final DSLContext dsl;

  public ProductListingRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  // --- listing CRUD ---

  @Override
  public Optional<ProductListing> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.ID.eq(id)))
        .fetchOptional()
        .map(this::toListing);
  }

  @Override
  public List<ProductListing> findAll(UUID orgId, int offset, int limit) {
    return dsl.selectFrom(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId))
        .orderBy(PRODUCT_LISTING.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toListing);
  }

  @Override
  public List<ProductListing> findAllByStatus(
      UUID orgId, ListingStatus status, int offset, int limit) {
    return dsl.selectFrom(PRODUCT_LISTING)
        .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.STATUS.eq(toGenerated(status))))
        .orderBy(PRODUCT_LISTING.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toListing);
  }

  @Override
  public long count(UUID orgId) {
    return dsl.fetchCount(dsl.selectFrom(PRODUCT_LISTING).where(PRODUCT_LISTING.ORG_ID.eq(orgId)));
  }

  @Override
  public long countByStatus(UUID orgId, ListingStatus status) {
    return dsl.fetchCount(
        dsl.selectFrom(PRODUCT_LISTING)
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(orgId)
                    .and(PRODUCT_LISTING.STATUS.eq(toGenerated(status)))));
  }

  @Override
  public ProductListing insert(ProductListing listing) {
    ProductListingRecord record =
        dsl.insertInto(PRODUCT_LISTING)
            .set(PRODUCT_LISTING.ORG_ID, listing.getOrgId())
            .set(PRODUCT_LISTING.PRODUCT_ID, listing.getProductId())
            .set(PRODUCT_LISTING.TITLE, listing.getTitle())
            .set(PRODUCT_LISTING.MARKETING_COPY, listing.getMarketingCopy())
            .set(PRODUCT_LISTING.SLUG, listing.getSlug())
            .set(PRODUCT_LISTING.SALES_PRICE, listing.getSalesPrice())
            .set(PRODUCT_LISTING.STATUS, toGenerated(listing.getStatus()))
            .set(PRODUCT_LISTING.PUBLISHED_AT, listing.getPublishedAt())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into product_listing returned no record");
    }
    log.debug("Inserted product_listing id={} orgId={}", record.getId(), record.getOrgId());
    return toListing(record);
  }

  @Override
  public ProductListing update(ProductListing listing) {
    ProductListingRecord record =
        dsl.update(PRODUCT_LISTING)
            .set(PRODUCT_LISTING.TITLE, listing.getTitle())
            .set(PRODUCT_LISTING.MARKETING_COPY, listing.getMarketingCopy())
            .set(PRODUCT_LISTING.SLUG, listing.getSlug())
            .set(PRODUCT_LISTING.SALES_PRICE, listing.getSalesPrice())
            .set(PRODUCT_LISTING.UPDATED_AT, OffsetDateTime.now())
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(listing.getOrgId())
                    .and(PRODUCT_LISTING.ID.eq(listing.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("ProductListing", listing.getId());
    }
    log.debug("Updated product_listing id={}", record.getId());
    return toListing(record);
  }

  @Override
  public ProductListing updateStatus(ProductListing listing) {
    ProductListingRecord record =
        dsl.update(PRODUCT_LISTING)
            .set(PRODUCT_LISTING.STATUS, toGenerated(listing.getStatus()))
            .set(PRODUCT_LISTING.PUBLISHED_AT, listing.getPublishedAt())
            .set(PRODUCT_LISTING.UPDATED_AT, OffsetDateTime.now())
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(listing.getOrgId())
                    .and(PRODUCT_LISTING.ID.eq(listing.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("ProductListing", listing.getId());
    }
    log.debug(
        "Updated product_listing status id={} status={}", record.getId(), listing.getStatus());
    return toListing(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(PRODUCT_LISTING)
            .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.ID.eq(id)))
            .execute();
    if (deleted == 0) {
      throw new NotFoundException("ProductListing", id);
    }
  }

  @Override
  public boolean existsByProductId(UUID orgId, UUID productId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT_LISTING)
            .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.PRODUCT_ID.eq(productId))));
  }

  @Override
  public boolean existsBySlug(UUID orgId, String slug) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT_LISTING)
            .where(PRODUCT_LISTING.ORG_ID.eq(orgId).and(PRODUCT_LISTING.SLUG.eq(slug))));
  }

  @Override
  public boolean existsBySlugAndIdNot(UUID orgId, String slug, UUID excludeId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT_LISTING)
            .where(
                PRODUCT_LISTING
                    .ORG_ID
                    .eq(orgId)
                    .and(PRODUCT_LISTING.SLUG.eq(slug))
                    .and(PRODUCT_LISTING.ID.ne(excludeId))));
  }

  @Override
  public boolean productExists(UUID orgId, UUID productId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT)
            .where(PRODUCT.ORG_ID.eq(orgId).and(PRODUCT.ID.eq(productId))));
  }

  // --- listing ⇄ category ---

  @Override
  public void replaceCategories(UUID listingId, Set<UUID> categoryIds) {
    dsl.deleteFrom(PRODUCT_LISTING_CATEGORY)
        .where(PRODUCT_LISTING_CATEGORY.LISTING_ID.eq(listingId))
        .execute();
    if (categoryIds.isEmpty()) {
      return;
    }
    List<ProductListingCategoryRecord> rows = new ArrayList<>(categoryIds.size());
    for (UUID categoryId : categoryIds) {
      ProductListingCategoryRecord r = dsl.newRecord(PRODUCT_LISTING_CATEGORY);
      r.setListingId(listingId);
      r.setCategoryId(categoryId);
      rows.add(r);
    }
    dsl.batchInsert(rows).execute();
  }

  @Override
  public List<UUID> findCategoryIds(UUID listingId) {
    return dsl.select(PRODUCT_LISTING_CATEGORY.CATEGORY_ID)
        .from(PRODUCT_LISTING_CATEGORY)
        .where(PRODUCT_LISTING_CATEGORY.LISTING_ID.eq(listingId))
        .fetch(PRODUCT_LISTING_CATEGORY.CATEGORY_ID);
  }

  @Override
  public long countCategoriesInOrg(UUID orgId, Set<UUID> categoryIds) {
    if (categoryIds.isEmpty()) {
      return 0L;
    }
    return dsl.fetchCount(
        dsl.selectOne()
            .from(CATEGORY)
            .where(CATEGORY.ORG_ID.eq(orgId).and(CATEGORY.ID.in(categoryIds))));
  }

  // --- images ---

  @Override
  public ProductListingImage insertImage(ProductListingImage image) {
    ProductListingImageRecord record =
        dsl.insertInto(PRODUCT_LISTING_IMAGE)
            .set(PRODUCT_LISTING_IMAGE.ORG_ID, image.getOrgId())
            .set(PRODUCT_LISTING_IMAGE.LISTING_ID, image.getListingId())
            .set(PRODUCT_LISTING_IMAGE.OBJECT_KEY, image.getObjectKey())
            .set(PRODUCT_LISTING_IMAGE.ALT_TEXT, image.getAltText())
            .set(PRODUCT_LISTING_IMAGE.SORT_ORDER, image.getSortOrder())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into product_listing_image returned no record");
    }
    return toImage(record);
  }

  @Override
  public List<ProductListingImage> findImages(UUID listingId) {
    return dsl.selectFrom(PRODUCT_LISTING_IMAGE)
        .where(PRODUCT_LISTING_IMAGE.LISTING_ID.eq(listingId))
        .orderBy(PRODUCT_LISTING_IMAGE.SORT_ORDER.asc(), PRODUCT_LISTING_IMAGE.CREATED_AT.asc())
        .fetch()
        .map(this::toImage);
  }

  @Override
  public void deleteImage(UUID orgId, UUID listingId, UUID imageId) {
    int deleted =
        dsl.deleteFrom(PRODUCT_LISTING_IMAGE)
            .where(
                PRODUCT_LISTING_IMAGE
                    .ORG_ID
                    .eq(orgId)
                    .and(PRODUCT_LISTING_IMAGE.LISTING_ID.eq(listingId))
                    .and(PRODUCT_LISTING_IMAGE.ID.eq(imageId)))
            .execute();
    if (deleted == 0) {
      throw new NotFoundException("ProductListingImage", imageId);
    }
  }

  // --- mappers / enum bridge ---

  private static com.loai.inventory.repository.generated.enums.ListingStatus toGenerated(
      ListingStatus status) {
    return com.loai.inventory.repository.generated.enums.ListingStatus.valueOf(status.name());
  }

  private ProductListing toListing(ProductListingRecord r) {
    return new ProductListing(
        r.getId(),
        r.getOrgId(),
        r.getProductId(),
        r.getTitle(),
        r.getMarketingCopy(),
        r.getSlug(),
        r.getSalesPrice(),
        ListingStatus.valueOf(r.getStatus().name()),
        r.getPublishedAt(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }

  private ProductListingImage toImage(ProductListingImageRecord r) {
    return new ProductListingImage(
        r.getId(),
        r.getOrgId(),
        r.getListingId(),
        r.getObjectKey(),
        r.getAltText(),
        r.getSortOrder() == null ? 0 : r.getSortOrder(),
        r.getCreatedAt());
  }
}
