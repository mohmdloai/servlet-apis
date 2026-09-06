package com.loai.inventory.api.mapper;

import com.loai.inventory.api.dto.CancelOrderResponse;
import com.loai.inventory.api.dto.CounterReturnRequest;
import com.loai.inventory.api.dto.InStoreSaleResponse;
import com.loai.inventory.api.dto.OrderListSummaryResponse;
import com.loai.inventory.api.dto.OrderStatusCountsResponse;
import com.loai.inventory.api.dto.PlaceSalesOrderRequest;
import com.loai.inventory.api.dto.SalesOrderResponse;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderListFilter;
import com.loai.inventory.domain.model.OrderListStats;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.service.CounterReturnService;
import com.loai.inventory.service.OrderCancellationService.CancelResult;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.DiscountInput;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.OrderStatusCounts;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.SalesOrderService.Placed;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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

  /**
   * Maps the in-store tender: the single {@code payment} block becomes a one-element list, {@code
   * payments} maps element-wise (a null element stays null for the service to name), both present
   * is a 400, neither → null. Throws 400 on an unknown provider.
   */
  public static List<PaymentInput> toPaymentInputs(PlaceSalesOrderRequest req) {
    if (req == null) {
      return null;
    }
    boolean single = req.getPayment() != null;
    boolean split = req.getPayments() != null;
    if (single && split) {
      throw new ValidationException("send either payment or payments, not both");
    }
    if (single) {
      return List.of(toPaymentInput(req.getPayment()));
    }
    if (split) {
      List<PaymentInput> out = new java.util.ArrayList<>(req.getPayments().size());
      for (PlaceSalesOrderRequest.PaymentPayload p : req.getPayments()) {
        out.add(p == null ? null : toPaymentInput(p));
      }
      return out;
    }
    return null;
  }

  private static PaymentInput toPaymentInput(PlaceSalesOrderRequest.PaymentPayload p) {
    return new PaymentInput(parseProvider(p.getProvider()), p.getProviderRef(), p.getAmount());
  }

  /**
   * Maps the optional counter-discount block; null block → null. An unknown {@code type} is a 400
   * here (the service then range-checks the value) — the same split as {@link #toPaymentInputs}.
   */
  public static DiscountInput toDiscountInput(PlaceSalesOrderRequest req) {
    if (req == null || req.getDiscount() == null) {
      return null;
    }
    PlaceSalesOrderRequest.DiscountPayload d = req.getDiscount();
    CouponType type = null;
    if (d.getType() != null && !d.getType().isBlank()) {
      try {
        type = CouponType.valueOf(d.getType().trim().toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new ValidationException("discount.type must be PERCENT or FIXED");
      }
    }
    return new DiscountInput(type, d.getValue(), d.getReason());
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

  /**
   * Parse the worklist {@code channel} filter ({@code stories/counter_return.md}). Blank/absent ⇒
   * null (all channels); an unknown value is a 400 naming the three.
   */
  public static OrderChannel toOrderChannel(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return OrderChannel.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException(
          "Unknown order channel: " + raw + " (expected ONLINE, PHONE or IN_STORE)");
    }
  }

  /**
   * The whole {@code GET /sales-orders} query string as one {@link OrderListFilter} ({@code
   * stories/order_filters.md}). Every parameter is optional; blank is absent. What can 400: an
   * unknown {@code status}, {@code channel}, {@code balance} or {@code sort}; a {@code from}/{@code
   * to} that is not an ISO-8601 date-time (the {@code /reports} convention) or a window with {@code
   * from >= to}; a {@code min}/{@code max} that is not a number, is negative, or has {@code min >
   * max}. {@code q} cannot fail — any text is a valid search.
   */
  public static OrderListFilter toListFilter(
      String status,
      String channel,
      String q,
      String from,
      String to,
      String balance,
      String min,
      String max,
      String sort) {
    OffsetDateTime createdFrom = QueryParams.parseTs("from", from);
    OffsetDateTime createdTo = QueryParams.parseTs("to", to);
    if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
      throw new ValidationException("'from' must be strictly before 'to'");
    }
    BigDecimal minTotal = QueryParams.parseMoney("min", min);
    BigDecimal maxTotal = QueryParams.parseMoney("max", max);
    if (minTotal != null && maxTotal != null && minTotal.compareTo(maxTotal) > 0) {
      throw new ValidationException("'min' must not exceed 'max'");
    }
    return new OrderListFilter(
        toOrderStatus(status),
        toOrderChannel(channel),
        q == null || q.isBlank() ? null : q.trim(),
        createdFrom,
        createdTo,
        QueryParams.parseEnum(
            "balance", balance, OrderListFilter.Balance.class, "owing, settled, overpaid"),
        minTotal,
        maxTotal,
        QueryParams.parseEnum(
            "sort", sort, OrderListFilter.Sort.class, "newest, oldest, total, balance, expiring"));
  }

  public static OrderListSummaryResponse toSummaryResponse(OrderListStats stats) {
    return new OrderListSummaryResponse(stats.outstanding(), stats.value());
  }

  /** The counter-return body → command; {@code restock} defaults to true. */
  public static CounterReturnService.ReturnCommand toReturnCommand(CounterReturnRequest req) {
    if (req == null) {
      throw new ValidationException("request body is required");
    }
    List<CounterReturnService.LineInput> lines =
        req.getLines() == null
            ? List.of()
            : req.getLines().stream()
                .map(
                    l ->
                        new CounterReturnService.LineInput(
                            l == null ? null : l.getProductId(),
                            l == null || l.getQuantity() == null ? 0 : l.getQuantity()))
                .toList();
    return new CounterReturnService.ReturnCommand(
        lines, req.getRestock() == null || req.getRestock(), req.getReasonNote());
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
