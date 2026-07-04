package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.CreateFulfillmentRequest;
import com.loai.inventory.api.dto.DeliverFulfillmentResponse;
import com.loai.inventory.api.dto.FailedFulfillmentRefundResponse;
import com.loai.inventory.api.dto.FulfillmentResponse;
import com.loai.inventory.api.dto.OrderFulfillmentsResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.FulfillmentStatus;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.service.FulfillmentService.DeliveredView;
import com.loai.inventory.service.FulfillmentService.FailedRefundResult;
import com.loai.inventory.service.FulfillmentService.FulfillmentView;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.FulfillmentService.OrderFulfillments;
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
    return FulfillmentResponse.from(
        view.fulfillment(), view.lines(), view.salesOrderNumber(), view.fulfillmentValue());
  }

  public static List<FulfillmentResponse> toResponses(List<FulfillmentView> views) {
    return views.stream().map(FulfillmentMapper::toResponse).toList();
  }

  public static OrderFulfillmentsResponse toOrderFulfillmentsResponse(OrderFulfillments result) {
    return OrderFulfillmentsResponse.from(result.order(), toResponses(result.fulfillments()));
  }

  /** Parse the optional {@code status} list filter; blank → null (no filter), unknown → 400. */
  public static FulfillmentStatus toStatusFilter(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return FulfillmentStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown status: " + raw);
    }
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
