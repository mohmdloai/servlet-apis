package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.PaymentTransactionResponse;
import com.loai.inventory.api.dto.ResolveOrphanRequest;
import com.loai.inventory.api.dto.VerifyPaymentTransactionRequest;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;

/** Maps the verify request DTO → service command and the service result → response DTO. */
public final class PaymentTransactionMapper {

  private PaymentTransactionMapper() {}

  public static VerifyCommand toCommand(VerifyPaymentTransactionRequest req) {
    if (req == null) {
      throw new ValidationException("request body is required");
    }
    return new VerifyCommand(
        parseProvider(req.getProvider()),
        req.getProviderRef(),
        req.getAmount(),
        req.getCurrency(),
        req.getSalesOrderId(),
        req.getOrderNumber(),
        req.getClaimedByCustomerId(),
        req.getCustomerNote(),
        req.getVerificationProof(),
        req.getOccurredAt());
  }

  public static PaymentTransactionResponse toResponse(VerifyResult result) {
    return PaymentTransactionResponse.from(result.transaction(), result.payment(), result.order());
  }

  /** The order reference an admin chose when resolving an ORPHAN transaction. */
  public static OrderRef toOrderRef(ResolveOrphanRequest req) {
    if (req == null) {
      throw new ValidationException("request body is required");
    }
    return new OrderRef(req.getSalesOrderId(), req.getOrderNumber());
  }

  /** Accept either the enum name ({@code INSTAPAY_MANUAL}) or the DB literal ({@code cash}). */
  private static PaymentProvider parseProvider(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("provider is required");
    }
    String v = raw.trim();
    try {
      return PaymentProvider.valueOf(v.toUpperCase());
    } catch (IllegalArgumentException ignored) {
      try {
        return PaymentProvider.fromDbLiteral(v.toLowerCase());
      } catch (IllegalArgumentException e) {
        throw new ValidationException("unknown provider: " + raw);
      }
    }
  }
}
