package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.CreateRefundRequest;
import com.loai.inventory.api.dto.RefundResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.RefundStatus;
import com.loai.inventory.service.RefundService.CreateCommand;
import com.loai.inventory.service.RefundService.RefundView;

/** Maps refund DTOs ↔ service inputs and domain → response. Lives in api/. */
public final class RefundMapper {

  private RefundMapper() {}

  public static CreateCommand toCommand(CreateRefundRequest req) {
    if (req == null) {
      throw new ValidationException("request body is required");
    }
    return new CreateCommand(
        req.getCreditNoteId(),
        req.getPaymentId(),
        req.getAmount(),
        req.getCurrency(),
        parseMethod(req.getMethod()),
        req.getNotes());
  }

  public static RefundResponse toResponse(Refund refund) {
    return RefundResponse.from(refund);
  }

  public static RefundResponse toResponse(RefundView view) {
    return RefundResponse.from(view);
  }

  /** Parse the optional {@code status} list filter; blank → null (no filter), unknown → 400. */
  public static RefundStatus toStatusFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return RefundStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown status: " + raw);
    }
  }

  /** Accept the enum name ({@code CASH}) or the DB literal ({@code cash}); blank → 400. */
  private static PaymentProvider parseMethod(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("method is required");
    }
    String t = raw.trim();
    try {
      return PaymentProvider.valueOf(t.toUpperCase());
    } catch (IllegalArgumentException ignored) {
      try {
        return PaymentProvider.fromDbLiteral(t.toLowerCase());
      } catch (IllegalArgumentException e) {
        throw new ValidationException("unknown refund method: " + raw);
      }
    }
  }
}
