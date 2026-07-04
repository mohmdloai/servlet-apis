package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.OrderPaymentsResponse;
import com.loai.inventory.api.dto.PaymentResponse;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.service.PaymentService.OrderPayments;

/** Maps {@link Payment} domain → response DTO. Lives in api/. */
public final class PaymentMapper {

  private PaymentMapper() {}

  public static PaymentResponse toResponse(Payment payment) {
    return PaymentResponse.from(payment);
  }

  public static OrderPaymentsResponse toOrderPaymentsResponse(OrderPayments result) {
    return OrderPaymentsResponse.from(
        result.order(),
        result.payments().stream()
            .map(p -> OrderPaymentsResponse.Entry.from(p.payment(), p.refunds()))
            .toList());
  }
}
