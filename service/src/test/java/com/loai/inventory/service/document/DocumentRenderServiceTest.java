package com.loai.inventory.service.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteLine;
import com.loai.inventory.domain.model.CreditNoteStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.RefundStatus;
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
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the PDF renderer: valid aggregates render non-empty {@code %PDF} bytes with a
 * filename derived from the document number, and an org with no billing profile still renders.
 */
class DocumentRenderServiceTest {

  static {
    // The runtime image has no display: the raster slip must paint under the same flag it ships
    // with (Dockerfile JAVA_OPTS), or a fontconfig/X11 lookup slips through CI and fails in prod.
    System.setProperty("java.awt.headless", "true");
  }

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
                order, List.of(new PaymentWithRefunds(payment, null, List.of()))));
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
    // A desk-issued note carries no discount share and prints exactly as before (V89).
    String text = pdfText(doc.bytes());
    assertFalse(text.contains("Discount"), text);
    assertTrue(text.contains("Credit total"), text);
  }

  /**
   * V89: a counter return's note credits gross lines against a net invoice — the discount share is
   * printed so the credit total is explained: 100 gross, 10 share, 90 credited.
   */
  @Test
  void renderCreditNote_printsDiscountRowWhenPresent() {
    UUID cnId = UUID.randomUUID();
    CreditNote cn = mock(CreditNote.class);
    when(cn.getSalesInvoiceId()).thenReturn(INVOICE);
    when(cn.getCreditNoteNumber()).thenReturn("CN-2026-0004");
    when(cn.getStatus()).thenReturn(CreditNoteStatus.SETTLED);
    when(cn.getSubtotal()).thenReturn(new BigDecimal("100.00"));
    when(cn.getTaxTotal()).thenReturn(BigDecimal.ZERO);
    when(cn.getDiscountTotal()).thenReturn(new BigDecimal("10.00"));
    when(cn.getTotal()).thenReturn(new BigDecimal("90.00"));
    when(cn.getCurrency()).thenReturn("EGP");
    when(cn.getIssuedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC));
    when(creditNoteService.get(ORG, cnId))
        .thenReturn(new CreditNoteService.Detail(cn, List.of(), BigDecimal.ZERO));
    when(invoiceAdminService.get(ORG, INVOICE))
        .thenReturn(new InvoiceView(anInvoice(), List.of(aLine())));
    when(orgService.getById(ORG)).thenReturn(orgWithProfile());

    String text = pdfText(svc.renderCreditNote(ORG, cnId).bytes());

    assertTrue(text.contains("Discount"), text);
    assertTrue(text.contains("10.00"), text);
    assertTrue(text.contains("Credit total"), text);
    assertTrue(text.contains("90.00"), text);
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
    PaymentWithRefunds pwr = new PaymentWithRefunds(payment, null, List.of());
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
    // A single tender prints no per-provider lines (stories/split_tender.md keeps it
    // byte-identical).
    assertFalse(text.contains("InstaPay"), text);
    assertFalse(text.contains("Cash"), text);
  }

  /**
   * A split sale ({@code stories/split_tender.md}) prints one line per tender — {@code InstaPay}
   * then {@code Cash}, in the ledger's order — above the {@code Tendered} sum.
   */
  @Test
  void renderReceipt_printsOneLinePerTenderWhenSplit() {
    SalesInvoice inv = anInvoice();
    SalesOrder order = mock(SalesOrder.class);
    when(order.getOrderNumber()).thenReturn("SO-2026-000124");
    when(invoiceAdminService.listForOrder(ORG, ORDER))
        .thenReturn(
            new InvoiceAdminService.OrderInvoices(
                order, List.of(new InvoiceView(inv, List.of(aLine())))));

    Payment transfer = mock(Payment.class);
    when(transfer.getAmount()).thenReturn(new BigDecimal("100.00"));
    com.loai.inventory.domain.model.PaymentTransaction transferTxn =
        mock(com.loai.inventory.domain.model.PaymentTransaction.class);
    when(transferTxn.getProvider())
        .thenReturn(com.loai.inventory.domain.model.PaymentProvider.INSTAPAY_IN_STORE);
    Payment notes = mock(Payment.class);
    when(notes.getAmount()).thenReturn(new BigDecimal("20.00"));
    com.loai.inventory.domain.model.PaymentTransaction notesTxn =
        mock(com.loai.inventory.domain.model.PaymentTransaction.class);
    when(notesTxn.getProvider()).thenReturn(com.loai.inventory.domain.model.PaymentProvider.CASH);
    when(paymentService.listForOrder(ORG, ORDER))
        .thenReturn(
            new PaymentService.OrderPayments(
                order,
                List.of(
                    new PaymentWithRefunds(transfer, transferTxn, List.of()),
                    new PaymentWithRefunds(notes, notesTxn, List.of()))));
    when(orgService.getById(ORG)).thenReturn(orgWithProfile());

    String text = pdfText(svc.renderReceipt(ORG, ORDER).bytes());
    assertTrue(text.contains("InstaPay"), text);
    assertTrue(text.contains("100.00"), text);
    assertTrue(text.contains("Cash"), text);
    assertTrue(text.contains("20.00"), text);
    assertTrue(text.contains("Tendered"), text);
    assertTrue(text.contains("120.00"), text);
    assertFalse(text.contains("Change"), text);
  }

  /**
   * The order number as a Code128 on the slip ({@code stories/counter_return.md}): the barcode is
   * drawn as filled bars into a form XObject — dozens of {@code re} rectangles that no text-only
   * receipt ever had — and the number still prints as text beside it.
   */
  @Test
  void renderReceipt_carriesOrderNumberBarcode() {
    SalesOrder order = mock(SalesOrder.class);
    when(order.getOrderNumber()).thenReturn("SO-2026-000417");
    stubReceipt(anInvoice(), order, "120.00");

    byte[] bytes = svc.renderReceipt(ORG, ORDER).bytes();

    assertTrue(pdfText(bytes).contains("SO-2026-000417"));
    int bars = filledRectangles(bytes);
    assertTrue(bars >= 20, "expected the Code128 bars, found " + bars + " rectangles");
  }

  /**
   * Filled-rectangle operators across the page and its XObjects — how a barcode's bars are drawn.
   */
  private static int filledRectangles(byte[] bytes) {
    try {
      com.lowagie.text.pdf.PdfReader reader = new com.lowagie.text.pdf.PdfReader(bytes);
      StringBuilder all = new StringBuilder();
      for (int page = 1; page <= reader.getNumberOfPages(); page++) {
        all.append(new String(reader.getPageContent(page), StandardCharsets.ISO_8859_1));
        collectXObjectStreams(
            reader.getPageN(page).getAsDict(com.lowagie.text.pdf.PdfName.RESOURCES), all);
      }
      // Barcode128 places every bar with its own `re` and fills once at the end, so count the
      // rectangle operators, not `re f` pairs.
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\sre\\s").matcher(all);
      int n = 0;
      while (m.find()) {
        n++;
      }
      return n;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("unreadable PDF", e);
    }
  }

  private static void collectXObjectStreams(
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
        out.append(
            new String(
                com.lowagie.text.pdf.PdfReader.getStreamBytes(stream),
                StandardCharsets.ISO_8859_1));
        collectXObjectStreams(stream.getAsDict(com.lowagie.text.pdf.PdfName.RESOURCES), out);
      }
    }
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
                order, List.of(new PaymentWithRefunds(payment, null, List.of()))));
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

  // ---- ESC/POS slip (stories/escpos_receipt.md) ----------------------------------------------

  private static SalesOrder anOrder(String number) {
    SalesOrder o = mock(SalesOrder.class);
    when(o.getOrderNumber()).thenReturn(number);
    return o;
  }

  private static SalesInvoiceLine aLine(String description) {
    return SalesInvoiceLine.create(
        UUID.randomUUID(),
        INVOICE,
        UUID.randomUUID(),
        description,
        2,
        new BigDecimal("50.00"),
        new BigDecimal("0.20"));
  }

  private void stubReceiptLines(
      SalesInvoice inv, SalesOrder order, String tender, List<SalesInvoiceLine> lines) {
    when(invoiceAdminService.listForOrder(ORG, ORDER))
        .thenReturn(
            new InvoiceAdminService.OrderInvoices(order, List.of(new InvoiceView(inv, lines))));
    Payment payment = mock(Payment.class);
    when(payment.getAmount()).thenReturn(new BigDecimal(tender));
    when(paymentService.listForOrder(ORG, ORDER))
        .thenReturn(
            new PaymentService.OrderPayments(
                order, List.of(new PaymentWithRefunds(payment, null, List.of()))));
    when(orgService.getById(ORG)).thenReturn(orgWithProfile());
  }

  private static List<String> labels(List<SlipModel.Row> rows) {
    return rows.stream().map(SlipModel.Row::label).toList();
  }

  @Test
  void renderReceiptEscpos_beginsWithInitAndEndsWithFeedAndCut() {
    stubReceipt(anInvoice(), anOrder("SO-2026-000201"), "150.00");

    RenderedDocument doc = svc.renderReceiptEscpos(ORG, ORDER, Escpos.WIDTH_80MM);

    assertEquals("SO-2026-000201.escpos", doc.filename());
    byte[] b = doc.bytes();
    assertArrayEquals(new byte[] {0x1B, 0x40}, Arrays.copyOfRange(b, 0, 2));
    assertArrayEquals(
        new byte[] {0x1B, 0x64, 0x04, 0x1D, 0x56, 0x42, 0x00},
        Arrays.copyOfRange(b, b.length - 7, b.length));
  }

  /** Every band is 72 bytes wide and 64 rows tall but the last; nothing sits between them. */
  @Test
  void renderReceiptEscpos_bandsTileTheSlipExactly() {
    stubReceipt(anInvoice(), anOrder("SO-2026-000202"), "150.00");

    byte[] b = svc.renderReceiptEscpos(ORG, ORDER, Escpos.WIDTH_80MM).bytes();

    EscposTestSupport.Parsed p = EscposTestSupport.parse(b);
    assertTrue(p.bands().size() >= 5, "a slip is several bands, got " + p.bands().size());
    int rows = 0;
    for (int i = 0; i < p.bands().size(); i++) {
      EscposTestSupport.Band band = p.bands().get(i);
      assertEquals(72, band.bytesPerRow());
      assertTrue(band.rows() > 0 && band.rows() <= 64, "band rows " + band.rows());
      if (i < p.bands().size() - 1) {
        assertEquals(64, band.rows(), "only the last band may be short");
      }
      rows += band.rows();
    }
    assertEquals(b.length - 7, p.tailOffset(), "nothing between the last band and feed+cut");
    assertTrue(rows > 300 && rows < 1400, "content-sized, not a page: " + rows + " rows");
  }

  @Test
  void renderReceiptEscpos_honoursWidth384() {
    stubReceipt(anInvoice(), anOrder("SO-2026-000203"), "150.00");

    byte[] b = svc.renderReceiptEscpos(ORG, ORDER, Escpos.WIDTH_58MM).bytes();

    for (EscposTestSupport.Band band : EscposTestSupport.parse(b).bands()) {
      assertEquals(48, band.bytesPerRow());
    }
  }

  /** The assertion Helvetica would fail: an Arabic title leaves ink, and the font knows it can. */
  @Test
  void renderReceiptEscpos_arabicTitleHasGlyphs() {
    assertEquals(-1, SlipFont.canDisplayUpTo("دفتر ملاحظات EGP 50.00"));
    assertNotEquals(
        -1,
        SlipFont.canDisplayUpTo("日本語"),
        "the guard is real: a script the font lacks is reported");

    stubReceiptLines(anInvoice(), anOrder("SO-1"), "150.00", List.of(aLine("دفتر ملاحظات")));
    int arabic =
        EscposTestSupport.ink(
            EscposTestSupport.bitmap(svc.renderReceiptEscpos(ORG, ORDER, 576).bytes()));
    stubReceiptLines(anInvoice(), anOrder("SO-1"), "150.00", List.of(aLine(".")));
    int dot =
        EscposTestSupport.ink(
            EscposTestSupport.bitmap(svc.renderReceiptEscpos(ORG, ORDER, 576).bytes()));

    assertTrue(arabic > dot + 300, "an Arabic title must leave ink: " + arabic + " vs " + dot);
  }

  /**
   * The layout owns the columns: an Arabic title is placed at the right edge by its own direction,
   * a Latin one at the left, and neither bleeds into the margins.
   */
  @Test
  void slipRaster_arabicTitleSitsRight_latinTitleSitsLeft() {
    assertTrue(SlipRaster.isRtl("قلم"));
    assertFalse(SlipRaster.isRtl("Pen"));
    assertFalse(SlipRaster.isRtl("EGP 450.00"));
    assertFalse(SlipRaster.isRtl(""));

    boolean[][] arabic =
        EscposTestSupport.bitmap(Escpos.encode(SlipRaster.paint(slip("قلم"), 576)));
    boolean[][] latin = EscposTestSupport.bitmap(Escpos.encode(SlipRaster.paint(slip("Pen"), 576)));

    assertTrue(hasRowEntirelyRightOfCentre(arabic), "the Arabic title row is flush right");
    assertFalse(hasRowEntirelyRightOfCentre(latin), "a Latin title row starts at the left margin");
    for (boolean[][] bitmap : List.of(arabic, latin)) {
      for (boolean[] row : bitmap) {
        int first = EscposTestSupport.firstInk(row);
        if (first >= 0) {
          assertTrue(first >= SlipRaster.MARGIN, "ink in the left margin at " + first);
          assertTrue(
              EscposTestSupport.lastInk(row) < 576 - SlipRaster.MARGIN, "ink in the right margin");
        }
      }
    }
  }

  private static boolean hasRowEntirelyRightOfCentre(boolean[][] bitmap) {
    for (boolean[] row : bitmap) {
      int first = EscposTestSupport.firstInk(row);
      if (first > row.length / 2) {
        return true;
      }
    }
    return false;
  }

  private static SlipModel slip(String title) {
    return new SlipModel(
        "Acme",
        List.of(),
        null,
        "RECEIPT",
        null,
        List.of(new SlipModel.Row("Order", "SO-1")),
        List.of(new SlipModel.Line(title, "1 x 10.00", "10.00 EGP")),
        List.of(new SlipModel.Row("Subtotal", "10.00 EGP")),
        new SlipModel.Row("TOTAL", "10.00 EGP"),
        List.of(),
        new SlipModel.Row("Tendered", "10.00 EGP"),
        null,
        null,
        "Thanks");
  }

  /** The order number's Code128 is drawn as bars: some row flips black/white dozens of times. */
  @Test
  void renderReceiptEscpos_barcodeRowsScanAsBars() {
    stubReceipt(anInvoice(), anOrder("SO-2026-000123"), "150.00");

    boolean[][] bitmap = EscposTestSupport.bitmap(svc.renderReceiptEscpos(ORG, ORDER, 576).bytes());

    int most = 0;
    for (boolean[] row : bitmap) {
      most = Math.max(most, EscposTestSupport.transitions(row));
    }
    assertTrue(most >= 40, "a barcode row has dozens of transitions, best row had " + most);
    byte[] modules = SlipRaster.barcodeModules("SO-2026-000123");
    assertTrue(modules.length > 30, "Code128 modules: " + modules.length);
  }

  /** The model — what both painters consume — carries a row only when the sale has the thing. */
  @Test
  void receiptModel_carriesTenderDiscountAndChangeRowsOnlyWhenTheyExist() {
    // A 10% counter discount on the 120.00 invoice, paid InstaPay 100 + cash 50 → change 40.
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
            new BigDecimal("10.00"),
            "EGP",
            "Nadia",
            "nadia@example.com",
            null,
            null,
            now);
    inv.issue("INV-2026-0009", now);
    SalesOrder order = anOrder("SO-2026-000300");
    when(order.getCounterDiscountType()).thenReturn(CouponType.PERCENT);
    when(order.getCounterDiscountValue()).thenReturn(new BigDecimal("10.00"));
    when(invoiceAdminService.listForOrder(ORG, ORDER))
        .thenReturn(
            new InvoiceAdminService.OrderInvoices(
                order, List.of(new InvoiceView(inv, List.of(aLine())))));
    Payment transfer = mock(Payment.class);
    when(transfer.getAmount()).thenReturn(new BigDecimal("100.00"));
    PaymentTransaction transferTxn = mock(PaymentTransaction.class);
    when(transferTxn.getProvider()).thenReturn(PaymentProvider.INSTAPAY_IN_STORE);
    Payment notes = mock(Payment.class);
    when(notes.getAmount()).thenReturn(new BigDecimal("50.00"));
    PaymentTransaction notesTxn = mock(PaymentTransaction.class);
    when(notesTxn.getProvider()).thenReturn(PaymentProvider.CASH);
    when(paymentService.listForOrder(ORG, ORDER))
        .thenReturn(
            new PaymentService.OrderPayments(
                order,
                List.of(
                    new PaymentWithRefunds(transfer, transferTxn, List.of()),
                    new PaymentWithRefunds(notes, notesTxn, List.of()))));
    when(orgService.getById(ORG)).thenReturn(orgWithProfile());

    SlipModel m = svc.receiptModel(ORG, ORDER);

    assertEquals("RECEIPT", m.title());
    assertEquals("SO-2026-000300", m.barcode());
    assertEquals("Acme Stationery LLC", m.name());
    assertEquals(List.of("Order", "Invoice", "Date", "Customer"), labels(m.meta()));
    assertEquals(List.of("Subtotal", "Tax", "Discount (10%)"), labels(m.totals()));
    assertEquals("-10.00 EGP", m.totals().get(2).value());
    assertEquals(new SlipModel.Row("TOTAL", money(inv.getGrandTotal()) + " EGP"), m.total());
    assertEquals(List.of("InstaPay", "Cash"), labels(m.tenders()));
    assertEquals("100.00 EGP", m.tenders().get(0).value());
    assertEquals(new SlipModel.Row("Tendered", "150.00 EGP"), m.tendered());
    assertEquals(
        new SlipModel.Row(
            "Change", money(new BigDecimal("150.00").subtract(inv.getGrandTotal())) + " EGP"),
        m.change());
    assertNull(m.settlement());
    assertEquals("Blue pen", m.lines().get(0).title());
    assertEquals("2 x 50.00", m.lines().get(0).qtyByPrice());

    // The plain sale: one exact tender, no discount → no tender rows, no discount, no change.
    stubReceipt(anInvoice(), anOrder("SO-2026-000301"), "120.00");
    SlipModel plain = svc.receiptModel(ORG, ORDER);
    assertEquals(List.of("Subtotal", "Tax"), labels(plain.totals()));
    assertTrue(plain.tenders().isEmpty());
    assertNull(plain.change());
    assertEquals(List.of("Order", "Invoice", "Date", "Customer"), labels(plain.meta()));
  }

  private static String money(BigDecimal b) {
    return b.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString();
  }

  /**
   * The return slip names the note, the original order (as barcode too), and how money went back.
   */
  @Test
  void returnSlipModel_namesTheOriginalOrderAndTheRefundMethod() {
    UUID cnId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    CreditNote cn = mock(CreditNote.class);
    when(cn.getStatus()).thenReturn(CreditNoteStatus.SETTLED);
    when(cn.getSalesInvoiceId()).thenReturn(INVOICE);
    when(cn.getCreditNoteNumber()).thenReturn("CN-2026-0003");
    when(cn.getIssuedAt()).thenReturn(now);
    when(cn.getSubtotal()).thenReturn(new BigDecimal("100.00"));
    when(cn.getTaxTotal()).thenReturn(BigDecimal.ZERO);
    when(cn.getDiscountTotal()).thenReturn(new BigDecimal("10.00"));
    when(cn.getTotal()).thenReturn(new BigDecimal("90.00"));
    when(cn.getCurrency()).thenReturn("EGP");
    CreditNoteLine line = mock(CreditNoteLine.class);
    when(line.getDescription()).thenReturn("دفتر");
    when(line.getQuantity()).thenReturn(1);
    when(line.getUnitPrice()).thenReturn(new BigDecimal("100.00"));
    when(line.getLineTotal()).thenReturn(new BigDecimal("100.00"));
    when(creditNoteService.get(ORG, cnId))
        .thenReturn(new CreditNoteService.Detail(cn, List.of(line), new BigDecimal("90.00")));
    SalesInvoice inv = mock(SalesInvoice.class);
    when(inv.getSalesOrderId()).thenReturn(orderId);
    when(inv.getInvoiceNumber()).thenReturn("INV-2026-0007");
    when(inv.getCustomerName()).thenReturn("Nadia");
    when(invoiceAdminService.get(ORG, INVOICE)).thenReturn(new InvoiceView(inv, List.of()));
    SalesOrder original = anOrder("SO-2026-000300");
    when(invoiceAdminService.listForOrder(ORG, orderId))
        .thenReturn(new InvoiceAdminService.OrderInvoices(original, List.of()));
    when(orgService.getById(ORG)).thenReturn(orgWithProfile());
    Refund cash = mock(Refund.class);
    when(cash.getStatus()).thenReturn(RefundStatus.EXECUTED);
    when(cash.getMethod()).thenReturn(PaymentProvider.CASH);
    when(creditNoteService.refundsFor(ORG, cnId)).thenReturn(List.of(cash));

    SlipModel m = svc.returnSlipModel(ORG, cnId);

    assertEquals("RETURN", m.title());
    assertEquals("SO-2026-000300", m.barcode());
    assertEquals(List.of("Credit note", "Order", "Invoice", "Date", "Customer"), labels(m.meta()));
    assertEquals("CN-2026-0003", m.meta().get(0).value());
    assertEquals("SO-2026-000300", m.meta().get(1).value());
    assertEquals(List.of("Subtotal", "Tax", "Discount"), labels(m.totals()));
    assertEquals("-10.00 EGP", m.totals().get(2).value());
    assertEquals(new SlipModel.Row("REFUND TOTAL", "90.00 EGP"), m.total());
    assertEquals(new SlipModel.Row("Refunded", "Cash handed back"), m.settlement());
    assertEquals("دفتر", m.lines().get(0).title());
    assertEquals("1 x 100.00", m.lines().get(0).qtyByPrice());
    assertNull(m.tendered());
    assertNull(m.change());
    assertTrue(m.tenders().isEmpty());

    // A cancelled row never speaks; the pending transfer after it does.
    Refund cancelled = mock(Refund.class);
    when(cancelled.getStatus()).thenReturn(RefundStatus.CANCELLED);
    Refund pending = mock(Refund.class);
    when(pending.getStatus()).thenReturn(RefundStatus.PENDING);
    when(creditNoteService.refundsFor(ORG, cnId)).thenReturn(List.of(cancelled, pending));
    assertEquals(
        new SlipModel.Row("Refund", "Pending transfer"),
        svc.returnSlipModel(ORG, cnId).settlement());

    // No refund yet → no row at all.
    when(creditNoteService.refundsFor(ORG, cnId)).thenReturn(List.of());
    assertNull(svc.returnSlipModel(ORG, cnId).settlement());

    RenderedDocument doc = svc.renderReturnSlipEscpos(ORG, cnId, Escpos.WIDTH_58MM);
    assertEquals("CN-2026-0003.escpos", doc.filename());
    for (EscposTestSupport.Band band : EscposTestSupport.parse(doc.bytes()).bands()) {
      assertEquals(48, band.bytesPerRow());
    }
  }

  @Test
  void returnSlip_draftNoteIs404() {
    UUID cnId = UUID.randomUUID();
    CreditNote cn = mock(CreditNote.class);
    when(cn.getStatus()).thenReturn(CreditNoteStatus.DRAFT);
    when(creditNoteService.get(ORG, cnId))
        .thenReturn(new CreditNoteService.Detail(cn, List.of(), BigDecimal.ZERO));

    assertThrows(NotFoundException.class, () -> svc.returnSlipModel(ORG, cnId));
    assertThrows(NotFoundException.class, () -> svc.renderReturnSlipEscpos(ORG, cnId, 576));
  }

  /** The slip paints under the flag the image ships with; the static block above set it. */
  @Test
  void slipPaintsHeadless() {
    assertTrue(GraphicsEnvironment.isHeadless(), "java.awt.headless must be in force");
    stubReceipt(anInvoice(), anOrder("SO-2026-000204"), "120.00");
    assertTrue(svc.renderReceiptEscpos(ORG, ORDER, 576).bytes().length > 1000);
  }

  /**
   * The transfer budget the frontend pair states honestly: a slip is tens of KB, never hundreds, at
   * either width. The numbers this prints are the ones recorded in {@code
   * tools/seed/results/escpos-slip-size.md}.
   */
  @Test
  void renderReceiptEscpos_sizeStaysWithinTheBleBudget() {
    for (int n : new int[] {1, 3, 10}) {
      List<SalesInvoiceLine> lines = new java.util.ArrayList<>();
      for (int i = 0; i < n; i++) {
        lines.add(aLine(i % 2 == 0 ? "دفتر ملاحظات A5 مسطر" : "Blue ballpoint pen 0.7"));
      }
      for (int width : new int[] {Escpos.WIDTH_80MM, Escpos.WIDTH_58MM}) {
        stubReceiptLines(anInvoice(), anOrder("SO-2026-000417"), "150.00", lines);
        byte[] b = svc.renderReceiptEscpos(ORG, ORDER, width).bytes();
        int rows = 0;
        for (EscposTestSupport.Band band : EscposTestSupport.parse(b).bands()) {
          rows += band.rows();
        }
        System.out.printf(
            "escpos-size lines=%d width=%d rows=%d bytes=%d%n", n, width, rows, b.length);
        assertTrue(b.length < 120_000, n + " lines at " + width + " → " + b.length + " bytes");
      }
    }
  }
}
