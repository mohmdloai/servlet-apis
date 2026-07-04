package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesOrder;
import java.util.List;

/**
 * Response for {@code GET /sales-orders/{id}/fulfillments} ({@code stories/fulfillment_reads.md}):
 * the order header (so the shipment panel renders standalone, same reason as {@link
 * OrderPaymentsResponse}) + every fulfillment ever created for the order oldest-first, each the
 * full {@link FulfillmentResponse} shape with its lines. An order with no fulfillments returns an
 * empty {@code data}.
 */
public class OrderFulfillmentsResponse {

  private PaymentTransactionResponse.OrderSummary order;
  private List<FulfillmentResponse> data;

  private OrderFulfillmentsResponse() {}

  public static OrderFulfillmentsResponse from(SalesOrder order, List<FulfillmentResponse> data) {
    OrderFulfillmentsResponse r = new OrderFulfillmentsResponse();
    r.order = PaymentTransactionResponse.OrderSummary.from(order);
    r.data = data;
    return r;
  }

  public PaymentTransactionResponse.OrderSummary getOrder() {
    return order;
  }

  public List<FulfillmentResponse> getData() {
    return data;
  }
}
