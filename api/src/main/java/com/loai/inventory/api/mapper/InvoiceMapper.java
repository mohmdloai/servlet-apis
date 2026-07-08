package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.InvoiceResponse;
import com.loai.inventory.api.dto.InvoiceSummaryResponse;
import com.loai.inventory.api.dto.OrderInvoicesResponse;
import com.loai.inventory.api.dto.ReissueInvoiceRequest;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.service.InvoiceAdminService.InvoiceSummary;
import com.loai.inventory.service.InvoiceAdminService.InvoiceView;
import com.loai.inventory.service.InvoiceAdminService.OrderInvoices;
import com.loai.inventory.service.InvoiceAdminService.ReissueLine;
import com.loai.inventory.service.InvoiceService.Issued;
import java.math.BigDecimal;
import java.util.List;

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
}
