package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.InStoreSaleResponse;
import com.loai.inventory.api.dto.PlaceSalesOrderRequest;
import com.loai.inventory.api.dto.SalesOrderResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.SalesOrderService.Placed;
import java.util.List;

/** Maps DTOs ↔ service inputs and domain → response. Lives in api/ — never imported by service. */
public final class SalesOrderMapper {

  private SalesOrderMapper() {}

  /** Returns {@code null} when no customer block is present (a walk-in in-store sale). */
  public static CustomerInput toCustomerInput(PlaceSalesOrderRequest req) {
    if (req == null || req.getCustomer() == null) {
      return null;
    }
    PlaceSalesOrderRequest.CustomerPayload c = req.getCustomer();
    return new CustomerInput(c.getName(), c.getEmail(), c.getPhone(), c.getAddress());
  }

  public static List<OrderLineInput> toLineInputs(PlaceSalesOrderRequest req) {
    if (req == null || req.getLines() == null) {
      throw new ValidationException("lines must not be empty");
    }
    return req.getLines().stream()
        .map(
            l ->
                new OrderLineInput(l.getProductId(), l.getQuantity() == null ? 0 : l.getQuantity()))
        .toList();
  }

  /** Maps the in-store tender block. Throws 400 on an unknown provider; null block → null. */
  public static PaymentInput toPaymentInput(PlaceSalesOrderRequest req) {
    if (req == null || req.getPayment() == null) {
      return null;
    }
    PlaceSalesOrderRequest.PaymentPayload p = req.getPayment();
    return new PaymentInput(parseProvider(p.getProvider()), p.getProviderRef(), p.getAmount());
  }

  public static SalesOrderResponse toResponse(Placed placed) {
    return SalesOrderResponse.from(placed.order(), placed.lines());
  }

  public static InStoreSaleResponse toInStoreResponse(InStoreSale sale) {
    return InStoreSaleResponse.from(sale);
  }

  /** Accept the enum name ({@code CASH}) or the DB literal ({@code cash}); blank → null. */
  private static PaymentProvider parseProvider(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String t = raw.trim();
    try {
      return PaymentProvider.valueOf(t.toUpperCase());
    } catch (IllegalArgumentException ignored) {
      try {
        return PaymentProvider.fromDbLiteral(t.toLowerCase());
      } catch (IllegalArgumentException e) {
        throw new ValidationException("unknown payment provider: " + raw);
      }
    }
  }
}
