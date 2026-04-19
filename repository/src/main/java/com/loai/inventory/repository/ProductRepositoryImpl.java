package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.PRODUCT;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.repository.generated.tables.records.ProductRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProductRepositoryImpl implements ProductRepository {
  private static final Logger log = LoggerFactory.getLogger(ProductRepositoryImpl.class);
  private final DSLContext dsl;

  public ProductRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<Product> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(PRODUCT)
        .where(PRODUCT.ORG_ID.eq(orgId).and(PRODUCT.ID.eq(id)))
        .fetchOptional()
        .map(this::toProduct);
  }

  @Override
  public List<Product> findAll(UUID orgId, int offset, int limit) {
    return dsl.selectFrom(PRODUCT)
        .where(PRODUCT.ORG_ID.eq(orgId))
        .orderBy(PRODUCT.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toProduct);
  }

  @Override
  public long count(UUID orgId) {
    return dsl.fetchCount(dsl.selectFrom(PRODUCT).where(PRODUCT.ORG_ID.eq(orgId)));
  }

  @Override
  public boolean existsBySku(UUID orgId, String sku) {
    return dsl.fetchExists(
        dsl.selectOne().from(PRODUCT).where(PRODUCT.ORG_ID.eq(orgId).and(PRODUCT.SKU.eq(sku))));
  }

  @Override
  public boolean existsBySkuAndIdNot(UUID orgId, String sku, UUID excludeId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(PRODUCT)
            .where(
                PRODUCT.ORG_ID.eq(orgId).and(PRODUCT.SKU.eq(sku)).and(PRODUCT.ID.ne(excludeId))));
  }

  @Override
  public Product insert(Product product) {
    ProductRecord record =
        dsl.insertInto(PRODUCT)
            .set(PRODUCT.ORG_ID, product.getOrgId())
            .set(PRODUCT.NAME, product.getName())
            .set(PRODUCT.DESCRIPTION, product.getDescription())
            .set(PRODUCT.BASE_PRICE, product.getBasePrice())
            .set(PRODUCT.SKU, product.getSku())
            .returning()
            .fetchOne();

    if (record == null) {
      throw new IllegalStateException("INSERT into product returned no record");
    }

    log.debug(
        "Inserted product id={} sku={} orgId={}",
        record.getId(),
        record.getSku(),
        record.getOrgId());
    return toProduct(record);
  }

  @Override
  public Product update(Product product) {
    ProductRecord record =
        dsl.update(PRODUCT)
            .set(PRODUCT.NAME, product.getName())
            .set(PRODUCT.DESCRIPTION, product.getDescription())
            .set(PRODUCT.BASE_PRICE, product.getBasePrice())
            .set(PRODUCT.SKU, product.getSku())
            .set(PRODUCT.UPDATED_AT, OffsetDateTime.now())
            .where(PRODUCT.ORG_ID.eq(product.getOrgId()).and(PRODUCT.ID.eq(product.getId())))
            .returning()
            .fetchOne();

    if (record == null) {
      throw new NotFoundException("Product", product.getId());
    }

    log.debug("Updated product id={}", record.getId());
    return toProduct(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(PRODUCT).where(PRODUCT.ORG_ID.eq(orgId).and(PRODUCT.ID.eq(id))).execute();

    if (deleted == 0) {
      throw new NotFoundException("Product", id);
    }
  }

  private Product toProduct(ProductRecord r) {
    return new Product(
        r.getId(),
        r.getOrgId(),
        r.getName(),
        r.getDescription(),
        r.getBasePrice(),
        r.getSku(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
