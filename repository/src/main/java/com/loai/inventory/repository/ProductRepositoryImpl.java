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
  public Optional<Product> findById(UUID id) {

    return dsl.selectFrom(PRODUCT).where(PRODUCT.ID.eq(id)).fetchOptional().map(this::toProduct);
  }

  @Override
  public List<Product> findAll(int offset, int limit) {
    /* TODO */
    return dsl.selectFrom(PRODUCT)
        .orderBy(PRODUCT.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toProduct);
  }

  @Override
  public long count() {
    return dsl.fetchCount(PRODUCT);
  }

  @Override
  public boolean existsBySku(String sku) {
    return dsl.fetchExists(dsl.selectOne().from(PRODUCT).where(PRODUCT.SKU.eq(sku)));
  }

  @Override
  public boolean existsBySkuAndIdNot(String sku, UUID excludeId) {
    return dsl.fetchExists(
        dsl.selectOne().from(PRODUCT).where(PRODUCT.SKU.eq(sku).and(PRODUCT.ID.ne(excludeId))));
  }

  // for any transaction
  // Repository CANNOT know this business rule:
  // "insert customer + create billing + audit log = one atomic unit"

  // Only the SERVICE knows what belongs in one transaction.
  // So only the SERVICE should control the transaction boundary.
  @Override
  public Product insert(Product product) {
    /*
     * Use RETURNING to get the DB-generated id, created_at, updated_at
     * back in a single round-trip — no second SELECT needed.
     */
    ProductRecord record =
        dsl.insertInto(PRODUCT)
            .set(PRODUCT.NAME, product.getName())
            .set(PRODUCT.DESCRIPTION, product.getDescription())
            .set(PRODUCT.BASE_PRICE, product.getBasePrice())
            .set(PRODUCT.SKU, product.getSku())
            .returning() // return all columns
            .fetchOne();

    if (record == null) {
      throw new IllegalStateException("INSERT into product returned no record");
    }

    log.debug("Inserted product id={} sku={}", record.getId(), record.getSku());
    return toProduct(record);
  }

  @Override
  public Product update(Product product) {
    /*
     * Use RETURNING to get the updated row with fresh updated_at
     * back in a single round-trip — no second SELECT needed.
     */
    ProductRecord record =
        dsl.update(PRODUCT)
            .set(PRODUCT.NAME, product.getName())
            .set(PRODUCT.DESCRIPTION, product.getDescription())
            .set(PRODUCT.BASE_PRICE, product.getBasePrice())
            .set(PRODUCT.SKU, product.getSku())
            .set(PRODUCT.UPDATED_AT, OffsetDateTime.now())
            .where(PRODUCT.ID.eq(product.getId()))
            .returning()
            .fetchOne();

    if (record == null) {
      throw new NotFoundException("Product", product.getId());
    }

    log.debug("Updated product id={}", record.getId());
    return toProduct(record);
  }

  @Override
  public void deleteById(UUID id) {
    int deleted = dsl.deleteFrom(PRODUCT).where(PRODUCT.ID.eq(id)).execute();

    if (deleted == 0) {
      throw new NotFoundException("Product", id);
    }
  }

  /**
   * Maps a jOOQ ProductRecord to the domain Product. This is the only place in the system that
   * knows about both types. service and api never see ProductRecord.
   */
  private Product toProduct(ProductRecord r) {
    return new Product(
        r.getId(),
        r.getName(),
        r.getDescription(),
        r.getBasePrice(),
        r.getSku(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
