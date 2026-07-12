package com.loai.inventory.service.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceAdminService.InvoiceView;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentService.PaymentWithRefunds;
import com.loai.inventory.service.document.DocumentRenderService.RenderedDocument;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the PDF renderer: valid aggregates render non-empty {@code %PDF} bytes with a
 * filename derived from the document number, and an org with no billing profile still renders.
 */
class DocumentRenderServiceTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID INVOICE = UUID.randomUUID();
  private static final UUID ORDER = UUID.randomUUID();

  private final OrgService orgService = mock(OrgService.class);
  private final InvoiceAdminService invoiceAdminService = mock(InvoiceAdminService.class);
  private final CreditNoteService creditNoteService = mock(CreditNoteService.class);
  private final PaymentService paymentService = mock(PaymentService.class);

  private final DocumentRenderService svc =
      new DocumentRenderService(orgService, invoiceAdminService, creditNoteService, paymentService);

  private static Org orgWithProfile() {
    Org o = new Org();
    o.setName("Acme");
    o.setLegalName("Acme Stationery LLC");
    o.setTaxRegistrationNumber("TRN-123456");
    o.setAddressLine1("12 Nile St");
    o.setCity("Cairo");
    o.setCountry("Egypt");
    o.setPhone("+20 100 000 0000");
    o.setContactEmail("shop@acme.example");
    return o;
  }

  private static Org orgNameOnly() {
    Org o = new Org();
    o.setName("Bare Org");
    return o;
  }

  private static SalesInvoice anInvoice() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    SalesInvoice inv =
        SalesInvoice.createDraft(
            INVOICE,
            ORG,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new BigDecimal("100.00"),
            new BigDecimal("20.00"),
            BigDecimal.ZERO,
            "EGP",
            "Nadia",
            "nadia@example.com",
            null,
            null,
            now);
    inv.issue("INV-2026-0007", now);
    return inv;
  }

  private static SalesInvoiceLine aLine() {
    return SalesInvoiceLine.create(
        UUID.randomUUID(),
        INVOICE,
        UUID.randomUUID(),
        "Blue pen",
        2,
        new BigDecimal("50.00"),
        new BigDecimal("0.20"));
  }

  private static boolean isPdf(byte[] bytes) {
    return bytes.length > 200
        && new String(bytes, 0, 4, StandardCharsets.ISO_8859_1).equals("%PDF");
  }

  @Test
  void renderInvoice_producesPdfNamedAfterInvoiceNumber() {
    when(invoiceAdminService.get(ORG, INVOICE))
        .thenReturn(new InvoiceView(anInvoice(), List.of(aLine())));
    when(orgService.getById(ORG)).thenReturn(orgWithProfile());

    RenderedDocument doc = svc.renderInvoice(ORG, INVOICE);

    assertEquals("INV-2026-0007.pdf", doc.filename());
    assertTrue(isPdf(doc.bytes()), "should be a non-empty PDF");
  }

  @Test
  void renderInvoice_stillRendersWithNoBillingProfile() {
    when(invoiceAdminService.get(ORG, INVOICE))
        .thenReturn(new InvoiceView(anInvoice(), List.of(aLine())));
    when(orgService.getById(ORG)).thenReturn(orgNameOnly());

    RenderedDocument doc = svc.renderInvoice(ORG, INVOICE);

    assertTrue(isPdf(doc.bytes()));
  }

  @Test
  void renderCreditNote_producesPdfNamedAfterCreditNoteNumber() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID cnId = UUID.randomUUID();
    CreditNote cn = mock(CreditNote.class);
    when(cn.getSalesInvoiceId()).thenReturn(INVOICE);
    when(cn.getCreditNoteNumber()).thenReturn("CN-2026-0003");
    when(cn.getStatus()).thenReturn(CreditNoteStatus.ISSUED);
    when(cn.getSubtotal()).thenReturn(new BigDecimal("50.00"));
    when(cn.getTaxTotal()).thenReturn(new BigDecimal("10.00"));
    when(cn.getTotal()).thenReturn(new BigDecimal("60.00"));
    when(cn.getCurrency()).thenReturn("EGP");
    when(cn.getIssuedAt()).thenReturn(now);

    when(creditNoteService.get(ORG, cnId))
        .thenReturn(new CreditNoteService.Detail(cn, List.of(), BigDecimal.ZERO));
    when(invoiceAdminService.get(ORG, INVOICE))
        .thenReturn(new InvoiceView(anInvoice(), List.of(aLine())));
    when(orgService.getById(ORG)).thenReturn(orgWithProfile());

    RenderedDocument doc = svc.renderCreditNote(ORG, cnId);

    assertEquals("CN-2026-0003.pdf", doc.filename());
    assertTrue(isPdf(doc.bytes()));
  }

  @Test
  void renderReceipt_producesPdfWithTenderAndChange() {
    SalesInvoice inv = anInvoice();
    SalesOrder order = mock(SalesOrder.class);
    when(order.getOrderNumber()).thenReturn("SO-2026-000123");

    InvoiceView view = new InvoiceView(inv, List.of(aLine()));
    InvoiceAdminService.OrderInvoices oi =
        new InvoiceAdminService.OrderInvoices(order, List.of(view));
    when(invoiceAdminService.listForOrder(ORG, ORDER)).thenReturn(oi);

    Payment payment = mock(Payment.class);
    when(payment.getAmount()).thenReturn(new BigDecimal("150.00")); // tender > 120 grand total
    PaymentWithRefunds pwr = new PaymentWithRefunds(payment, List.of());
    PaymentService.OrderPayments op = new PaymentService.OrderPayments(order, List.of(pwr));
    when(paymentService.listForOrder(ORG, ORDER)).thenReturn(op);

    when(orgService.getById(ORG)).thenReturn(orgWithProfile());

    RenderedDocument doc = svc.renderReceipt(ORG, ORDER);

    assertTrue(doc.filename().endsWith(".pdf"));
    assertTrue(isPdf(doc.bytes()));
  }
}
