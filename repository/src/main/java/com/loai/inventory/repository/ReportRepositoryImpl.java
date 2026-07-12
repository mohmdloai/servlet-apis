package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;

import com.loai.inventory.domain.model.report.AgingBand;
import com.loai.inventory.domain.model.report.InventoryValuation;
import com.loai.inventory.domain.model.report.RevenuePoint;
import com.loai.inventory.domain.model.report.SalesPoint;
import com.loai.inventory.domain.model.report.TopProduct;
import com.loai.inventory.domain.repository.ReportRepository;
import com.loai.inventory.repository.generated.enums.InvoiceStatus;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.RefundStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.CaseConditionStep;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/**
 * Postgres/jOOQ implementation of the dashboard aggregates ({@code stories/reporting_reads.md}).
 *
 * <p>Time buckets use Postgres 14+'s three-arg {@code date_trunc(field, ts, 'UTC')} so bucket edges
 * are deterministic UTC regardless of the server session timezone. The {@code bucket} argument is
 * an allowlisted literal ({@code day|week|month}, validated in {@code ReportService}) bound as a
 * value. Only money-committed orders (PAID/FULFILLING/FULFILLED/CLOSED) count as sales.
 */
public final class ReportRepositoryImpl implements ReportRepository {

  /**
   * Orders that count as a "sale" — money committed. See {@code stories/reporting_reads.md} §G2.
   */
  private static final OrderStatus[] SALE_STATUSES = {
    OrderStatus.PAID, OrderStatus.FULFILLING, OrderStatus.FULFILLED, OrderStatus.CLOSED
  };

  private final DSLContext dsl;

  public ReportRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  // ── G1 · revenue ────────────────────────────────────────────────────────────

  @Override
  public List<RevenuePoint> revenue(
      UUID orgId, String bucket, OffsetDateTime from, OffsetDateTime to) {
    Map<OffsetDateTime, BigDecimal> invoiced =
        sumByPeriod(
            bucket,
            SALES_INVOICE.ISSUED_AT,
            SALES_INVOICE.GRAND_TOTAL,
            SALES_INVOICE,
            SALES_INVOICE
                .ORG_ID
                .eq(orgId)
                .and(SALES_INVOICE.STATUS.in(InvoiceStatus.ISSUED, InvoiceStatus.PAID))
                .and(window(SALES_INVOICE.ISSUED_AT, from, to)));
    Map<OffsetDateTime, BigDecimal> collected =
        sumByPeriod(
            bucket,
            PAYMENT.RECEIVED_AT,
            PAYMENT.AMOUNT,
            PAYMENT,
            PAYMENT.ORG_ID.eq(orgId).and(window(PAYMENT.RECEIVED_AT, from, to)));
    Map<OffsetDateTime, BigDecimal> refunded =
        sumByPeriod(
            bucket,
            REFUND.EXECUTED_AT,
            REFUND.AMOUNT,
            REFUND,
            REFUND
                .ORG_ID
                .eq(orgId)
                .and(REFUND.STATUS.eq(RefundStatus.EXECUTED))
                .and(window(REFUND.EXECUTED_AT, from, to)));

    // Sparse union: a bucket present in ANY of the three columns is a row; missing columns are 0.
    // TreeMap keeps period ASC without a re-sort.
    TreeMap<OffsetDateTime, RevenuePoint> merged = new TreeMap<>();
    List<Map<OffsetDateTime, BigDecimal>> all = List.of(invoiced, collected, refunded);
    for (Map<OffsetDateTime, BigDecimal> m : all) {
      for (OffsetDateTime period : m.keySet()) {
        merged.computeIfAbsent(
            period,
            p ->
                new RevenuePoint(
                    p,
                    invoiced.getOrDefault(p, BigDecimal.ZERO),
                    collected.getOrDefault(p, BigDecimal.ZERO),
                    refunded.getOrDefault(p, BigDecimal.ZERO)));
      }
    }
    return new ArrayList<>(merged.values());
  }

  private Map<OffsetDateTime, BigDecimal> sumByPeriod(
      String bucket,
      Field<OffsetDateTime> ts,
      Field<BigDecimal> amount,
      org.jooq.Table<?> table,
      Condition where) {
    // Group by the output alias, not the trunc expression: Postgres won't match a parameterized
    // date_trunc(?, ts, ?) in GROUP BY against the identical SELECT expression, but it does support
    // grouping by an output column name.
    return dsl.select(truncField(bucket, ts).as("period"), DSL.sum(amount).as("total"))
        .from(table)
        .where(where)
        .groupBy(DSL.field(DSL.name("period")))
        .fetchMap(
            r -> utc(r.get("period", OffsetDateTime.class)), r -> r.get("total", BigDecimal.class));
  }

  // ── G2 · sales by channel ──────────────────────────────────────────────────

  @Override
  public List<SalesPoint> sales(
      UUID orgId, String bucket, OffsetDateTime from, OffsetDateTime to, String channel) {
    Field<OffsetDateTime> saleTs = DSL.coalesce(SALES_ORDER.PLACED_AT, SALES_ORDER.CREATED_AT);
    // See sumByPeriod: group/order by the output alias, not the parameterized trunc expression.
    Field<Object> periodRef = DSL.field(DSL.name("period"));

    Condition where =
        SALES_ORDER
            .ORG_ID
            .eq(orgId)
            .and(SALES_ORDER.STATUS.in(SALE_STATUSES))
            .and(window(saleTs, from, to));
    if (channel != null) {
      where = where.and(SALES_ORDER.CHANNEL.eq(OrderChannel.valueOf(channel)));
    }

    return dsl.select(
            truncField(bucket, saleTs).as("period"),
            SALES_ORDER.CHANNEL,
            DSL.count().as("orders"),
            DSL.coalesce(DSL.sum(SALES_ORDER.GRAND_TOTAL), BigDecimal.ZERO).as("gross"))
        .from(SALES_ORDER)
        .where(where)
        .groupBy(periodRef, SALES_ORDER.CHANNEL)
        // Order channels by NAME, not the enum's physical declaration order (Postgres sorts enums
        // by
        // definition order: ONLINE < IN_STORE < PHONE), so "channel ASC" reads as alphabetical.
        .orderBy(periodRef.asc(), SALES_ORDER.CHANNEL.cast(SQLDataType.VARCHAR).asc())
        .fetch(
            r ->
                new SalesPoint(
                    utc(r.get("period", OffsetDateTime.class)),
                    r.get(SALES_ORDER.CHANNEL).name(),
                    r.get("orders", Long.class),
                    r.get("gross", BigDecimal.class)));
  }

  // ── G3 · top products ──────────────────────────────────────────────────────

  @Override
  public List<TopProduct> topProducts(
      UUID orgId, OffsetDateTime from, OffsetDateTime to, boolean byRevenue, int limit) {
    Field<OffsetDateTime> saleTs = DSL.coalesce(SALES_ORDER.PLACED_AT, SALES_ORDER.CREATED_AT);
    Field<BigDecimal> quantity = DSL.sum(SALES_ORDER_LINE.QUANTITY).as("quantity");
    Field<BigDecimal> revenue = DSL.sum(SALES_ORDER_LINE.LINE_TOTAL).as("revenue");

    return dsl.select(PRODUCT.ID, PRODUCT.NAME, PRODUCT.SKU, quantity, revenue)
        .from(SALES_ORDER_LINE)
        .join(SALES_ORDER)
        .on(SALES_ORDER_LINE.SALES_ORDER_ID.eq(SALES_ORDER.ID))
        .join(PRODUCT)
        .on(SALES_ORDER_LINE.PRODUCT_ID.eq(PRODUCT.ID))
        .where(
            SALES_ORDER
                .ORG_ID
                .eq(orgId)
                .and(SALES_ORDER.STATUS.in(SALE_STATUSES))
                .and(window(saleTs, from, to)))
        .groupBy(PRODUCT.ID, PRODUCT.NAME, PRODUCT.SKU)
        // Tie-break on product_id so the capped top-N is deterministic without pagination.
        .orderBy(byRevenue ? revenue.desc() : quantity.desc(), PRODUCT.ID.asc())
        .limit(limit)
        .fetch(
            r ->
                new TopProduct(
                    r.get(PRODUCT.ID),
                    r.get(PRODUCT.NAME),
                    r.get(PRODUCT.SKU),
                    r.get(quantity).longValueExact(),
                    r.get(revenue)));
  }

  // ── G4 · AR aging ──────────────────────────────────────────────────────────

  @Override
  public List<AgingBand> arAging(UUID orgId, int[] edges, OffsetDateTime asOf) {
    Field<BigDecimal> outstanding = SALES_INVOICE.GRAND_TOTAL.minus(SALES_INVOICE.PAID_AMOUNT);
    // Whole days elapsed since issuance, as of `asOf`. Floor so day-labeled bands compare integer
    // days — an invoice issued exactly N days ago reads as N (not N+epsilon) and lands in the ≤N
    // band, matching the "0-30"/"31-60" labels.
    Field<BigDecimal> ageDays =
        DSL.field(
            "floor(extract(epoch from ({0} - {1})) / 86400)",
            BigDecimal.class, DSL.val(asOf), SALES_INVOICE.ISSUED_AT);

    // First matching arm wins: band 0 = age ≤ edges[0], band i = age ≤ edges[i], overflow = else.
    CaseConditionStep<Integer> arms =
        DSL.when(ageDays.le(BigDecimal.valueOf(edges[0])), DSL.inline(0));
    for (int i = 1; i < edges.length; i++) {
      arms = arms.when(ageDays.le(BigDecimal.valueOf(edges[i])), DSL.inline(i));
    }
    Field<Integer> bandExpr = arms.otherwise(DSL.inline(edges.length));

    // The partial index idx_invoice_unpaid (org_id, issued_at) WHERE status='ISSUED' AND
    // paid_amount < grand_total covers exactly this predicate.
    // Group by the output alias, not the CASE expression: it embeds a parameterized age term that
    // Postgres won't match between SELECT and GROUP BY (same reason as the trunc buckets above).
    Map<Integer, Record> byBand =
        dsl.select(
                bandExpr.as("band"),
                DSL.count().as("cnt"),
                DSL.coalesce(DSL.sum(outstanding), BigDecimal.ZERO).as("outstanding"))
            .from(SALES_INVOICE)
            .where(
                SALES_INVOICE
                    .ORG_ID
                    .eq(orgId)
                    .and(SALES_INVOICE.STATUS.eq(InvoiceStatus.ISSUED))
                    .and(SALES_INVOICE.PAID_AMOUNT.lt(SALES_INVOICE.GRAND_TOTAL)))
            .groupBy(DSL.field(DSL.name("band")))
            .fetchMap(r -> r.get("band", Integer.class), r -> r);

    // Zero-fill every band, ordered youngest → overflow.
    List<AgingBand> bands = new ArrayList<>(edges.length + 1);
    for (int i = 0; i <= edges.length; i++) {
      Record r = byBand.get(i);
      long count = r == null ? 0L : r.get("cnt", Long.class);
      BigDecimal out = r == null ? BigDecimal.ZERO : r.get("outstanding", BigDecimal.class);
      bands.add(new AgingBand(bandLabel(edges, i), count, out));
    }
    return bands;
  }

  private static String bandLabel(int[] edges, int i) {
    if (i == 0) {
      return "0-" + edges[0];
    }
    if (i == edges.length) {
      return edges[edges.length - 1] + "+";
    }
    return (edges[i - 1] + 1) + "-" + edges[i];
  }

  // ── G5 · inventory valuation ───────────────────────────────────────────────

  @Override
  public InventoryValuation inventoryValuation(UUID orgId) {
    // stock_qty * base_price kept as a raw fragment so the Integer×Numeric product stays NUMERIC
    // (jOOQ's typed .mul would coerce to the Integer LHS and truncate the money).
    Field<BigDecimal> retail =
        DSL.field("coalesce(sum(inventory.stock_qty * product.base_price), 0)", BigDecimal.class);
    Record r =
        dsl.select(
                DSL.count().as("tracked"),
                DSL.coalesce(DSL.sum(INVENTORY.STOCK_QTY), BigDecimal.ZERO).as("units"),
                retail.as("retail"),
                DSL.count().filterWhere(INVENTORY.STOCK_QTY.eq(0)).as("oos"))
            .from(INVENTORY)
            .join(PRODUCT)
            .on(INVENTORY.PRODUCT_ID.eq(PRODUCT.ID))
            .where(INVENTORY.ORG_ID.eq(orgId))
            .fetchOne();

    return new InventoryValuation(
        r.get("tracked", Long.class),
        r.get("units", BigDecimal.class).longValueExact(),
        r.get("retail", BigDecimal.class),
        r.get("oos", Long.class));
  }

  // ── shared helpers ─────────────────────────────────────────────────────────

  /** {@code date_trunc(bucket, ts, 'UTC')} — deterministic UTC bucket edges (Postgres 14+). */
  private static Field<OffsetDateTime> truncField(String bucket, Field<OffsetDateTime> ts) {
    return DSL.field(
        "date_trunc({0}, {1}, {2})", OffsetDateTime.class, DSL.val(bucket), ts, DSL.val("UTC"));
  }

  /**
   * Normalize a bucket boundary to a UTC offset. The value is already a UTC-midnight instant (the
   * trunc used {@code 'UTC'}), but the JDBC driver hands it back in the JVM zone — rendering the
   * <em>same instant</em> at, say, {@code +02:00}. Re-express it at {@code Z} so the API's periods
   * are consistently UTC, matching the story's contract.
   */
  private static OffsetDateTime utc(OffsetDateTime ts) {
    return ts.toInstant().atOffset(ZoneOffset.UTC);
  }

  /** Half-open window {@code [from, to)} on a timestamp field. */
  private static Condition window(
      Field<OffsetDateTime> ts, OffsetDateTime from, OffsetDateTime to) {
    return ts.ge(from).and(ts.lt(to));
  }
}
