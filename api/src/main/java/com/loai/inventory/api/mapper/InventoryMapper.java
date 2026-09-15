package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.InventoryListSummaryResponse;
import com.loai.inventory.api.dto.InventoryLogRow;
import com.loai.inventory.api.dto.InventoryOverviewRow;
import com.loai.inventory.api.dto.InventoryStockCountsResponse;
import com.loai.inventory.api.dto.OrderReservationsResponse;
import com.loai.inventory.api.dto.ProductReservationsResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.InventoryListFilter;
import com.loai.inventory.domain.model.InventoryListStats;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.InventoryStockCounts;
import com.loai.inventory.domain.model.InventoryStockFilter;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.service.InventoryService.LogPage;
import com.loai.inventory.service.InventoryService.OrderReservations;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Maps inventory read query params + domain reads → response DTOs. Lives in api/. */
public final class InventoryMapper {

  private InventoryMapper() {}

  /**
   * Parse the {@code stock} overview filter. Blank/absent ⇒ null (no filter); an unknown value is a
   * 400 (fail loudly, per house convention), never a silent all-rows fallthrough.
   */
  public static InventoryStockFilter parseStockFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return InventoryStockFilter.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException(
          "Unknown stock filter: " + raw + " (expected out|low|reorder|tracked|untracked)");
    }
  }

  /**
   * The whole {@code GET /inventory} query string as one {@link InventoryListFilter} ({@code
   * stories/inventory_filters.md}). Every parameter is optional; blank is absent. What can 400: an
   * unknown {@code stock}, {@code held}, {@code rule} or {@code sort}; a {@code category} that is
   * not a UUID; a {@code changed_from}/{@code changed_to} that is not an ISO-8601 date-time (the
   * {@code /reports} convention) or a window with {@code from >= to}; a negative or non-integer
   * {@code low_lte} (parsed by the caller). {@code q} cannot fail — any text is a valid search.
   */
  public static InventoryListFilter toListFilter(
      String q,
      String stock,
      Integer lowLte,
      String category,
      String held,
      String rule,
      String changedFrom,
      String changedTo,
      String sort) {
    OffsetDateTime from = QueryParams.parseTs("changed_from", changedFrom);
    OffsetDateTime to = QueryParams.parseTs("changed_to", changedTo);
    if (from != null && to != null && !from.isBefore(to)) {
      throw new ValidationException("'changed_from' must be strictly before 'changed_to'");
    }
    return new InventoryListFilter(
        q == null || q.isBlank() ? null : q.trim(),
        parseStockFilter(stock),
        lowLte,
        parseUuid("category", category),
        parseBoolean("held", held),
        QueryParams.parseEnum("rule", rule, InventoryListFilter.ReorderRule.class, "set, none"),
        from,
        to,
        QueryParams.parseEnum(
            "sort", sort, InventoryListFilter.Sort.class, "name, available, on_hand, updated"));
  }

  private static UUID parseUuid(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("'" + name + "' must be a UUID");
    }
  }

  private static Boolean parseBoolean(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "true" -> Boolean.TRUE;
      case "false" -> Boolean.FALSE;
      default -> throw new ValidationException("'" + name + "' must be true or false");
    };
  }

  /** The envelope summary; {@code cost_value} crosses only with manager authority. */
  public static InventoryListSummaryResponse toSummaryResponse(
      InventoryListStats stats, boolean costVisible) {
    return new InventoryListSummaryResponse(
        stats.products(),
        stats.unitsOnHand(),
        stats.unitsAvailable(),
        stats.costedProducts(),
        costVisible ? stats.costValue() : null);
  }

  public static InventoryStockCountsResponse toStockCountsResponse(InventoryStockCounts c) {
    return new InventoryStockCountsResponse(c.all(), c.low(), c.reorder(), c.out(), c.untracked());
  }

  /**
   * Parse the per-product reservation {@code status} filter. Blank/absent ⇒ {@code ACTIVE} (the
   * "what is holding this stock right now" default); an unknown value is a 400.
   */
  public static ReservationStatus parseReservationStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      return ReservationStatus.ACTIVE;
    }
    try {
      return ReservationStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException(
          "Unknown reservation status: " + raw + " (expected ACTIVE|CONSUMED|RELEASED)");
    }
  }

  public static List<InventoryOverviewRow> toOverviewRows(
      List<InventoryRepository.OverviewRow> rows) {
    return rows.stream().map(InventoryOverviewRow::from).toList();
  }

  /**
   * As {@link #toOverviewRows(List)}, attaching each product's presigned thumbnail URL (or null).
   */
  public static List<InventoryOverviewRow> toOverviewRows(
      List<InventoryRepository.OverviewRow> rows, java.util.Map<java.util.UUID, String> imageUrls) {
    return toOverviewRows(rows, imageUrls, false);
  }

  /** As above, writing each row's {@code cost_price} iff the caller has manager authority. */
  public static List<InventoryOverviewRow> toOverviewRows(
      List<InventoryRepository.OverviewRow> rows,
      java.util.Map<java.util.UUID, String> imageUrls,
      boolean costVisible) {
    return rows.stream()
        .map(row -> InventoryOverviewRow.from(row, imageUrls.get(row.productId()), costVisible))
        .toList();
  }

  public static List<InventoryLogRow> toLogRows(LogPage page) {
    return page.logs().stream()
        .map(l -> InventoryLogRow.from(l, orderNumber(page, l), receiptNumber(page, l)))
        .toList();
  }

  private static String orderNumber(LogPage page, InventoryLog log) {
    return log.getOrderId() == null ? null : page.orderNumbers().get(log.getOrderId());
  }

  private static String receiptNumber(LogPage page, InventoryLog log) {
    return log.getGoodsReceiptId() == null
        ? null
        : page.receiptNumbers().get(log.getGoodsReceiptId());
  }

  public static OrderReservationsResponse toOrderReservationsResponse(OrderReservations r) {
    return OrderReservationsResponse.from(r.order(), r.reservations(), r.productNames());
  }

  public static ProductReservationsResponse toProductReservationsResponse(
      List<
              com.loai.inventory.domain.repository.InventoryReservationRepository
                  .ProductReservationRow>
          rows) {
    return ProductReservationsResponse.from(rows);
  }
}
