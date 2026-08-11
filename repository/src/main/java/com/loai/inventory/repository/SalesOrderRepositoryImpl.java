package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORDER_NUMBER_COUNTER;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;

import com.loai.inventory.common.text.Phone;
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
  public Map<UUID, String> findOrderNumbersByIds(UUID orgId, Collection<UUID> salesOrderIds) {
    if (salesOrderIds.isEmpty()) {
      return Map.of();
    }
    return dsl.select(SALES_ORDER.ID, SALES_ORDER.ORDER_NUMBER)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.ID.in(salesOrderIds)))
        .fetchMap(SALES_ORDER.ID, SALES_ORDER.ORDER_NUMBER);
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
  public List<SalesOrder> list(UUID orgId, OrderStatus status, int offset, int limit) {
    var query = dsl.selectFrom(SALES_ORDER).where(listConditions(orgId, status));
    // Queue vs ledger: a status filter is a worklist — oldest first; no filter is the ledger —
    // newest first (mirrors the payment/refund worklists).
    var ordered =
        status != null
            ? query.orderBy(SALES_ORDER.CREATED_AT.asc(), SALES_ORDER.ID.asc())
            : query.orderBy(SALES_ORDER.CREATED_AT.desc(), SALES_ORDER.ID.desc());
    return ordered.offset(offset).limit(limit).fetch().map(this::toSalesOrder);
  }

  @Override
  public long count(UUID orgId, OrderStatus status) {
    return dsl.fetchCount(dsl.selectFrom(SALES_ORDER).where(listConditions(orgId, status)));
  }

  @Override
  public List<SalesOrder> findByCustomerId(UUID orgId, UUID customerId, int offset, int limit) {
    return dsl.selectFrom(SALES_ORDER)
        .where(SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.CUSTOMER_ID.eq(customerId)))
        .orderBy(SALES_ORDER.PLACED_AT.desc(), SALES_ORDER.ID.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toSalesOrder);
  }

  @Override
  public long countByCustomerId(UUID orgId, UUID customerId) {
    return dsl.fetchCount(
        dsl.selectFrom(SALES_ORDER)
            .where(SALES_ORDER.ORG_ID.eq(orgId).and(SALES_ORDER.CUSTOMER_ID.eq(customerId))));
  }

  private static org.jooq.Condition listConditions(UUID orgId, OrderStatus status) {
    org.jooq.Condition c = SALES_ORDER.ORG_ID.eq(orgId);
    if (status != null) {
      c =
          c.and(
              SALES_ORDER.STATUS.eq(
                  com.loai.inventory.repository.generated.enums.OrderStatus.valueOf(
                      status.name())));
    }
    return c;
  }

  @Override
  public Map<UUID, List<SalesOrderLine>> findLinesByOrderIds(Collection<UUID> salesOrderIds) {
    if (salesOrderIds == null || salesOrderIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<SalesOrderLine>> byOrder = new HashMap<>();
    dsl.selectFrom(SALES_ORDER_LINE)
        .where(SALES_ORDER_LINE.SALES_ORDER_ID.in(salesOrderIds))
        .fetch()
        .forEach(
            r ->
                byOrder
                    .computeIfAbsent(r.getSalesOrderId(), k -> new ArrayList<>())
                    .add(toSalesOrderLine(r)));
    return byOrder;
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
        .map(SalesOrderRepositoryImpl::toCustomer);
  }

  @Override
  public Customer upsertCustomerByEmail(
      UUID orgId, String email, String name, String phone, String address, String locale) {
    CustomerRecord record =
        dsl.insertInto(CUSTOMER)
            .columns(
                CUSTOMER.ORG_ID,
                CUSTOMER.EMAIL,
                CUSTOMER.NAME,
                CUSTOMER.PHONE,
                CUSTOMER.PHONE_E164,
                CUSTOMER.ADDRESS,
                CUSTOMER.LOCALE)
            .values(orgId, email, name, phone, Phone.toE164(phone), address, locale)
            .onConflict(CUSTOMER.ORG_ID, CUSTOMER.EMAIL)
            .doUpdate()
            .set(CUSTOMER.NAME, DSL.coalesce(excluded(CUSTOMER.NAME), CUSTOMER.NAME))
            .set(CUSTOMER.PHONE, DSL.coalesce(excluded(CUSTOMER.PHONE), CUSTOMER.PHONE))
            // The E.164 twin follows `phone`'s fate exactly — it cannot use the same COALESCE.
            // COALESCE would keep the OLD e164 whenever the incoming phone is present but
            // unparseable, leaving the pair describing two different people: `phone` the number
            // just typed, `phone_e164` the previous one, which is the number a message would
            // actually be sent to. So: phone replaced ⇒ e164 replaced, null and all.
            .set(
                CUSTOMER.PHONE_E164,
                DSL.when(excluded(CUSTOMER.PHONE).isNotNull(), excluded(CUSTOMER.PHONE_E164))
                    .otherwise(CUSTOMER.PHONE_E164))
            .set(CUSTOMER.ADDRESS, DSL.coalesce(excluded(CUSTOMER.ADDRESS), CUSTOMER.ADDRESS))
            // Fill-once, and note the argument order is REVERSED from its neighbours: the STORED
            // value wins. A checkout locale is implicit (whichever link they opened); the portal
            // profile is explicit. One English checkout must not permanently flip an Arabic
            // speaker's language.
            .set(CUSTOMER.LOCALE, DSL.coalesce(CUSTOMER.LOCALE, excluded(CUSTOMER.LOCALE)))
            .set(CUSTOMER.UPDATED_AT, DSL.currentOffsetDateTime())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("upsertCustomerByEmail returned no row");
    }
    return toCustomer(record);
  }

  /**
   * Row → domain, carrying the derived {@code phone_e164} the constructor does not take (it is set
   * from the record, never recomputed here — the column is the source of truth once written).
   */
  private static Customer toCustomer(CustomerRecord record) {
    Customer customer =
        new Customer(
            record.getId(),
            record.getOrgId(),
            record.getEmail(),
            record.getName(),
            record.getPhone(),
            record.getAddress(),
            record.getCreatedAt(),
            record.getUpdatedAt());
    customer.setPhoneE164(record.getPhoneE164());
    return customer;
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
        .set(SALES_ORDER.SHIPPING_TOTAL, order.getShippingTotal())
        .set(SALES_ORDER.DISCOUNT_TOTAL, order.getDiscountTotal())
        .set(SALES_ORDER.GRAND_TOTAL, order.getGrandTotal())
        .set(SALES_ORDER.CURRENCY, order.getCurrency())
        .set(SALES_ORDER.PREPAID_AMOUNT, order.getPrepaidAmount())
        .set(SALES_ORDER.IDEMPOTENCY_KEY, order.getIdempotencyKey())
        .set(SALES_ORDER.PLACED_AT, order.getPlacedAt())
        .set(SALES_ORDER.EXPIRES_AT, order.getExpiresAt())
        .set(SALES_ORDER.NOTES, order.getNotes())
        // Roadmap item 9: the redeemed coupon + its frozen display code (both null without one).
        .set(SALES_ORDER.COUPON_ID, order.getCouponId())
        .set(SALES_ORDER.COUPON_CODE, order.getCouponCode())
        // V80: the per-order delivery contact, frozen here and never updated afterwards.
        .set(SALES_ORDER.DELIVERY_RECIPIENT, order.getDeliveryRecipient())
        .set(SALES_ORDER.DELIVERY_PHONE, order.getDeliveryPhone())
        .set(SALES_ORDER.DELIVERY_ADDRESS, order.getDeliveryAddress())
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
    SalesOrder order = rehydrateBase(r);
    // The coupon snapshot is plain frozen data, not state-machine state, so it rides as fields
    // rather than widening the already 22-parameter rehydrate signature.
    order.setCouponId(r.getCouponId());
    order.setCouponCode(r.getCouponCode());
    order.setDeliveryContact(
        r.getDeliveryRecipient(), r.getDeliveryPhone(), r.getDeliveryAddress());
    return order;
  }

  private static SalesOrder rehydrateBase(SalesOrderRecord r) {
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
        r.getShippingTotal(),
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
