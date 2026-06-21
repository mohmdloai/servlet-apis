package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.service.FulfillmentService.DeliveredView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Result of {@code POST /fulfillments/{id}/deliver}: the now-DELIVERED fulfillment, the
 * SalesInvoice it issued, the auto-created PaymentAllocations, and the rolled-up order status.
 */
public class DeliverFulfillmentResponse {

  private UUID fulfillmentId;
  private String fulfillmentStatus;
  private OffsetDateTime deliveredAt;
  private Invoice invoice;
  private List<Allocation> allocations;
  private UUID salesOrderId;
  private OrderStatus orderStatus;

  private DeliverFulfillmentResponse() {}

  public static DeliverFulfillmentResponse from(DeliveredView view) {
    DeliverFulfillmentResponse r = new DeliverFulfillmentResponse();
    r.fulfillmentId = view.fulfillment().getId();
    r.fulfillmentStatus = view.fulfillment().getStatus().name();
    r.deliveredAt = view.fulfillment().getDeliveredAt();
    r.invoice = Invoice.from(view);
    r.allocations =
        view.allocations().stream()
            .map(a -> new Allocation(a.getId(), a.getPaymentId(), a.getAmount()))
            .toList();
    r.salesOrderId = view.order().getId();
    r.orderStatus = view.order().getStatus();
    return r;
  }

  public UUID getFulfillmentId() {
    return fulfillmentId;
  }

  public String getFulfillmentStatus() {
    return fulfillmentStatus;
  }

  public OffsetDateTime getDeliveredAt() {
    return deliveredAt;
  }

  public Invoice getInvoice() {
    return invoice;
  }

  public List<Allocation> getAllocations() {
    return allocations;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public OrderStatus getOrderStatus() {
    return orderStatus;
  }

  /** The issued invoice summary. */
  public static class Invoice {
    private UUID id;
    private String invoiceNumber;
    private InvoiceStatus status;
    private BigDecimal subtotal;
    private BigDecimal taxTotal;
    private BigDecimal grandTotal;
    private BigDecimal paidAmount;
    private List<Line> lines;

    private Invoice() {}

    static Invoice from(DeliveredView view) {
      com.loai.inventory.domain.model.SalesInvoice inv = view.invoice();
      Invoice i = new Invoice();
      i.id = inv.getId();
      i.invoiceNumber = inv.getInvoiceNumber();
      i.status = inv.getStatus();
      i.subtotal = inv.getSubtotal();
      i.taxTotal = inv.getTaxTotal();
      i.grandTotal = inv.getGrandTotal();
      i.paidAmount = inv.getPaidAmount();
      i.lines =
          view.invoiceLines().stream()
              .map(
                  l ->
                      new Line(
                          l.getId(),
                          l.getProductId(),
                          l.getDescription(),
                          l.getQuantity(),
                          l.getUnitPrice(),
                          l.getLineTotal()))
              .toList();
      return i;
    }

    public UUID getId() {
      return id;
    }

    public String getInvoiceNumber() {
      return invoiceNumber;
    }

    public InvoiceStatus getStatus() {
      return status;
    }

    public BigDecimal getSubtotal() {
      return subtotal;
    }

    public BigDecimal getTaxTotal() {
      return taxTotal;
    }

    public BigDecimal getGrandTotal() {
      return grandTotal;
    }

    public BigDecimal getPaidAmount() {
      return paidAmount;
    }

    public List<Line> getLines() {
      return lines;
    }
  }

  /** One billed invoice line. */
  public record Line(
      UUID id,
      UUID productId,
      String description,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal lineTotal) {}

  /** One payment allocation created from prepayment. */
  public record Allocation(UUID id, UUID paymentId, BigDecimal amount) {}
}
