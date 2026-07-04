package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.service.CreditNoteService.InvoiceCreditNotes;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Response for {@code GET /credit-notes?sales_invoice_id=} ({@code stories/money_reads.md}): the
 * invoice header, the cap guard's own already-credited sum ({@code credited_total} — ISSUED +
 * SETTLED, unaffected by the {@code status} filter), and the notes oldest-first. {@code
 * credited_total} of {@code invoice.grand_total} is the honest "X of Y already credited" meter —
 * the same numbers issuance enforces, not a cap discovered by a 400.
 */
public class InvoiceCreditNotesResponse {

  private InvoiceSummary invoice;
  private BigDecimal creditedTotal;
  private List<CreditNoteResponse> data;

  private InvoiceCreditNotesResponse() {}

  public static InvoiceCreditNotesResponse from(InvoiceCreditNotes result) {
    InvoiceCreditNotesResponse r = new InvoiceCreditNotesResponse();
    r.invoice = InvoiceSummary.from(result.invoice());
    r.creditedTotal = result.creditedTotal();
    r.data = result.notes().stream().map(CreditNoteResponse::from).toList();
    return r;
  }

  public InvoiceSummary getInvoice() {
    return invoice;
  }

  public BigDecimal getCreditedTotal() {
    return creditedTotal;
  }

  public List<CreditNoteResponse> getData() {
    return data;
  }

  /** The credited invoice's identity + the cap ({@code grand_total}). */
  public static class InvoiceSummary {
    private UUID id;
    private String invoiceNumber;
    private String status;
    private BigDecimal grandTotal;
    private String currency;

    private InvoiceSummary() {}

    static InvoiceSummary from(SalesInvoice inv) {
      InvoiceSummary s = new InvoiceSummary();
      s.id = inv.getId();
      s.invoiceNumber = inv.getInvoiceNumber();
      s.status = inv.getStatus().name();
      s.grandTotal = inv.getGrandTotal();
      s.currency = inv.getCurrency();
      return s;
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

    public BigDecimal getGrandTotal() {
      return grandTotal;
    }

    public String getCurrency() {
      return currency;
    }
  }
}
