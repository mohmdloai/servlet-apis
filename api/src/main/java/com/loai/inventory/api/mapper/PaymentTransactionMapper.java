package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.OrphanRefundResponse;
import com.loai.inventory.api.dto.PaymentTransactionResponse;
import com.loai.inventory.api.dto.RefundOrphanRequest;
import com.loai.inventory.api.dto.ResolveOrphanRequest;
import com.loai.inventory.api.dto.VerifyClaimRequest;
import com.loai.inventory.api.dto.VerifyPaymentTransactionRequest;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService.ClaimContext;
import com.loai.inventory.service.PaymentTransactionService.OrphanRefundResult;
import com.loai.inventory.service.PaymentTransactionService.VerifyClaimCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

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
        req.getOccurredAt(),
        req.getAcknowledgeClaimIds() == null
            ? Set.of()
            : new HashSet<UUID>(req.getAcknowledgeClaimIds()),
        req.getSupersedesClaimId());
  }

  /** The "Found it" body — absent body = the claim's own figures. */
  public static VerifyClaimCommand toClaimCommand(VerifyClaimRequest req) {
    if (req == null) {
      return new VerifyClaimCommand(null, null, null);
    }
    return new VerifyClaimCommand(req.getAmount(), req.getOccurredAt(), req.getVerificationProof());
  }

  public static PaymentTransactionResponse toResponse(VerifyResult result) {
    return PaymentTransactionResponse.from(result.transaction(), result.payment(), result.order());
  }

  /**
   * The verify-by-id response: the verify result plus the claim's context (who filed / verified).
   */
  public static PaymentTransactionResponse toResponse(VerifyResult result, ClaimContext ctx) {
    return PaymentTransactionResponse.withContext(
        result.transaction(),
        result.payment(),
        result.order(),
        ctx.customer(),
        ctx.claimedOrder(),
        ctx.verifiedByName());
  }

  /** The order reference an admin chose when resolving an ORPHAN transaction. */
  public static OrderRef toOrderRef(ResolveOrphanRequest req) {
    if (req == null) {
      throw new ValidationException("request body is required");
    }
    return new OrderRef(req.getSalesOrderId(), req.getOrderNumber());
  }

  /** Refund method chosen for an orphan refund; null → service defaults to the txn's provider. */
  public static PaymentProvider toRefundMethod(RefundOrphanRequest req) {
    if (req == null || req.getMethod() == null || req.getMethod().isBlank()) {
      return null;
    }
    return parseProvider(req.getMethod());
  }

  public static OrphanRefundResponse toResponse(OrphanRefundResult result) {
    return OrphanRefundResponse.from(result.transaction(), result.payment(), result.refund());
  }

  /**
   * Optional {@code provider} list filter: absent/blank → null (no filter); otherwise the enum name
   * or DB literal, unknown value → 400.
   */
  public static PaymentProvider toProviderFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return parseProvider(raw);
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
