package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.TenderPayment;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Result of an in-store sale ({@code POST /api/orgs/{orgId}/sales-orders} with {@code channel =
 * IN_STORE}): the CLOSED order, the ISSUED→PAID invoice, the ALLOCATED payment per tender and the
 * DELIVERED fulfillment — every aggregate created in the one checkout transaction.
 *
 * <p>{@code payments} is the truth, one entry per tender in the ledger's order ({@code
 * stories/split_tender.md}); {@code payment} repeats its first element for one release so an admin
 * build that predates the split-tender pair still renders.
 */
public class InStoreSaleResponse {

  private SalesOrderResponse order;
  private Invoice invoice;
  private List<Payment> payments;
  private Payment payment;
  private Fulfillment fulfillment;
  private Change change;

  private InStoreSaleResponse() {}

  public static InStoreSaleResponse from(InStoreSale sale) {
    InStoreSaleResponse r = new InStoreSaleResponse();
    r.order = SalesOrderResponse.from(sale.order(), sale.lines());
    r.invoice = Invoice.from(sale);
    r.payments = sale.payments().stream().map(Payment::from).toList();
    r.payment = r.payments.get(0);
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

  public List<Payment> getPayments() {
    return payments;
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
    private BigDecimal discountTotal;
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
      // Carried since V21; the in-store DTO never surfaced it because it was always zero. A
      // discounted receipt needs it to explain subtotal ≠ total.
      i.discountTotal = inv.getDiscountTotal();
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

    public BigDecimal getDiscountTotal() {
      return discountTotal;
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

  /** One recognised + allocated tender: the payment with its transaction's provider and ref. */
  public record Payment(
      UUID id,
      String provider,
      String providerRef,
      String status,
      BigDecimal amount,
      BigDecimal unallocatedAmount) {
    static Payment from(TenderPayment tp) {
      com.loai.inventory.domain.model.Payment p = tp.payment();
      return new Payment(
          p.getId(),
          tp.transaction().getProvider().name(),
          tp.transaction().getProviderRef(),
          p.getStatus().name(),
          p.getAmount(),
          p.getUnallocatedAmount());
    }
  }

  /**
   * Counter change handed back for an overpaid tender: {@code amount} is the sum across every
   * residual payment, {@code refunds} one EXECUTED cash refund per such payment; {@code refundId} /
   * {@code status} describe the first (compat). Omitted (null) for an exact tender.
   */
  public record Change(
      BigDecimal amount, UUID refundId, String status, List<ChangeRefund> refunds) {
    static Change from(InStoreSale sale) {
      if (sale.changeRefunds().isEmpty()) {
        return null;
      }
      Refund first = sale.changeRefunds().get(0);
      return new Change(
          sale.changeAmount(),
          first.getId(),
          first.getStatus().name(),
          sale.changeRefunds().stream()
              .map(
                  r ->
                      new ChangeRefund(
                          r.getId(), r.getAmount(), r.getStatus().name(), r.getPaymentId()))
              .toList());
    }
  }

  /** One change refund: the EXECUTED cash draw off the payment that held the residue. */
  public record ChangeRefund(UUID refundId, BigDecimal amount, String status, UUID paymentId) {}

  /** The DELIVERED fulfillment summary. */
  public record Fulfillment(UUID id, String status, OffsetDateTime deliveredAt) {
    static Fulfillment from(InStoreSale sale) {
      com.loai.inventory.domain.model.Fulfillment f = sale.fulfillment();
      return new Fulfillment(f.getId(), f.getStatus().name(), f.getDeliveredAt());
    }
  }
}
