package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.repository.generated.tables.records.InventoryRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InventoryRepositoryImpl implements InventoryRepository {
  private static final Logger log = LoggerFactory.getLogger(InventoryRepositoryImpl.class);
  private final DSLContext dsl;

  public InventoryRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<Inventory> findByProductId(UUID productId) {
    return dsl.selectFrom(INVENTORY)
        .where(INVENTORY.PRODUCT_ID.eq(productId))
        .fetchOptional()
        .map(this::toInventory);
  }

  @Override
  public Inventory insert(Inventory inventory) {
    InventoryRecord record =
        dsl.insertInto(INVENTORY)
            .set(INVENTORY.PRODUCT_ID, inventory.getProductId())
            .set(INVENTORY.STOCK_QTY, inventory.getStockQty())
            .set(INVENTORY.RESERVED_QTY, inventory.getReservedQty())
            .returning()
            .fetchOne();

    if (record == null) {
      throw new IllegalStateException("INSERT into inventory returned no record");
    }

    log.debug("Inserted inventory for productId={}", record.getProductId());
    return toInventory(record);
  }

  @Override
  public Inventory adjustQuantities(
      UUID productId, int stockDelta, int reservedDelta, long expectedVersion) {
    InventoryRecord record =
        dsl.update(INVENTORY)
            .set(INVENTORY.STOCK_QTY, INVENTORY.STOCK_QTY.plus(stockDelta))
            .set(INVENTORY.RESERVED_QTY, INVENTORY.RESERVED_QTY.plus(reservedDelta))
            .set(INVENTORY.VERSION, INVENTORY.VERSION.plus(1))
            .set(INVENTORY.UPDATED_AT, OffsetDateTime.now())
            .where(INVENTORY.PRODUCT_ID.eq(productId))
            .and(INVENTORY.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne();

    if (record == null) {
      throw new ConflictException(
          "Inventory version conflict for productId="
              + productId
              + " (expected version "
              + expectedVersion
              + ")");
    }

    log.debug(
        "Adjusted inventory productId={} stockDelta={} reservedDelta={} newVersion={}",
        productId,
        stockDelta,
        reservedDelta,
        record.getVersion());
    return toInventory(record);
  }

  @Override
  public void deleteByProductId(UUID productId) {
    int deleted = dsl.deleteFrom(INVENTORY).where(INVENTORY.PRODUCT_ID.eq(productId)).execute();

    if (deleted == 0) {
      throw new NotFoundException("Inventory", productId);
    }
  }

  @Override
  public boolean existsByProductId(UUID productId) {
    return dsl.fetchExists(
        dsl.selectOne().from(INVENTORY).where(INVENTORY.PRODUCT_ID.eq(productId)));
  }

  private Inventory toInventory(InventoryRecord r) {
    return new Inventory(
        r.getProductId(), r.getStockQty(), r.getReservedQty(), r.getVersion(), r.getUpdatedAt());
  }
}
