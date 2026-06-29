package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.CreateFulfillmentRequest;
import com.loai.inventory.api.dto.DeliverFulfillmentResponse;
import com.loai.inventory.api.dto.FailedFulfillmentRefundResponse;
import com.loai.inventory.api.dto.FulfillmentResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.service.FulfillmentService.DeliveredView;
import com.loai.inventory.service.FulfillmentService.FailedRefundResult;
import com.loai.inventory.service.FulfillmentService.FulfillmentView;
import com.loai.inventory.service.FulfillmentService.LineInput;
import java.util.List;

/** Maps DTOs ↔ service inputs and domain → response. Lives in api/ — never imported by service. */
public final class FulfillmentMapper {

  private FulfillmentMapper() {}

  public static List<LineInput> toLineInputs(CreateFulfillmentRequest req) {
    if (req == null || req.getLines() == null) {
      throw new ValidationException("lines must not be empty");
    }
    return req.getLines().stream()
        .map(l -> new LineInput(l == null ? null : l.getSalesOrderLineId()))
        .toList();
  }

  public static FulfillmentResponse toResponse(FulfillmentView view) {
    return FulfillmentResponse.from(view.fulfillment(), view.lines());
  }

  public static DeliverFulfillmentResponse toDeliverResponse(DeliveredView view) {
    return DeliverFulfillmentResponse.from(view);
  }

  public static FailedFulfillmentRefundResponse toRefundResponse(FailedRefundResult result) {
    return FailedFulfillmentRefundResponse.from(result);
  }

  /** Parse the optional refund method on a failed-fulfillment refund; blank → null (default). */
  public static PaymentProvider toRefundMethod(String raw) {
    return SalesOrderMapper.toRefundMethod(raw);
  }
}
