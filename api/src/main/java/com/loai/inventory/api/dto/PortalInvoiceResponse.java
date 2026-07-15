package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Customer-safe view of one of the caller's own invoices + lines — the {@code GET
 * /api/portal/invoices/{id}} body (slice P3, {@code stories/portal_invoices.md}). A deliberate
 * whitelist of the staff {@link InvoiceResponse}: it keeps only what a customer needs to read and
 * download their own document — the invoice {@code id} (the addressing handle for {@code /{id}} and
 * {@code /{id}/pdf}), number, dates, status, currency, the frozen money meter, the customer
 * snapshot (their own data), and the line descriptions/amounts.
 *
 * <p>Dropped as staff-only / internal (mirrors the epic no-leak rule and the public order view):
 * {@code customer_id}, {@code sales_order_id}, {@code fulfillment_id}, {@code voided_at}/{@code
 * void_reason} (a VOID invoice is never exposed anyway), and each line's {@code id} and {@code
 * product_id}.
 */
public class PortalInvoiceResponse {
  private UUID id;
  private String invoiceNumber;
  private String status;
  private OffsetDateTime issuedAt;
  private String currency;
  private BigDecimal subtotal;
  private BigDecimal taxTotal;
  private BigDecimal discountTotal;
  private BigDecimal grandTotal;
  private BigDecimal paidAmount;
  private BigDecimal balance;
  private String customerName;
  private String customerEmail;
  private String customerPhone;
  private String customerAddress;
  private List<Line> lines;

  private PortalInvoiceResponse() {}

  public static PortalInvoiceResponse from(SalesInvoice inv, List<SalesInvoiceLine> lines) {
    PortalInvoiceResponse r = new PortalInvoiceResponse();
    r.id = inv.getId();
    r.invoiceNumber = inv.getInvoiceNumber();
    r.status = inv.getStatus().name();
    r.issuedAt = inv.getIssuedAt();
    r.currency = inv.getCurrency();
    r.subtotal = inv.getSubtotal();
    r.taxTotal = inv.getTaxTotal();
    r.discountTotal = inv.getDiscountTotal();
    r.grandTotal = inv.getGrandTotal();
    r.paidAmount = inv.getPaidAmount();
    r.balance = nz(inv.getGrandTotal()).subtract(nz(inv.getPaidAmount()));
    r.customerName = inv.getCustomerName();
    r.customerEmail = inv.getCustomerEmail();
    r.customerPhone = inv.getCustomerPhone();
    r.customerAddress = inv.getCustomerAddress();
    r.lines = lines.stream().map(Line::from).toList();
    return r;
  }

  private static BigDecimal nz(BigDecimal b) {
    return b == null ? BigDecimal.ZERO : b;
  }

  public UUID getId() {
    return id;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public String getStatus() {
    return status;
  }

  public OffsetDateTime getIssuedAt() {
    return issuedAt;
  }

  public String getCurrency() {
    return currency;
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

  public BigDecimal getBalance() {
    return balance;
  }

  public String getCustomerName() {
    return customerName;
  }

  public String getCustomerEmail() {
    return customerEmail;
  }

  public String getCustomerPhone() {
    return customerPhone;
  }

  public String getCustomerAddress() {
    return customerAddress;
  }

  public List<Line> getLines() {
    return lines;
  }

  /** One invoice line — description + amounts only; no id, no {@code product_id}. */
  public static class Line {
    private String description;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal taxRate;
    private BigDecimal lineSubtotal;
    private BigDecimal lineTax;
    private BigDecimal lineTotal;

    private Line() {}

    static Line from(SalesInvoiceLine l) {
      Line r = new Line();
      r.description = l.getDescription();
      r.quantity = l.getQuantity();
      r.unitPrice = l.getUnitPrice();
      r.taxRate = l.getTaxRate();
      r.lineSubtotal = l.getLineSubtotal();
      r.lineTax = l.getLineTax();
      r.lineTotal = l.getLineTotal();
      return r;
    }

    public String getDescription() {
      return description;
    }

    public int getQuantity() {
      return quantity;
    }

    public BigDecimal getUnitPrice() {
      return unitPrice;
    }

    public BigDecimal getTaxRate() {
      return taxRate;
    }

    public BigDecimal getLineSubtotal() {
      return lineSubtotal;
    }

    public BigDecimal getLineTax() {
      return lineTax;
    }

    public BigDecimal getLineTotal() {
      return lineTotal;
    }
  }
}
