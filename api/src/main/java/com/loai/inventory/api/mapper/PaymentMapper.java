package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.PaymentResponse;
import com.loai.inventory.domain.model.Payment;

/** Maps {@link Payment} domain → response DTO. Lives in api/. */
public final class PaymentMapper {

  private PaymentMapper() {}

  public static PaymentResponse toResponse(Payment payment) {
    return PaymentResponse.from(payment);
  }
}
