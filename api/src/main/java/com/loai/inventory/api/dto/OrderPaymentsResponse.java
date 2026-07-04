package com.loai.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesOrder;
import java.util.List;

/**
 * Response for {@code GET /sales-orders/{id}/payments} ({@code stories/list_order_payments.md}):
 * the order header (so the money panel renders standalone) + every payment FIFO, each row the
 * existing {@link PaymentResponse} shape flattened with its {@link RefundResponse} list. Empty
 * {@code refunds} arrays are present-but-empty; an order with no payments returns an empty {@code
 * data}.
 */
public class OrderPaymentsResponse {

  private PaymentTransactionResponse.OrderSummary order;
  private List<Entry> data;

  private OrderPaymentsResponse() {}

  public static OrderPaymentsResponse from(SalesOrder order, List<Entry> data) {
    OrderPaymentsResponse r = new OrderPaymentsResponse();
    r.order = PaymentTransactionResponse.OrderSummary.from(order);
    r.data = data;
    return r;
  }

  public PaymentTransactionResponse.OrderSummary getOrder() {
    return order;
  }

  public List<Entry> getData() {
    return data;
  }

  /** A payment row (flattened {@link PaymentResponse}) plus its refunds, oldest first. */
  public static class Entry {
    private PaymentResponse payment;
    private List<RefundResponse> refunds;

    private Entry() {}

    public static Entry from(Payment payment, List<Refund> refunds) {
      Entry e = new Entry();
      e.payment = PaymentResponse.from(payment);
      e.refunds = refunds.stream().map(RefundResponse::from).toList();
      return e;
    }

    @JsonUnwrapped
    public PaymentResponse getPayment() {
      return payment;
    }

    public List<RefundResponse> getRefunds() {
      return refunds;
    }
  }
}
