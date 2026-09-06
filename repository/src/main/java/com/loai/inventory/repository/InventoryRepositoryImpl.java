package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING_CATEGORY;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_VARIANT;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryListFilter;
import com.loai.inventory.domain.model.InventoryListStats;
import com.loai.inventory.domain.model.InventoryStockCounts;
import com.loai.inventory.domain.model.InventoryStockFilter;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.repository.generated.tables.records.InventoryRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.jooq.SortField;
import org.jooq.impl.DSL;
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
      UUID orgId, InventoryListFilter filter, int offset, int limit) {
    return dsl.select(
            PRODUCT.ID,
            PRODUCT.NAME,
            PRODUCT.SKU,
            PRODUCT.BASE_PRICE,
            PRODUCT.COST_PRICE,
            PRODUCT.REORDER_POINT,
            INVENTORY.PRODUCT_ID,
            INVENTORY.STOCK_QTY,
            INVENTORY.RESERVED_QTY,
            INVENTORY.VERSION,
            INVENTORY.UPDATED_AT)
        .from(PRODUCT)
        .leftJoin(INVENTORY)
        .on(INVENTORY.PRODUCT_ID.eq(PRODUCT.ID).and(INVENTORY.ORG_ID.eq(PRODUCT.ORG_ID)))
        .where(overviewConditions(orgId, filter))
        .orderBy(overviewOrder(filter))
        .offset(offset)
        .limit(limit)
        .fetch(this::toOverviewRow);
  }

  @Override
  public long countOverview(UUID orgId, InventoryListFilter filter) {
    return dsl.fetchCount(
        dsl.selectOne()
            .from(PRODUCT)
            .leftJoin(INVENTORY)
            .on(INVENTORY.PRODUCT_ID.eq(PRODUCT.ID).and(INVENTORY.ORG_ID.eq(PRODUCT.ORG_ID)))
            .where(overviewConditions(orgId, filter)));
  }

  @Override
  public InventoryListStats statsOverview(UUID orgId, InventoryListFilter filter) {
    // One pass over the filtered set: the pager's total and the shelf figures. Untracked rows
    // contribute to the count only — their stock columns are NULL and SUM skips them. The cost
    // value sums costed rows only; costedProducts lets the reader see when that is a subset.
    Field<Integer> available = INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY);
    Field<BigDecimal> costOfRow =
        INVENTORY.STOCK_QTY.cast(BigDecimal.class).mul(PRODUCT.COST_PRICE);
    var row =
        dsl.select(
                DSL.count(),
                DSL.coalesce(DSL.sum(INVENTORY.STOCK_QTY), DSL.inline(0)),
                DSL.coalesce(DSL.sum(available), DSL.inline(0)),
                DSL.count()
                    .filterWhere(
                        INVENTORY.PRODUCT_ID.isNotNull().and(PRODUCT.COST_PRICE.isNotNull())),
                DSL.coalesce(DSL.sum(costOfRow), DSL.inline(BigDecimal.ZERO)))
            .from(PRODUCT)
            .leftJoin(INVENTORY)
            .on(INVENTORY.PRODUCT_ID.eq(PRODUCT.ID).and(INVENTORY.ORG_ID.eq(PRODUCT.ORG_ID)))
            .where(overviewConditions(orgId, filter))
            .fetchOne();
    if (row == null) {
      return InventoryListStats.empty();
    }
    return new InventoryListStats(
        row.value1().longValue(),
        row.value2().longValue(),
        row.value3().longValue(),
        row.value4().longValue(),
        row.value5().setScale(2, RoundingMode.HALF_EVEN));
  }

  @Override
  public InventoryStockCounts stockCounts(UUID orgId, int lowLte) {
    // The same LEFT JOIN as the list, partitioned with FILTER clauses that restate each
    // InventoryStockFilter arm of overviewConditions — a tab's chip and its list's total are the
    // same rows by construction. Org totals only: search and the sheet never narrow the tabs.
    Field<Integer> available = INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY);
    Condition tracked = INVENTORY.PRODUCT_ID.isNotNull();
    var row =
        dsl.select(
                DSL.count(),
                DSL.count().filterWhere(tracked.and(available.le(lowLte))),
                DSL.count()
                    .filterWhere(
                        tracked
                            .and(PRODUCT.REORDER_POINT.isNotNull())
                            .and(available.le(PRODUCT.REORDER_POINT))),
                DSL.count().filterWhere(tracked.and(available.eq(0))),
                DSL.count().filterWhere(INVENTORY.PRODUCT_ID.isNull()))
            .from(PRODUCT)
            .leftJoin(INVENTORY)
            .on(INVENTORY.PRODUCT_ID.eq(PRODUCT.ID).and(INVENTORY.ORG_ID.eq(PRODUCT.ORG_ID)))
            .where(PRODUCT.ORG_ID.eq(orgId))
            .fetchOne();
    if (row == null) {
      return new InventoryStockCounts(0, 0, 0, 0, 0);
    }
    return new InventoryStockCounts(
        row.value1(), row.value2(), row.value3(), row.value4(), row.value5());
  }

  /**
   * The list's order. An explicit {@code sort} wins; without one the catalog order applies — name
   * ASC — except on the REORDER tab, the one segment that is a queue: deepest below its own point
   * first, so the empty shelf tops a long list ({@code stories/reorder_point.md}). Every order
   * breaks ties on (name, id) so paging is stable; the untracked rows (NULL stock) go last on the
   * stock-driven sorts.
   */
  private static List<? extends SortField<?>> overviewOrder(InventoryListFilter f) {
    Field<Integer> available = INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY);
    if (f.sort() == null) {
      return f.stock() == InventoryStockFilter.REORDER
          ? List.of(
              available.minus(PRODUCT.REORDER_POINT).asc(), PRODUCT.NAME.asc(), PRODUCT.ID.asc())
          : List.of(PRODUCT.NAME.asc(), PRODUCT.ID.asc());
    }
    return switch (f.sort()) {
      case NAME -> List.of(PRODUCT.NAME.asc(), PRODUCT.ID.asc());
      case AVAILABLE -> List.of(available.asc().nullsLast(), PRODUCT.NAME.asc(), PRODUCT.ID.asc());
      case ON_HAND ->
          List.of(INVENTORY.STOCK_QTY.desc().nullsLast(), PRODUCT.NAME.asc(), PRODUCT.ID.asc());
      case UPDATED ->
          List.of(INVENTORY.UPDATED_AT.desc().nullsLast(), PRODUCT.NAME.asc(), PRODUCT.ID.asc());
    };
  }

  /**
   * The overview predicate — one definition for the rows, their total, the stock summary and (arm
   * by arm) the tab counts, so none of them can disagree ({@code stories/inventory_filters.md}).
   * Every dimension of the {@link InventoryListFilter} ANDs onto the org scope:
   *
   * <ul>
   *   <li><b>q</b> — OR of: {@code name ILIKE '%q%'}; the generated {@code name_search} key against
   *       the same {@code fold_search} of the term (so Arabic spelling variants match — the {@code
   *       ProductRepositoryImpl.searchCondition} precedent); {@code sku ILIKE '%q%'}; and {@code
   *       barcode = q} exactly, so a scanned code lands on its one product.
   *   <li><b>stock</b> — the tab, exactly as before; {@code available = stock - reserved}, and a
   *       NULL (untracked) comparison excludes the row, which is right for every arm but UNTRACKED.
   *   <li><b>category</b> — EXISTS a listing in the org whose category set holds the id and which
   *       is either the product's own listing or the listing a variant child is attached to.
   *   <li><b>held</b> — tracked and {@code reserved_qty > 0} / {@code = 0}.
   *   <li><b>rule</b> — {@code reorder_point IS NOT NULL} / {@code IS NULL}, product-side.
   *   <li><b>changed window</b> — tracked and half-open on {@code updated_at}: {@code >= from},
   *       {@code < to}.
   * </ul>
   */
  private static Condition overviewConditions(UUID orgId, InventoryListFilter f) {
    Condition c = PRODUCT.ORG_ID.eq(orgId);
    if (f.hasQuery()) {
      String term = f.q().trim();
      Field<String> foldedTerm = DSL.field("fold_search({0})", String.class, DSL.val(term));
      Condition arabicName =
          PRODUCT.NAME_SEARCH.like(DSL.concat(DSL.inline("%"), foldedTerm, DSL.inline("%")));
      c =
          c.and(
              PRODUCT
                  .NAME
                  .containsIgnoreCase(term)
                  .or(arabicName)
                  .or(PRODUCT.SKU.containsIgnoreCase(term))
                  .or(PRODUCT.BARCODE.eq(term)));
    }
    Field<Integer> available = INVENTORY.STOCK_QTY.minus(INVENTORY.RESERVED_QTY);
    Condition tracked = INVENTORY.PRODUCT_ID.isNotNull();
    if (f.stock() != null) {
      c =
          switch (f.stock()) {
            case TRACKED -> c.and(tracked);
            case UNTRACKED -> c.and(INVENTORY.PRODUCT_ID.isNull());
            case OUT -> c.and(tracked).and(available.eq(0));
            case LOW -> c.and(tracked).and(available.le(f.lowLte() != null ? f.lowLte() : 5));
            // The product's own rule (V94): a NULL point makes the comparison null → excluded.
            case REORDER ->
                c.and(tracked)
                    .and(PRODUCT.REORDER_POINT.isNotNull())
                    .and(available.le(PRODUCT.REORDER_POINT));
          };
    }
    if (f.categoryId() != null) {
      Condition ownListing = PRODUCT_LISTING.PRODUCT_ID.eq(PRODUCT.ID);
      Condition variantOfListing =
          DSL.exists(
              DSL.selectOne()
                  .from(PRODUCT_VARIANT)
                  .where(PRODUCT_VARIANT.PRODUCT_LISTING_ID.eq(PRODUCT_LISTING.ID))
                  .and(PRODUCT_VARIANT.PRODUCT_ID.eq(PRODUCT.ID)));
      c =
          c.and(
              DSL.exists(
                  DSL.selectOne()
                      .from(PRODUCT_LISTING)
                      .join(PRODUCT_LISTING_CATEGORY)
                      .on(PRODUCT_LISTING_CATEGORY.LISTING_ID.eq(PRODUCT_LISTING.ID))
                      .where(PRODUCT_LISTING.ORG_ID.eq(orgId))
                      .and(PRODUCT_LISTING_CATEGORY.CATEGORY_ID.eq(f.categoryId()))
                      .and(ownListing.or(variantOfListing))));
    }
    if (f.held() != null) {
      c =
          c.and(tracked)
              .and(f.held() ? INVENTORY.RESERVED_QTY.gt(0) : INVENTORY.RESERVED_QTY.eq(0));
    }
    if (f.rule() != null) {
      c =
          switch (f.rule()) {
            case SET -> c.and(PRODUCT.REORDER_POINT.isNotNull());
            case NONE -> c.and(PRODUCT.REORDER_POINT.isNull());
          };
    }
    if (f.changedFrom() != null) {
      c = c.and(tracked).and(INVENTORY.UPDATED_AT.ge(f.changedFrom()));
    }
    if (f.changedTo() != null) {
      c = c.and(tracked).and(INVENTORY.UPDATED_AT.lt(f.changedTo()));
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
          r.get(PRODUCT.COST_PRICE),
          r.get(PRODUCT.REORDER_POINT),
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
        r.get(PRODUCT.COST_PRICE),
        r.get(PRODUCT.REORDER_POINT),
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
