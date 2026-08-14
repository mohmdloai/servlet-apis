package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.CancelOrderResponse;
import com.loai.inventory.api.dto.InStoreSaleResponse;
import com.loai.inventory.api.dto.OrderStatusCountsResponse;
import com.loai.inventory.api.dto.PlaceSalesOrderRequest;
import com.loai.inventory.api.dto.SalesOrderResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.service.OrderCancellationService.CancelResult;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.OrderStatusCounts;
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

  public static OrderStatusCountsResponse toStatusCountsResponse(OrderStatusCounts counts) {
    return new OrderStatusCountsResponse(counts.counts(), counts.total());
  }

  /**
   * Parse the worklist {@code status} filter. Blank/absent ⇒ null (unfiltered ledger); an unknown
   * value is a 400, never a silent all-rows fallthrough.
   */
  public static OrderStatus toOrderStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return OrderStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown order status: " + raw);
    }
  }

  public static InStoreSaleResponse toInStoreResponse(InStoreSale sale) {
    return InStoreSaleResponse.from(sale);
  }

  /** Parse the optional refund method on a cancel request; blank → null (service default). */
  public static PaymentProvider toRefundMethod(String raw) {
    return parseProvider(raw);
  }

  public static CancelOrderResponse toCancelResponse(CancelResult result) {
    SalesOrder order = result.order();
    CancelOrderResponse r = new CancelOrderResponse();
    r.setOrderId(order.getId());
    r.setOrderNumber(order.getOrderNumber());
    r.setStatus(order.getStatus().name());
    r.setCancelledAt(order.getCancelledAt());
    r.setReservationsReleased(result.reservationsReleased());
    r.setPendingRefundTotal(result.pendingRefundTotal());
    r.setPendingRefundIds(result.refunds().stream().map(Refund::getId).toList());
    return r;
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
