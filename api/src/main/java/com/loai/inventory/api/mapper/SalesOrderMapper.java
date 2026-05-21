package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.PlaceOnlineOrderRequest;
import com.loai.inventory.api.dto.SalesOrderResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.Placed;
import java.util.List;

/** Maps DTOs ↔ service inputs and domain → response. Lives in api/ — never imported by service. */
public final class SalesOrderMapper {

  private SalesOrderMapper() {}

  public static CustomerInput toCustomerInput(PlaceOnlineOrderRequest req) {
    if (req == null || req.getCustomer() == null) {
      throw new ValidationException("customer is required");
    }
    PlaceOnlineOrderRequest.CustomerPayload c = req.getCustomer();
    return new CustomerInput(c.getName(), c.getEmail(), c.getPhone(), c.getAddress());
  }

  public static List<OrderLineInput> toLineInputs(PlaceOnlineOrderRequest req) {
    if (req == null || req.getLines() == null) {
      throw new ValidationException("lines must not be empty");
    }
    return req.getLines().stream()
        .map(
            l ->
                new OrderLineInput(l.getProductId(), l.getQuantity() == null ? 0 : l.getQuantity()))
        .toList();
  }

  public static SalesOrderResponse toResponse(Placed placed) {
    return SalesOrderResponse.from(placed.order(), placed.lines());
  }
}
