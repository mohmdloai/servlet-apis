package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Result of an in-store sale ({@code POST /api/orgs/{orgId}/sales-orders} with {@code channel =
 * IN_STORE}): the CLOSED order, the ISSUED→PAID invoice, the ALLOCATED payment and the DELIVERED
 * fulfillment — every aggregate created in the one checkout transaction.
 */
public class InStoreSaleResponse {

  private SalesOrderResponse order;
  private Invoice invoice;
  private Payment payment;
  private Fulfillment fulfillment;
  private Change change;

  private InStoreSaleResponse() {}

  public static InStoreSaleResponse from(InStoreSale sale) {
    InStoreSaleResponse r = new InStoreSaleResponse();
    r.order = SalesOrderResponse.from(sale.order(), sale.lines());
    r.invoice = Invoice.from(sale);
    r.payment = Payment.from(sale);
    r.fulfillment = Fulfillment.from(sale);
    r.change = Change.from(sale);
    return r;
  }

  public SalesOrderResponse getOrder() {
    return order;
  }

  public Invoice getInvoice() {
    return invoice;
  }

  public Payment getPayment() {
    return payment;
  }

  public Fulfillment getFulfillment() {
    return fulfillment;
  }

  public Change getChange() {
    return change;
  }

  /** The issued + paid invoice summary. */
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

    static Invoice from(InStoreSale sale) {
      com.loai.inventory.domain.model.SalesInvoice inv = sale.invoice();
      Invoice i = new Invoice();
      i.id = inv.getId();
      i.invoiceNumber = inv.getInvoiceNumber();
      i.status = inv.getStatus();
      i.subtotal = inv.getSubtotal();
      i.taxTotal = inv.getTaxTotal();
      i.grandTotal = inv.getGrandTotal();
      i.paidAmount = inv.getPaidAmount();
      i.lines =
          sale.invoiceLines().stream()
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

  /** The recognised + allocated payment summary. */
  public record Payment(UUID id, String status, BigDecimal amount, BigDecimal unallocatedAmount) {
    static Payment from(InStoreSale sale) {
      com.loai.inventory.domain.model.Payment p = sale.payment();
      return new Payment(p.getId(), p.getStatus().name(), p.getAmount(), p.getUnallocatedAmount());
    }
  }

  /**
   * Counter change handed back for an overpaid tender: the EXECUTED cash refund of the excess.
   * Omitted (null) for an exact tender.
   */
  public record Change(BigDecimal amount, UUID refundId, String status) {
    static Change from(InStoreSale sale) {
      com.loai.inventory.domain.model.Refund r = sale.changeRefund();
      return r == null ? null : new Change(r.getAmount(), r.getId(), r.getStatus().name());
    }
  }

  /** The DELIVERED fulfillment summary. */
  public record Fulfillment(UUID id, String status, OffsetDateTime deliveredAt) {
    static Fulfillment from(InStoreSale sale) {
      com.loai.inventory.domain.model.Fulfillment f = sale.fulfillment();
      return new Fulfillment(f.getId(), f.getStatus().name(), f.getDeliveredAt());
    }
  }
}
