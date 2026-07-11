package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.OrderPaymentsResponse;
import com.loai.inventory.api.dto.PaymentResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentStatus;
import com.loai.inventory.service.PaymentDisputeService.PaymentView;
import com.loai.inventory.service.PaymentService.OrderPayments;

/** Maps {@link Payment} domain → response DTO. Lives in api/. */
public final class PaymentMapper {

  private PaymentMapper() {}

  /** Parse the optional {@code status} list filter; blank → null (any), unknown → 400. */
  public static PaymentStatus toStatusFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return PaymentStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown status: " + raw);
    }
  }

  public static PaymentResponse toResponse(Payment payment) {
    return PaymentResponse.from(payment);
  }

  public static PaymentResponse toResponse(PaymentView view) {
    return PaymentResponse.from(view);
  }

  public static OrderPaymentsResponse toOrderPaymentsResponse(OrderPayments result) {
    return OrderPaymentsResponse.from(
        result.order(),
        result.payments().stream()
            .map(p -> OrderPaymentsResponse.Entry.from(p.payment(), p.refunds()))
            .toList());
  }
}
