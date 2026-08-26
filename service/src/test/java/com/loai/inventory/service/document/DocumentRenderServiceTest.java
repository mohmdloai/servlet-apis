package com.loai.inventory.service.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.domain.model.CouponType;
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
            BigDecimal.ZERO, // shipping
            BigDecimal.ZERO, // discount
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

  /**
   * Every string literal the document draws, one per line, across all pages and their XObjects —
   * for asserting on what a slip actually says (labels, amounts). OpenPDF writes standard-font text
   * as plain {@code (…) Tj} operands and renders table cells into form XObjects, so scanning the
   * decoded streams is both simpler and more complete than the library's legacy text extractor
   * (which recovered two lines of a receipt).
   */
  private static String pdfText(byte[] bytes) {
    try {
      com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(bytes);
      StringBuilder out = new StringBuilder();
      for (int page = 1; page <= reader.getNumberOfPages(); page++) {
        collectLiterals(reader.getPageContent(page), out);
        collectXObjectLiterals(
            reader.getPageN(page).getAsDict(com.lowagie.text.pdf.PdfName.RESOURCES), out);
      }
      return out.toString();
    } catch (java.io.IOException e) {
      throw new IllegalStateException("unreadable PDF", e);
    }
  }

  private static void collectXObjectLiterals(
      com.lowagie.text.pdf.PdfDictionary resources, StringBuilder out) throws java.io.IOException {
    if (resources == null) {
      return;
    }
    com.lowagie.text.pdf.PdfDictionary xobjects =
        resources.getAsDict(com.lowagie.text.pdf.PdfName.XOBJECT);
    if (xobjects == null) {
      return;
    }
    for (Object key : xobjects.getKeys()) {
      com.lowagie.text.pdf.PdfObject o =
          com.lowagie.text.pdf.PdfReader.getPdfObject(
              xobjects.get((com.lowagie.text.pdf.PdfName) key));
      if (o instanceof com.lowagie.text.pdf.PRStream stream) {
        collectLiterals(com.lowagie.text.pdf.PdfReader.getStreamBytes(stream), out);
        collectXObjectLiterals(stream.getAsDict(com.lowagie.text.pdf.PdfName.RESOURCES), out);
      }
    }
  }

  private static final java.util.regex.Pattern LITERAL =
      java.util.regex.Pattern.compile("\\((?:\\\\.|[^\\\\)])*\\)");

  private static void collectLiterals(byte[] content, StringBuilder out) {
    java.util.regex.Matcher m = LITERAL.matcher(new String(content, StandardCharsets.ISO_8859_1));
    while (m.find()) {
      String lit = m.group();
      out.append(
              lit.substring(1, lit.length() - 1)
                  .replace("\\(", "(")
                  .replace("\\)", ")")
                  .replace("\\\\", "\\"))
          .append('\n');
    }
  }

  /**
   * An invoice like {@link #anInvoice()} but carrying a discount: 100 + 20 tax − {@code discount}.
   */
  private static SalesInvoice aDiscountedInvoice(String discount) {
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
            new BigDecimal(discount),
            "EGP",
            "Nadia",
            "nadia@example.com",
            null,
            null,
            now);
    inv.issue("INV-2026-0008", now);
    return inv;
  }

  /**
   * Wires a receipt render for {@code inv} against {@code order} with a tender of {@code tender}.
   */
  private void stubReceipt(SalesInvoice inv, SalesOrder order, String tender) {
    InvoiceView view = new InvoiceView(inv, List.of(aLine()));
    when(invoiceAdminService.listForOrder(ORG, ORDER))
        .thenReturn(new InvoiceAdminService.OrderInvoices(order, List.of(view)));
    Payment payment = mock(Payment.class);
    when(payment.getAmount()).thenReturn(new BigDecimal(tender));
    when(paymentService.listForOrder(ORG, ORDER))
        .thenReturn(
            new PaymentService.OrderPayments(
                order, List.of(new PaymentWithRefunds(payment, List.of()))));
    when(orgService.getById(ORG)).thenReturn(orgWithProfile());
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
    // An undiscounted slip prints no Discount line at all — subtotal, tax, TOTAL, tendered, change.
    String text = pdfText(doc.bytes());
    assertFalse(text.contains("Discount"), text);
    assertTrue(text.contains("Tendered"), text);
    assertTrue(text.contains("Change"), text);
  }

  /**
   * V88: a discounted sale prints its discount between Tax and TOTAL, naming the intent — otherwise
   * the slip shows a subtotal and a total that disagree with nothing between them.
   */
  @Test
  void renderReceipt_printsDiscountLineWhenDiscounted() {
    SalesOrder order = mock(SalesOrder.class);
    when(order.getOrderNumber()).thenReturn("SO-2026-000124");
    when(order.getCounterDiscountType()).thenReturn(CouponType.PERCENT);
    when(order.getCounterDiscountValue()).thenReturn(new BigDecimal("20.00"));
    stubReceipt(aDiscountedInvoice("20.00"), order, "100.00");

    String text = pdfText(svc.renderReceipt(ORG, ORDER).bytes());

    assertTrue(text.contains("Discount (20%)"), text);
    assertTrue(text.contains("-20.00 EGP"), text);
    assertTrue(text.contains("100.00 EGP"), text); // the discounted TOTAL
  }

  /** A FIXED counter discount is a plain "Discount"; a redeemed code names the code. */
  @Test
  void renderReceipt_discountLabelNamesFixedPlainAndCouponByCode() {
    SalesOrder fixed = mock(SalesOrder.class);
    when(fixed.getOrderNumber()).thenReturn("SO-2026-000125");
    when(fixed.getCounterDiscountType()).thenReturn(CouponType.FIXED);
    when(fixed.getCounterDiscountValue()).thenReturn(new BigDecimal("15.00"));
    stubReceipt(aDiscountedInvoice("15.00"), fixed, "105.00");
    String fixedText = pdfText(svc.renderReceipt(ORG, ORDER).bytes());
    assertTrue(fixedText.contains("Discount"), fixedText);
    assertFalse(fixedText.contains("Discount ("), fixedText);
    assertTrue(fixedText.contains("-15.00 EGP"), fixedText);

    SalesOrder coupon = mock(SalesOrder.class);
    when(coupon.getOrderNumber()).thenReturn("SO-2026-000126");
    when(coupon.getCouponCode()).thenReturn("SAVE10");
    stubReceipt(aDiscountedInvoice("10.00"), coupon, "110.00");
    String couponText = pdfText(svc.renderReceipt(ORG, ORDER).bytes());
    assertTrue(couponText.contains("Discount (SAVE10)"), couponText);
  }

  // ---- letterhead logo (V51 logo_object_key via LogoSource) ------------------------------------

  /** A valid 1×1 red PNG — the smallest bytes OpenPDF's Image.getInstance can decode. */
  private static final byte[] ONE_PIXEL_PNG =
      java.util.Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAA"
                  + "AABJRU5ErkJggg==");

  private static Org orgWithLogo() {
    Org o = orgWithProfile();
    o.setLogoObjectKey(ORG + "/logo/abc-logo.png");
    return o;
  }

  @Test
  void renderInvoice_embedsTheLogoWhenTheSourceResolvesIt() {
    when(invoiceAdminService.get(ORG, INVOICE))
        .thenReturn(new InvoiceView(anInvoice(), List.of(aLine())));
    when(orgService.getById(ORG)).thenReturn(orgWithLogo());

    DocumentRenderService withLogo =
        new DocumentRenderService(
            orgService,
            invoiceAdminService,
            creditNoteService,
            paymentService,
            key -> ONE_PIXEL_PNG);
    RenderedDocument logoDoc = withLogo.renderInvoice(ORG, INVOICE);
    RenderedDocument textDoc = svc.renderInvoice(ORG, INVOICE); // default source resolves nothing

    assertTrue(isPdf(logoDoc.bytes()));
    // The embedded image stream makes the logo render strictly larger than the text-only one.
    assertTrue(
        logoDoc.bytes().length > textDoc.bytes().length,
        "logo render should carry an image stream");
  }

  @Test
  void renderInvoice_fallsBackToTextHeaderWhenTheLogoFetchFails() {
    when(invoiceAdminService.get(ORG, INVOICE))
        .thenReturn(new InvoiceView(anInvoice(), List.of(aLine())));
    when(orgService.getById(ORG)).thenReturn(orgWithLogo());

    DocumentRenderService broken =
        new DocumentRenderService(
            orgService,
            invoiceAdminService,
            creditNoteService,
            paymentService,
            key -> {
              throw new java.io.IOException("object store down");
            });

    RenderedDocument doc = broken.renderInvoice(ORG, INVOICE);

    assertEquals("INV-2026-0007.pdf", doc.filename());
    assertTrue(isPdf(doc.bytes()), "a broken logo must never break the document");
  }

  @Test
  void renderReceipt_embedsTheLogoAndStillRenders() {
    SalesInvoice inv = anInvoice();
    SalesOrder order = mock(SalesOrder.class);
    when(order.getOrderNumber()).thenReturn("SO-2026-000123");
    when(invoiceAdminService.listForOrder(ORG, ORDER))
        .thenReturn(
            new InvoiceAdminService.OrderInvoices(
                order, List.of(new InvoiceView(inv, List.of(aLine())))));
    Payment payment = mock(Payment.class);
    when(payment.getAmount()).thenReturn(new BigDecimal("150.00"));
    when(paymentService.listForOrder(ORG, ORDER))
        .thenReturn(
            new PaymentService.OrderPayments(
                order, List.of(new PaymentWithRefunds(payment, List.of()))));
    when(orgService.getById(ORG)).thenReturn(orgWithLogo());

    DocumentRenderService withLogo =
        new DocumentRenderService(
            orgService,
            invoiceAdminService,
            creditNoteService,
            paymentService,
            key -> ONE_PIXEL_PNG);

    RenderedDocument doc = withLogo.renderReceipt(ORG, ORDER);

    assertTrue(isPdf(doc.bytes()));
  }
}
