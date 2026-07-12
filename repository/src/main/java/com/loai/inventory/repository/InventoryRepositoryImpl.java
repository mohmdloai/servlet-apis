package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryStockFilter;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.repository.generated.tables.records.InventoryRecord;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InventoryRepositoryImpl implements InventoryRepository {
  private static final Logger log = LoggerFactory.getLogger(InventoryRepositoryImpl.class);
  private final DSLContext dsl;

  public InventoryRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<Inventory> findByProductId(UUID orgId, UUID productId) {
    return dsl.selectFrom(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.eq(productId)))
        .fetchOptional()
        .map(this::toInventory);
  }

  @Override
  public Map<UUID, Integer> findAvailableByProductIds(UUID orgId, Collection<UUID> productIds) {
    if (productIds == null || productIds.isEmpty()) {
      return Map.of();
    }
    Field<Integer> available = INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY).as("available");
    Map<UUID, Integer> result = new LinkedHashMap<>();
    dsl.select(INVENTORY.PRODUCT_ID, available)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.in(productIds)))
        .fetch()
        .forEach(r -> result.put(r.get(INVENTORY.PRODUCT_ID), r.get(available)));
    return result;
  }

  @Override
  public List<OverviewRow> listOverview(
      UUID orgId, String q, InventoryStockFilter stock, Integer lowLte, int offset, int limit) {
    return dsl.select(
            PRODUCT.ID,
            PRODUCT.NAME,
            PRODUCT.SKU,
            PRODUCT.BASE_PRICE,
            INVENTORY.PRODUCT_ID,
            INVENTORY.STOCK_QTY,
            INVENTORY.RESERVED_QTY,
            INVENTORY.VERSION,
            INVENTORY.UPDATED_AT)
        .from(PRODUCT)
        .leftJoin(INVENTORY)
        .on(INVENTORY.PRODUCT_ID.eq(PRODUCT.ID).and(INVENTORY.ORG_ID.eq(PRODUCT.ORG_ID)))
        .where(overviewConditions(orgId, q, stock, lowLte))
        // Catalog order — this is a list, not a queue; the created_at convention does not apply.
        .orderBy(PRODUCT.NAME.asc(), PRODUCT.ID.asc())
        .offset(offset)
        .limit(limit)
        .fetch(this::toOverviewRow);
  }

  @Override
  public long countOverview(UUID orgId, String q, InventoryStockFilter stock, Integer lowLte) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(PRODUCT)
            .leftJoin(INVENTORY)
            .on(INVENTORY.PRODUCT_ID.eq(PRODUCT.ID).and(INVENTORY.ORG_ID.eq(PRODUCT.ORG_ID)))
            .where(overviewConditions(orgId, q, stock, lowLte)));
  }

  /** Shared filter for {@link #listOverview} / {@link #countOverview}. */
  private static Condition overviewConditions(
      UUID orgId, String q, InventoryStockFilter stock, Integer lowLte) {
    Condition c = PRODUCT.ORG_ID.eq(orgId);
    if (q != null && !q.isBlank()) {
      String term = q.trim();
      c = c.and(PRODUCT.NAME.containsIgnoreCase(term).or(PRODUCT.SKU.containsIgnoreCase(term)));
    }
    if (stock != null) {
      // available = stock - reserved; null (untracked) makes the comparison null → excluded, which
      // is exactly right for OUT/LOW (both also require a tracked row).
      Field<Integer> available = INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY);
      c =
          switch (stock) {
            case TRACKED -> c.and(INVENTORY.PRODUCT_ID.isNotNull());
            case UNTRACKED -> c.and(INVENTORY.PRODUCT_ID.isNull());
            case OUT -> c.and(INVENTORY.PRODUCT_ID.isNotNull()).and(available.eq(0));
            case LOW ->
                c.and(INVENTORY.PRODUCT_ID.isNotNull())
                    .and(available.le(lowLte != null ? lowLte : 5));
          };
    }
    return c;
  }

  private OverviewRow toOverviewRow(Record r) {
    boolean tracked = r.get(INVENTORY.PRODUCT_ID) != null;
    if (!tracked) {
      return new OverviewRow(
          r.get(PRODUCT.ID),
          r.get(PRODUCT.NAME),
          r.get(PRODUCT.SKU),
          r.get(PRODUCT.BASE_PRICE),
          false,
          null,
          null,
          null,
          null,
          null);
    }
    int stockQty = r.get(INVENTORY.STOCK_QTY);
    int reservedQty = r.get(INVENTORY.RESERVED_QTY);
    return new OverviewRow(
        r.get(PRODUCT.ID),
        r.get(PRODUCT.NAME),
        r.get(PRODUCT.SKU),
        r.get(PRODUCT.BASE_PRICE),
        true,
        stockQty,
        reservedQty,
        stockQty - reservedQty,
        r.get(INVENTORY.VERSION),
        r.get(INVENTORY.UPDATED_AT));
  }

  @Override
  public Inventory insert(Inventory inventory) {
    InventoryRecord record =
        dsl.insertInto(INVENTORY)
            .set(INVENTORY.ORG_ID, inventory.getOrgId())
            .set(INVENTORY.PRODUCT_ID, inventory.getProductId())
            .set(INVENTORY.STOCK_QTY, inventory.getStockQty())
            .set(INVENTORY.RESERVED_QTY, inventory.getReservedQty())
            .returning()
            .fetchOne();

    if (record == null) {
      throw new IllegalStateException("INSERT into inventory returned no record");
    }

    log.debug("Inserted inventory orgId={} productId={}", record.getOrgId(), record.getProductId());
    return toInventory(record);
  }

  @Override
  public Inventory adjustQuantities(
      UUID orgId, UUID productId, int stockDelta, int reservedDelta, long expectedVersion) {
    InventoryRecord record =
        dsl.update(INVENTORY)
            .set(INVENTORY.STOCK_QTY, INVENTORY.STOCK_QTY.plus(stockDelta))
            .set(INVENTORY.RESERVED_QTY, INVENTORY.RESERVED_QTY.plus(reservedDelta))
            .set(INVENTORY.VERSION, INVENTORY.VERSION.plus(1))
            .set(INVENTORY.UPDATED_AT, OffsetDateTime.now())
            .where(INVENTORY.ORG_ID.eq(orgId))
            .and(INVENTORY.PRODUCT_ID.eq(productId))
            .and(INVENTORY.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne();

    if (record == null) {
      throw new ConflictException(
          "Inventory version conflict for orgId="
              + orgId
              + " productId="
              + productId
              + " (expected version "
              + expectedVersion
              + ")");
    }

    log.debug(
        "Adjusted inventory orgId={} productId={} stockDelta={} reservedDelta={} newVersion={}",
        orgId,
        productId,
        stockDelta,
        reservedDelta,
        record.getVersion());
    return toInventory(record);
  }

  @Override
  public Map<UUID, Inventory> lockForUpdate(UUID orgId, Collection<UUID> productIds) {
    if (productIds == null || productIds.isEmpty()) {
      return Map.of();
    }
    // Stable ascending lock order: sort distinct ids; LinkedHashMap preserves the iteration order
    // so callers can rely on it for logging / per-product follow-up writes if they want to.
    var sortedIds = productIds.stream().distinct().sorted().toList();
    Map<UUID, Inventory> out = new LinkedHashMap<>(sortedIds.size());
    dsl.selectFrom(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.in(sortedIds)))
        .orderBy(INVENTORY.PRODUCT_ID.asc())
        .forUpdate()
        .fetch()
        .forEach(r -> out.put(r.getProductId(), toInventory(r)));
    return out;
  }

  @Override
  public void deleteByProductId(UUID orgId, UUID productId) {
    int deleted =
        dsl.deleteFrom(INVENTORY)
            .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.eq(productId)))
            .execute();

    if (deleted == 0) {
      throw new NotFoundException("Inventory", productId);
    }
  }

  @Override
  public boolean existsByProductId(UUID orgId, UUID productId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(INVENTORY)
            .where(INVENTORY.ORG_ID.eq(orgId).and(INVENTORY.PRODUCT_ID.eq(productId))));
  }

  private Inventory toInventory(InventoryRecord r) {
    return new Inventory(
        r.getOrgId(),
        r.getProductId(),
        r.getStockQty(),
        r.getReservedQty(),
        r.getVersion(),
        r.getUpdatedAt());
  }
}
