package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteLine;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Response shape for a credit note + its lines. */
public class CreditNoteResponse {
  private UUID id;
  private UUID salesInvoiceId;
  private UUID customerId;
  private String creditNoteNumber;
  private String status;
  private String reason;
  private String reasonNote;
  private BigDecimal subtotal;
  private BigDecimal taxTotal;
  private BigDecimal total;
  private BigDecimal refundedTotal;
  private BigDecimal remainingRefundable;
  private String currency;
  private OffsetDateTime issuedAt;
  private List<Line> lines;

  private CreditNoteResponse() {}

  /** Detail shape (issuance response): lines, no refund decoration. */
  public static CreditNoteResponse from(CreditNote n, List<CreditNoteLine> lines) {
    CreditNoteResponse r = from(n);
    r.lines = lines.stream().map(Line::from).toList();
    return r;
  }

  /**
   * Detail shape for the GET read: lines plus the refund meter — {@code refunded_total} (sum of
   * EXECUTED refunds against this note) and {@code remaining_refundable} ({@code total −
   * refunded_total}), so a client never reconstructs it from the refund ledger.
   */
  public static CreditNoteResponse from(
      CreditNote n, List<CreditNoteLine> lines, BigDecimal refundedTotal) {
    CreditNoteResponse r = from(n, refundedTotal);
    r.lines = lines.stream().map(Line::from).toList();
    return r;
  }

  /**
   * The header-only shape for list rows ({@code GET /credit-notes?sales_invoice_id=}): {@code
   * lines} is omitted — the detail read has them.
   */
  public static CreditNoteResponse from(CreditNote n) {
    CreditNoteResponse r = new CreditNoteResponse();
    r.id = n.getId();
    r.salesInvoiceId = n.getSalesInvoiceId();
    r.customerId = n.getCustomerId();
    r.creditNoteNumber = n.getCreditNoteNumber();
    r.status = n.getStatus().name();
    r.reason = n.getReason().name();
    r.reasonNote = n.getReasonNote();
    r.subtotal = n.getSubtotal();
    r.taxTotal = n.getTaxTotal();
    r.total = n.getTotal();
    r.currency = n.getCurrency();
    r.issuedAt = n.getIssuedAt();
    return r;
  }

  /** Header row + the refund meter, for the invoice's credit-note list. */
  public static CreditNoteResponse from(CreditNote n, BigDecimal refundedTotal) {
    CreditNoteResponse r = from(n);
    r.refundedTotal = refundedTotal;
    r.remainingRefundable = n.getTotal().subtract(refundedTotal);
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSalesInvoiceId() {
    return salesInvoiceId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public String getCreditNoteNumber() {
    return creditNoteNumber;
  }

  public String getStatus() {
    return status;
  }

  public String getReason() {
    return reason;
  }

  public String getReasonNote() {
    return reasonNote;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getTaxTotal() {
    return taxTotal;
  }

  public BigDecimal getTotal() {
    return total;
  }

  public BigDecimal getRefundedTotal() {
    return refundedTotal;
  }

  public BigDecimal getRemainingRefundable() {
    return remainingRefundable;
  }

  public String getCurrency() {
    return currency;
  }

  public OffsetDateTime getIssuedAt() {
    return issuedAt;
  }

  public List<Line> getLines() {
    return lines;
  }

  /** One credit-note line in the response. */
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

    static Line from(CreditNoteLine l) {
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
