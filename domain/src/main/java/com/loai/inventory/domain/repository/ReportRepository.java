package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.report.AgingBand;
import com.loai.inventory.domain.model.report.InventoryValuation;
import com.loai.inventory.domain.model.report.ProfitTotals;
import com.loai.inventory.domain.model.report.RevenuePoint;
import com.loai.inventory.domain.model.report.SalesPoint;
import com.loai.inventory.domain.model.report.TopProduct;
import com.loai.inventory.domain.model.report.TopProductSort;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Org-scoped aggregate reads for the tenant dashboard ({@code stories/reporting_reads.md}). Every
 * method filters {@code org_id = orgId} on its driving table; none returns raw rows a client would
 * otherwise page and sum. Parameter validation (window bounds, enum allowlists, band edges) is the
 * {@code ReportService}'s job — implementations trust their inputs.
 *
 * <p>Windows are half-open {@code [from, to)}; time buckets are {@code date_trunc(bucket, ts,
 * 'UTC')} and the returned series is <em>sparse</em> (empty buckets omitted), ordered {@code period
 * ASC}.
 */
public interface ReportRepository {

  /** Per-bucket invoiced / collected / refunded over the three money ledgers, merged on period. */
  List<RevenuePoint> revenue(UUID orgId, String bucket, OffsetDateTime from, OffsetDateTime to);

  /**
   * Per-{@code (bucket, channel)} order count + gross over money-committed orders.
   *
   * @param channel optional exact {@code order_channel} name; {@code null} returns every channel
   */
  List<SalesPoint> sales(
      UUID orgId, String bucket, OffsetDateTime from, OffsetDateTime to, String channel);

  /**
   * Top products by revenue, quantity or gross profit over money-committed orders; ties broken
   * {@code product_id ASC}. The cost figures on every row are always computed (cheap {@code FILTER}
   * sums on a query that runs anyway) — the handler decides whether they cross the wire.
   */
  List<TopProduct> topProducts(
      UUID orgId, OffsetDateTime from, OffsetDateTime to, TopProductSort sort, int limit);

  /**
   * The window's totals over the same lines and expressions as {@link #topProducts}: units sold,
   * costed units, and the costed lines' net sales / cost / gross profit
   * (stories/product_cost_and_margin.md).
   */
  ProfitTotals profit(UUID orgId, OffsetDateTime from, OffsetDateTime to);

  /**
   * Outstanding-invoice money bucketed by age. Returns every band (zero-filled), ordered youngest
   * first, then a synthetic {@code "{lastEdge}+"} overflow band.
   *
   * @param edges ascending positive day-edges (e.g. {@code [30, 60, 90]}); {@code edges.length + 1}
   *     bands result
   * @param asOf the reference instant for the age computation
   */
  List<AgingBand> arAging(UUID orgId, int[] edges, OffsetDateTime asOf);

  /** Point-in-time stock units + retail value over the org's tracked products. */
  InventoryValuation inventoryValuation(UUID orgId);
}
