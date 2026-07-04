package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesOrder;
import java.util.List;

/**
 * Response for {@code GET /sales-orders/{id}/invoices} ({@code stories/money_reads.md}): the order
 * header (so the billing panel renders standalone, same reason as {@link OrderPaymentsResponse} /
 * {@link OrderFulfillmentsResponse}) + every invoice ever issued against the order oldest-first,
 * each the full {@link InvoiceResponse} shape with its lines. An order with no invoices returns an
 * empty {@code data}.
 */
public class OrderInvoicesResponse {

  private PaymentTransactionResponse.OrderSummary order;
  private List<InvoiceResponse> data;

  private OrderInvoicesResponse() {}

  public static OrderInvoicesResponse from(SalesOrder order, List<InvoiceResponse> data) {
    OrderInvoicesResponse r = new OrderInvoicesResponse();
    r.order = PaymentTransactionResponse.OrderSummary.from(order);
    r.data = data;
    return r;
  }

  public PaymentTransactionResponse.OrderSummary getOrder() {
    return order;
  }

  public List<InvoiceResponse> getData() {
    return data;
  }
}
