package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.InventoryLogRow;
import com.loai.inventory.api.dto.InventoryOverviewRow;
import com.loai.inventory.api.dto.OrderReservationsResponse;
import com.loai.inventory.api.dto.ProductReservationsResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.InventoryStockFilter;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.service.InventoryService.LogPage;
import com.loai.inventory.service.InventoryService.OrderReservations;
import java.util.List;
import java.util.Locale;

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
          "Unknown stock filter: " + raw + " (expected out|low|tracked|untracked)");
    }
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
    return page.logs().stream().map(l -> InventoryLogRow.from(l, orderNumber(page, l))).toList();
  }

  private static String orderNumber(LogPage page, InventoryLog log) {
    return log.getOrderId() == null ? null : page.orderNumbers().get(log.getOrderId());
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
