package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.InvoiceListSummaryResponse;
import com.loai.inventory.api.dto.InvoiceResponse;
import com.loai.inventory.api.dto.InvoiceStatusCountsResponse;
import com.loai.inventory.api.dto.InvoiceSummaryResponse;
import com.loai.inventory.api.dto.OrderInvoicesResponse;
import com.loai.inventory.api.dto.ReissueInvoiceRequest;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.InvoiceListFilter;
import com.loai.inventory.domain.model.InvoiceListStats;
import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.service.InvoiceAdminService.InvoiceStatusCounts;
import com.loai.inventory.service.InvoiceAdminService.InvoiceSummary;
import com.loai.inventory.service.InvoiceAdminService.InvoiceView;
import com.loai.inventory.service.InvoiceAdminService.OrderInvoices;
import com.loai.inventory.service.InvoiceAdminService.ReissueLine;
import com.loai.inventory.service.InvoiceService.Issued;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/** Maps invoice DTOs ↔ service inputs and domain → response. Lives in api/. */
public final class InvoiceMapper {

  private InvoiceMapper() {}

  public static List<ReissueLine> toReissueLines(ReissueInvoiceRequest req) {
    if (req == null) {
      throw new ValidationException("request body is required");
    }
    if (req.getLines() == null || req.getLines().isEmpty()) {
      throw new ValidationException("at least one line is required");
    }
    return req.getLines().stream()
        .map(
            l ->
                new ReissueLine(
                    l.getProductId(),
                    l.getDescription(),
                    l.getQuantity() == null ? 0 : l.getQuantity(),
                    l.getUnitPrice(),
                    l.getTaxRate() == null ? BigDecimal.ZERO : l.getTaxRate()))
        .toList();
  }

  public static InvoiceResponse toResponse(InvoiceView view) {
    return InvoiceResponse.from(view.invoice(), view.lines());
  }

  public static InvoiceResponse toResponse(Issued issued) {
    return InvoiceResponse.from(issued.invoice(), issued.lines());
  }

  public static InvoiceSummaryResponse toSummaryResponse(InvoiceSummary summary) {
    return InvoiceSummaryResponse.from(summary);
  }

  public static OrderInvoicesResponse toOrderInvoicesResponse(OrderInvoices result) {
    return OrderInvoicesResponse.from(
        result.order(), result.invoices().stream().map(InvoiceMapper::toResponse).toList());
  }

  /** Parse the optional {@code status} list filter; blank → null (no filter), unknown → 400. */
  public static InvoiceStatus toStatusFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return InvoiceStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown status: " + raw);
    }
  }

  /**
   * The whole {@code GET /invoices} query string as one {@link InvoiceListFilter} ({@code
   * stories/invoice_filters.md}). Every parameter is optional; blank is absent. What can 400: an
   * unknown {@code status} or {@code paid}; a {@code from}/{@code to} that is not an ISO-8601
   * date-time (the {@code /reports} convention) or a window with {@code from >= to}; a {@code
   * min}/{@code max} that is not a number, is negative, or has {@code min > max}. {@code q} cannot
   * fail — any text is a valid search.
   */
  public static InvoiceListFilter toListFilter(
      String status, String q, String from, String to, String paid, String min, String max) {
    OffsetDateTime issuedFrom = QueryParams.parseTs("from", from);
    OffsetDateTime issuedTo = QueryParams.parseTs("to", to);
    if (issuedFrom != null && issuedTo != null && !issuedFrom.isBefore(issuedTo)) {
      throw new ValidationException("'from' must be strictly before 'to'");
    }
    BigDecimal minTotal = QueryParams.parseMoney("min", min);
    BigDecimal maxTotal = QueryParams.parseMoney("max", max);
    if (minTotal != null && maxTotal != null && minTotal.compareTo(maxTotal) > 0) {
      throw new ValidationException("'min' must not exceed 'max'");
    }
    return new InvoiceListFilter(
        toStatusFilter(status),
        q == null || q.isBlank() ? null : q.trim(),
        issuedFrom,
        issuedTo,
        toPaidState(paid),
        minTotal,
        maxTotal);
  }

  /** {@code ?paid=none|partial}; blank → null, anything else → 400. */
  public static InvoiceListFilter.PaidState toPaidState(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return InvoiceListFilter.PaidState.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException("'paid' must be one of: none, partial");
    }
  }

  public static InvoiceListSummaryResponse toSummaryResponse(InvoiceListStats stats) {
    return new InvoiceListSummaryResponse(stats.outstanding(), stats.issued());
  }

  public static InvoiceStatusCountsResponse toStatusCountsResponse(InvoiceStatusCounts counts) {
    return new InvoiceStatusCountsResponse(counts.counts(), counts.total());
  }
}
