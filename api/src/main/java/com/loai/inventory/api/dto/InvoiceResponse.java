package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response shape for a sales invoice + its lines. */
public class InvoiceResponse {
  private UUID id;
  private UUID customerId;
  private UUID salesOrderId;
  private UUID fulfillmentId;
  private String invoiceNumber;
  private String status;
  private BigDecimal subtotal;
  private BigDecimal taxTotal;
  private BigDecimal shippingTotal;
  private BigDecimal discountTotal;
  private BigDecimal grandTotal;
  private String currency;
  private String customerName;
  private String customerEmail;
  private String customerPhone;
  private String customerAddress;
  private BigDecimal paidAmount;
  private OffsetDateTime issuedAt;
  private OffsetDateTime voidedAt;
  private String voidReason;
  private List<Line> lines;

  private InvoiceResponse() {}

  public static InvoiceResponse from(SalesInvoice inv, List<SalesInvoiceLine> lines) {
    InvoiceResponse r = new InvoiceResponse();
    r.id = inv.getId();
    r.customerId = inv.getCustomerId();
    r.salesOrderId = inv.getSalesOrderId();
    r.fulfillmentId = inv.getFulfillmentId();
    r.invoiceNumber = inv.getInvoiceNumber();
    r.status = inv.getStatus().name();
    r.subtotal = inv.getSubtotal();
    r.taxTotal = inv.getTaxTotal();
    r.shippingTotal = inv.getShippingTotal();
    r.discountTotal = inv.getDiscountTotal();
    r.grandTotal = inv.getGrandTotal();
    r.currency = inv.getCurrency();
    r.customerName = inv.getCustomerName();
    r.customerEmail = inv.getCustomerEmail();
    r.customerPhone = inv.getCustomerPhone();
    r.customerAddress = inv.getCustomerAddress();
    r.paidAmount = inv.getPaidAmount();
    r.issuedAt = inv.getIssuedAt();
    r.voidedAt = inv.getVoidedAt();
    r.voidReason = inv.getVoidReason();
    r.lines = lines.stream().map(Line::from).toList();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public UUID getFulfillmentId() {
    return fulfillmentId;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public String getStatus() {
    return status;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getTaxTotal() {
    return taxTotal;
  }

  public BigDecimal getShippingTotal() {
    return shippingTotal;
  }

  public BigDecimal getDiscountTotal() {
    return discountTotal;
  }

  public BigDecimal getGrandTotal() {
    return grandTotal;
  }

  public String getCurrency() {
    return currency;
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

  public BigDecimal getPaidAmount() {
    return paidAmount;
  }

  public OffsetDateTime getIssuedAt() {
    return issuedAt;
  }

  public OffsetDateTime getVoidedAt() {
    return voidedAt;
  }

  public String getVoidReason() {
    return voidReason;
  }

  public List<Line> getLines() {
    return lines;
  }

  /** One invoice line in the response. */
  public static class Line {
    private UUID id;
    private UUID productId;
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
      r.id = l.getId();
      r.productId = l.getProductId();
      r.description = l.getDescription();
      r.quantity = l.getQuantity();
      r.unitPrice = l.getUnitPrice();
      r.taxRate = l.getTaxRate();
      r.lineSubtotal = l.getLineSubtotal();
      r.lineTax = l.getLineTax();
      r.lineTotal = l.getLineTotal();
      return r;
    }

    public UUID getId() {
      return id;
    }

    public UUID getProductId() {
      return productId;
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
