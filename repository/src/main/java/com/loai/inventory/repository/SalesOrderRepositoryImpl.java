package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORDER_NUMBER_COUNTER;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;

import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.repository.generated.tables.records.CustomerRecord;
import com.loai.inventory.repository.generated.tables.records.SalesOrderLineRecord;
import com.loai.inventory.repository.generated.tables.records.SalesOrderRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SalesOrderRepositoryImpl implements SalesOrderRepository {

  private static final Logger log = LoggerFactory.getLogger(SalesOrderRepositoryImpl.class);

  private final DSLContext dsl;

  public SalesOrderRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<SalesOrder> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(SALES_ORDER)
        .where(SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.ID.eq(id)))
        .fetchOptional()
        .map(this::toSalesOrder);
  }

  @Override
  public Optional<SalesOrder> findByIdForUpdate(UUID orgId, UUID id) {
    return dsl.selectFrom(SALES_ORDER)
        .where(SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.ID.eq(id)))
        .forUpdate()
        .fetchOptional()
        .map(this::toSalesOrder);
  }

  @Override
  public Optional<SalesOrder> findByOrderNumberForUpdate(UUID orgId, String orderNumber) {
    if (orderNumber == null || orderNumber.isBlank()) {
      return Optional.empty();
    }
    return dsl.selectFrom(SALES_ORDER)
        .where(SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.ORDER_NUMBER.eq(orderNumber)))
        .forUpdate()
        .fetchOptional()
        .map(this::toSalesOrder);
  }

  @Override
  public Optional<SalesOrder> findByOrderNumber(UUID orgId, String orderNumber) {
    if (orderNumber == null || orderNumber.isBlank()) {
      return Optional.empty();
    }
    return dsl.selectFrom(SALES_ORDER)
        .where(SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.ORDER_NUMBER.eq(orderNumber)))
        .fetchOptional()
        .map(this::toSalesOrder);
  }

  @Override
  public void updatePaymentState(SalesOrder order) {
    dsl.update(SALES_ORDER)
        .set(
            SALES_ORDER.STATUS,
            com.loai.inventory.repository.generated.enums.OrderStatus.valueOf(
                order.getStatus().name()))
        .set(SALES_ORDER.PREPAID_AMOUNT, order.getPrepaidAmount())
        .set(SALES_ORDER.UPDATED_AT, order.getUpdatedAt())
        .where(SALES_ORDER.ID.eq(order.getId()).and(SALES_ORDER.ORG_ID.eq(order.getOrgId())))
        .execute();
  }

  @Override
  public void updateFulfillmentState(SalesOrder order) {
    dsl.update(SALES_ORDER)
        .set(
            SALES_ORDER.STATUS,
            com.loai.inventory.repository.generated.enums.OrderStatus.valueOf(
                order.getStatus().name()))
        .set(SALES_ORDER.FULFILLED_AT, order.getFulfilledAt())
        .set(SALES_ORDER.CLOSED_AT, order.getClosedAt())
        .set(SALES_ORDER.UPDATED_AT, order.getUpdatedAt())
        .where(SALES_ORDER.ID.eq(order.getId()).and(SALES_ORDER.ORG_ID.eq(order.getOrgId())))
        .execute();
  }

  @Override
  public void updateCancelledState(SalesOrder order) {
    dsl.update(SALES_ORDER)
        .set(
            SALES_ORDER.STATUS,
            com.loai.inventory.repository.generated.enums.OrderStatus.valueOf(
                order.getStatus().name()))
        .set(SALES_ORDER.CANCELLED_AT, order.getCancelledAt())
        .set(SALES_ORDER.PREPAID_AMOUNT, order.getPrepaidAmount())
        .set(SALES_ORDER.UPDATED_AT, order.getUpdatedAt())
        .where(SALES_ORDER.ID.eq(order.getId()).and(SALES_ORDER.ORG_ID.eq(order.getOrgId())))
        .execute();
  }

  @Override
  public Optional<SalesOrder> findByIdempotencyKey(UUID orgId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      return Optional.empty();
    }
    return dsl.selectFrom(SALES_ORDER)
        .where(SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.IDEMPOTENCY_KEY.eq(idempotencyKey)))
        .fetchOptional()
        .map(this::toSalesOrder);
  }

  @Override
  public List<SalesOrderLine> findLinesByOrderId(UUID salesOrderId) {
    return dsl.selectFrom(SALES_ORDER_LINE)
        .where(SALES_ORDER_LINE.SALES_ORDER_ID.eq(salesOrderId))
        .fetch()
        .map(this::toSalesOrderLine);
  }

  @Override
  public Map<UUID, ProductSnapshot> fetchProductSnapshots(UUID orgId, Collection<UUID> productIds) {
    if (productIds == null || productIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, ProductSnapshot> result = new HashMap<>();
    dsl.select(PRODUCT.ID, PRODUCT.NAME, PRODUCT.BASE_PRICE)
        .from(PRODUCT)
        .where(PRODUCT.ORG_ID.eq(orgId).and(PRODUCT.ID.in(productIds)))
        .fetch()
        .forEach(
            r ->
                result.put(
                    r.get(PRODUCT.ID),
                    new ProductSnapshot(
                        r.get(PRODUCT.ID), r.get(PRODUCT.NAME), r.get(PRODUCT.BASE_PRICE))));
    return result;
  }

  @Override
  public long claimOrderNumber(UUID orgId, int year) {
    // INSERT … ON CONFLICT DO UPDATE … RETURNING next_val in one round-trip.
    // First call for (org, year): inserts (1) and seeds NEXT_VAL=2 — caller owns 1.
    // Subsequent calls: increment NEXT_VAL by 1 and return it — caller owns (NEXT_VAL-1).
    Long claimed =
        dsl.insertInto(ORDER_NUMBER_COUNTER)
            .columns(
                ORDER_NUMBER_COUNTER.ORG_ID,
                ORDER_NUMBER_COUNTER.YEAR,
                ORDER_NUMBER_COUNTER.NEXT_VAL)
            .values(orgId, year, 2L)
            .onConflict(ORDER_NUMBER_COUNTER.ORG_ID, ORDER_NUMBER_COUNTER.YEAR)
            .doUpdate()
            .set(ORDER_NUMBER_COUNTER.NEXT_VAL, ORDER_NUMBER_COUNTER.NEXT_VAL.plus(1))
            .returning(ORDER_NUMBER_COUNTER.NEXT_VAL)
            .fetchOne(ORDER_NUMBER_COUNTER.NEXT_VAL);
    if (claimed == null) {
      throw new IllegalStateException("claimOrderNumber returned no row");
    }
    return claimed - 1L;
  }

  @Override
  public Optional<Customer> findCustomerById(UUID orgId, UUID customerId) {
    if (customerId == null) return Optional.empty();
    return dsl.selectFrom(CUSTOMER)
        .where(CUSTOMER.ORG_ID.eq(orgId).and(CUSTOMER.ID.eq(customerId)))
        .fetchOptional()
        .map(
            r ->
                new Customer(
                    r.getId(),
                    r.getOrgId(),
                    r.getEmail(),
                    r.getName(),
                    r.getPhone(),
                    r.getAddress(),
                    r.getCreatedAt(),
                    r.getUpdatedAt()));
  }

  @Override
  public Customer upsertCustomerByEmail(
      UUID orgId, String email, String name, String phone, String address) {
    CustomerRecord record =
        dsl.insertInto(CUSTOMER)
            .columns(
                CUSTOMER.ORG_ID, CUSTOMER.EMAIL, CUSTOMER.NAME, CUSTOMER.PHONE, CUSTOMER.ADDRESS)
            .values(orgId, email, name, phone, address)
            .onConflict(CUSTOMER.ORG_ID, CUSTOMER.EMAIL)
            .doUpdate()
            .set(CUSTOMER.NAME, DSL.coalesce(excluded(CUSTOMER.NAME), CUSTOMER.NAME))
            .set(CUSTOMER.PHONE, DSL.coalesce(excluded(CUSTOMER.PHONE), CUSTOMER.PHONE))
            .set(CUSTOMER.ADDRESS, DSL.coalesce(excluded(CUSTOMER.ADDRESS), CUSTOMER.ADDRESS))
            .set(CUSTOMER.UPDATED_AT, DSL.currentOffsetDateTime())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("upsertCustomerByEmail returned no row");
    }
    return new Customer(
        record.getId(),
        record.getOrgId(),
        record.getEmail(),
        record.getName(),
        record.getPhone(),
        record.getAddress(),
        record.getCreatedAt(),
        record.getUpdatedAt());
  }

  @Override
  public void insert(SalesOrder order, List<SalesOrderLine> lines) {
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, order.getId())
        .set(SALES_ORDER.ORG_ID, order.getOrgId())
        .set(SALES_ORDER.CUSTOMER_ID, order.getCustomerId())
        .set(SALES_ORDER.ORDER_NUMBER, order.getOrderNumber())
        .set(
            SALES_ORDER.CHANNEL,
            com.loai.inventory.repository.generated.enums.OrderChannel.valueOf(
                order.getChannel().name()))
        .set(
            SALES_ORDER.STATUS,
            com.loai.inventory.repository.generated.enums.OrderStatus.valueOf(
                order.getStatus().name()))
        .set(SALES_ORDER.SUBTOTAL, order.getSubtotal())
        .set(SALES_ORDER.TAX_TOTAL, order.getTaxTotal())
        .set(SALES_ORDER.DISCOUNT_TOTAL, order.getDiscountTotal())
        .set(SALES_ORDER.GRAND_TOTAL, order.getGrandTotal())
        .set(SALES_ORDER.CURRENCY, order.getCurrency())
        .set(SALES_ORDER.PREPAID_AMOUNT, order.getPrepaidAmount())
        .set(SALES_ORDER.IDEMPOTENCY_KEY, order.getIdempotencyKey())
        .set(SALES_ORDER.PLACED_AT, order.getPlacedAt())
        .set(SALES_ORDER.EXPIRES_AT, order.getExpiresAt())
        .set(SALES_ORDER.NOTES, order.getNotes())
        .execute();

    if (!lines.isEmpty()) {
      List<SalesOrderLineRecord> records = new ArrayList<>(lines.size());
      for (SalesOrderLine line : lines) {
        SalesOrderLineRecord r = dsl.newRecord(SALES_ORDER_LINE);
        r.setId(line.getId());
        r.setSalesOrderId(line.getSalesOrderId());
        r.setProductId(line.getProductId());
        r.setDescription(line.getDescription());
        r.setQuantity(line.getQuantity());
        r.setUnitPrice(line.getUnitPrice());
        r.setTaxRate(line.getTaxRate());
        r.setLineSubtotal(line.getLineSubtotal());
        r.setLineTax(line.getLineTax());
        r.setLineTotal(line.getLineTotal());
        records.add(r);
      }
      dsl.batchInsert(records).execute();
    }

    log.debug(
        "Inserted sales_order id={} orgId={} orderNumber={} status={} lines={}",
        order.getId(),
        order.getOrgId(),
        order.getOrderNumber(),
        order.getStatus(),
        lines.size());
  }

  @Override
  public List<UUID> findExpiredPendingIds(int limit) {
    // IDs only; index-ordered scan on idx_so_pending_global (expires_at WHERE PENDING_PAYMENT).
    // Read-only — runs on the caller's autocommit connection, no surrounding transaction.
    return dsl.select(SALES_ORDER.ID)
        .from(SALES_ORDER)
        .where(
            SALES_ORDER
                .STATUS
                .eq(com.loai.inventory.repository.generated.enums.OrderStatus.PENDING_PAYMENT)
                .and(SALES_ORDER.EXPIRES_AT.lt(DSL.currentOffsetDateTime())))
        .orderBy(SALES_ORDER.EXPIRES_AT.asc())
        .limit(limit)
        .fetchInto(UUID.class);
  }

  @Override
  public int markExpiredIfPending(UUID orderId, OffsetDateTime now) {
    // The WHERE status='PENDING_PAYMENT' clause IS the concurrency guard — atomic, DB-enforced.
    // 0 rows ⇒ a sibling sweeper or a payment already moved the order off PENDING_PAYMENT.
    return dsl.update(SALES_ORDER)
        .set(SALES_ORDER.STATUS, com.loai.inventory.repository.generated.enums.OrderStatus.EXPIRED)
        .set(SALES_ORDER.EXPIRED_AT, now)
        .set(SALES_ORDER.UPDATED_AT, now)
        .where(
            SALES_ORDER
                .ID
                .eq(orderId)
                .and(
                    SALES_ORDER.STATUS.eq(
                        com.loai.inventory.repository.generated.enums.OrderStatus.PENDING_PAYMENT)))
        .execute();
  }

  // Mapping

  private SalesOrder toSalesOrder(SalesOrderRecord r) {
    return SalesOrder.rehydrate(
        r.getId(),
        r.getOrgId(),
        r.getCustomerId(),
        r.getOrderNumber(),
        OrderChannel.valueOf(r.getChannel().name()),
        r.getCurrency(),
        r.getIdempotencyKey(),
        r.getCreatedAt(),
        OrderStatus.valueOf(r.getStatus().name()),
        r.getSubtotal(),
        r.getTaxTotal(),
        r.getDiscountTotal(),
        r.getGrandTotal(),
        r.getPrepaidAmount(),
        r.getUpdatedAt(),
        r.getPlacedAt(),
        r.getExpiresAt(),
        r.getCancelledAt(),
        r.getFulfilledAt(),
        r.getClosedAt(),
        r.getExpiredAt(),
        r.getNotes());
  }

  private SalesOrderLine toSalesOrderLine(SalesOrderLineRecord r) {
    return SalesOrderLine.rehydrate(
        r.getId(),
        r.getSalesOrderId(),
        r.getProductId(),
        r.getDescription(),
        r.getQuantity(),
        r.getUnitPrice(),
        r.getTaxRate(),
        r.getLineSubtotal(),
        r.getLineTax(),
        r.getLineTotal());
  }

  /** Reference to {@code EXCLUDED.<col>} inside ON CONFLICT DO UPDATE (PostgreSQL syntax). */
  private static <T> Field<T> excluded(TableField<?, T> field) {
    return DSL.field("excluded." + field.getName(), field.getDataType());
  }
}
