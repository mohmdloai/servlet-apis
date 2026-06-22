package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.CreditNoteResponse;
import com.loai.inventory.api.dto.IssueCreditNoteRequest;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CreditNoteReason;
import com.loai.inventory.service.CreditNoteService.IssueCommand;
import com.loai.inventory.service.CreditNoteService.Issued;
import com.loai.inventory.service.CreditNoteService.LineSpec;
import java.math.BigDecimal;
import java.util.List;

/** Maps credit-note DTOs ↔ service inputs and domain → response. Lives in api/. */
public final class CreditNoteMapper {

  private CreditNoteMapper() {}

  public static IssueCommand toCommand(IssueCreditNoteRequest req) {
    if (req == null) {
      throw new ValidationException("request body is required");
    }
    if (req.getLines() == null || req.getLines().isEmpty()) {
      throw new ValidationException("at least one line is required");
    }
    List<LineSpec> lines =
        req.getLines().stream()
            .map(
                l ->
                    new LineSpec(
                        l.getProductId(),
                        l.getDescription(),
                        l.getQuantity() == null ? 0 : l.getQuantity(),
                        l.getUnitPrice(),
                        l.getTaxRate() == null ? BigDecimal.ZERO : l.getTaxRate()))
            .toList();
    return new IssueCommand(
        req.getSalesInvoiceId(), parseReason(req.getReason()), req.getReasonNote(), lines);
  }

  public static CreditNoteResponse toResponse(Issued issued) {
    return CreditNoteResponse.from(issued.creditNote(), issued.lines());
  }

  private static CreditNoteReason parseReason(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("reason is required");
    }
    try {
      return CreditNoteReason.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("unknown credit note reason: " + raw);
    }
  }
}
