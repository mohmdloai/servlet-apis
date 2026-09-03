package com.loai.inventory.service.document;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteLine;
import com.loai.inventory.domain.model.CreditNoteStatus;
import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.PaymentProvider;
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
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders an already-frozen finance aggregate to a PDF document — the single, server-side renderer
 * behind the invoice / credit-note download and the 80mm in-store receipt (see {@code
 * stories/document_pdf_rendering.md}, Part B). It computes <em>nothing</em>: every figure is the
 * frozen decimal the aggregate already holds, merely formatted for print. The seller identity in
 * the header comes from the org billing profile (V51); an org with none set still renders (header
 * falls back to {@code org.name}).
 *
 * <p>The PDFs use OpenPDF with the built-in base-14 fonts (Latin/English, zero font assets); the
 * HTML-engine + embedded-Arabic-font upgrade is documented in the story and slots behind this same
 * class without changing callers. The 80 mm slip has a second output ({@code
 * stories/escpos_receipt.md}): the same {@link SlipModel} painted as an ESC/POS raster with the
 * bundled Arabic-capable {@link SlipFont}, for the counter's thermal printer.
 */
public final class DocumentRenderService {

  private static final Logger log = LoggerFactory.getLogger(DocumentRenderService.class);

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");
  private static final Color MUTED = new Color(0x66, 0x66, 0x66);
  private static final Color RULE = new Color(0xDD, 0xDD, 0xDD);

  /** Letterhead logo bounds (pt) — scaled to fit, aspect kept. */
  private static final float LOGO_MAX_W = 140f;

  private static final float LOGO_MAX_H = 48f;

  /** Receipt (80mm) logo bounds + the extra slip height reserved when a logo is set. */
  private static final float RECEIPT_LOGO_MAX_W = 100f;

  private static final float RECEIPT_LOGO_MAX_H = 40f;
  private static final float RECEIPT_LOGO_EXTRA_H = 48f;

  /**
   * Resolves an org's {@code logo_object_key} to raw image bytes (V51/V52 — the same key the
   * storefront renders). Returning {@code null} (or throwing) means "no logo" — the document falls
   * back to the text-only header; a broken logo must never break an invoice download.
   */
  @FunctionalInterface
  public interface LogoSource {
    byte[] fetch(String objectKey) throws Exception;
  }

  private final OrgService orgService;
  private final InvoiceAdminService invoiceAdminService;
  private final CreditNoteService creditNoteService;
  private final PaymentService paymentService;
  private final LogoSource logoSource;

  /** Text-only headers (no logo resolution) — kept for callers/tests that don't wire storage. */
  public DocumentRenderService(
      OrgService orgService,
      InvoiceAdminService invoiceAdminService,
      CreditNoteService creditNoteService,
      PaymentService paymentService) {
    this(orgService, invoiceAdminService, creditNoteService, paymentService, key -> null);
  }

  public DocumentRenderService(
      OrgService orgService,
      InvoiceAdminService invoiceAdminService,
      CreditNoteService creditNoteService,
      PaymentService paymentService,
      LogoSource logoSource) {
    this.orgService = orgService;
    this.invoiceAdminService = invoiceAdminService;
    this.creditNoteService = creditNoteService;
    this.paymentService = paymentService;
    this.logoSource = logoSource;
  }

  // ---- fonts (base-14, no assets) -------------------------------------------------------------

  private static Font f(String name, float size, Color color) {
    Font font = FontFactory.getFont(name, size);
    if (color != null) {
      font.setColor(color);
    }
    return font;
  }

  /** Height the receipt's order-number barcode adds: ~10 mm of bars plus breathing room. */
  private static final float RECEIPT_BARCODE_EXTRA_H = 40f;

  private static final Font TITLE = f(FontFactory.HELVETICA_BOLD, 18, null);
  private static final Font H2 = f(FontFactory.HELVETICA_BOLD, 12, null);
  private static final Font BODY = f(FontFactory.HELVETICA, 10, null);
  private static final Font BODY_BOLD = f(FontFactory.HELVETICA_BOLD, 10, null);
  private static final Font MUTED_BODY = f(FontFactory.HELVETICA, 9, MUTED);
  private static final Font TH = f(FontFactory.HELVETICA_BOLD, 9, MUTED);

  // ---- public API -----------------------------------------------------------------------------

  /** A rendered PDF plus the download filename derived from the document number. */
  public record RenderedDocument(String filename, byte[] bytes) {}

  /** A full-page (A4) printable invoice. */
  public RenderedDocument renderInvoice(UUID orgId, UUID invoiceId) {
    InvoiceView view = invoiceAdminService.get(orgId, invoiceId);
    Org org = orgService.getById(orgId);
    byte[] bytes =
        build(
            PageSize.A4,
            36,
            (doc, writer) -> {
              SalesInvoice inv = view.invoice();
              orgHeader(doc, org);
              documentTitle(
                  doc, "INVOICE", inv.getInvoiceNumber(), inv.getStatus().name(), inv.isVoid());
              keyValueBlock(
                  doc,
                  new String[][] {
                    {"Issued", fmtDate(inv.getIssuedAt())},
                    {"Status", inv.getStatus().name()},
                  });
              billTo(
                  doc,
                  inv.getCustomerName(),
                  inv.getCustomerEmail(),
                  inv.getCustomerPhone(),
                  inv.getCustomerAddress());
              lineItems(doc, toRows(view.lines()), inv.getCurrency());
              BigDecimal balance = nz(inv.getGrandTotal()).subtract(nz(inv.getPaidAmount()));
              totals(
                  doc,
                  inv.getCurrency(),
                  new String[][] {
                    {"Subtotal", money(inv.getSubtotal())},
                    {"Tax", money(inv.getTaxTotal())},
                    {"Shipping", money(inv.getShippingTotal())},
                    {"Discount", money(inv.getDiscountTotal())},
                    {"Grand total", money(inv.getGrandTotal())},
                    {"Paid", money(inv.getPaidAmount())},
                    {"Balance", money(balance)},
                  },
                  4 // grand-total row index (0-based) — emphasised
                  );
            });
    return new RenderedDocument(fileName(view.invoice().getInvoiceNumber(), "invoice"), bytes);
  }

  /** A full-page (A4) printable credit note. */
  public RenderedDocument renderCreditNote(UUID orgId, UUID creditNoteId) {
    CreditNoteService.Detail detail = creditNoteService.get(orgId, creditNoteId);
    CreditNote cn = detail.creditNote();
    Org org = orgService.getById(orgId);
    // The credit note has no customer snapshot of its own — borrow the referenced invoice's.
    InvoiceView inv = invoiceAdminService.get(orgId, cn.getSalesInvoiceId());
    byte[] bytes =
        build(
            PageSize.A4,
            36,
            (doc, writer) -> {
              orgHeader(doc, org);
              documentTitle(
                  doc,
                  "CREDIT NOTE",
                  cn.getCreditNoteNumber(),
                  cn.getStatus().name(),
                  cn.getStatus().name().equals("VOID"));
              keyValueBlock(
                  doc,
                  new String[][] {
                    {"Issued", fmtDate(cn.getIssuedAt())},
                    {"Against invoice", inv.invoice().getInvoiceNumber()},
                    {"Reason", cn.getReason() == null ? "-" : cn.getReason().name()},
                    {"Note", cn.getReasonNote() == null ? "-" : cn.getReasonNote()},
                  });
              billTo(
                  doc,
                  inv.invoice().getCustomerName(),
                  inv.invoice().getCustomerEmail(),
                  inv.invoice().getCustomerPhone(),
                  inv.invoice().getCustomerAddress());
              lineItems(doc, toCnRows(detail.lines()), cn.getCurrency());
              // V89: a counter return's note credits gross lines against a net invoice — the
              // discount share is what brings the credit total down to what was paid. Only when
              // there is one; a desk-issued note prints exactly as before.
              boolean discounted = nz(cn.getDiscountTotal()).signum() > 0;
              String[][] cnRows =
                  discounted
                      ? new String[][] {
                        {"Subtotal", money(cn.getSubtotal())},
                        {"Tax", money(cn.getTaxTotal())},
                        {"Discount", money(cn.getDiscountTotal())},
                        {"Credit total", money(cn.getTotal())},
                      }
                      : new String[][] {
                        {"Subtotal", money(cn.getSubtotal())},
                        {"Tax", money(cn.getTaxTotal())},
                        {"Credit total", money(cn.getTotal())},
                      };
              totals(doc, cn.getCurrency(), cnRows, discounted ? 3 : 2);
            });
    return new RenderedDocument(fileName(cn.getCreditNoteNumber(), "credit-note"), bytes);
  }

  // ---- the 80 mm slip: one model, two painters (stories/escpos_receipt.md) -------------------

  /**
   * The in-store receipt's content, decided once — what the PDF and the ESC/POS raster both print:
   * seller header, the order number as barcode, Order / Invoice / Date / Customer, one line per
   * invoice line, Subtotal / Tax / Discount-when-there-is-one, TOTAL, a row per tender on a split
   * sale, Tendered, Change-when-positive. 404 when the order has no issued invoice.
   */
  public SlipModel receiptModel(UUID orgId, UUID salesOrderId) {
    InvoiceAdminService.OrderInvoices oi = invoiceAdminService.listForOrder(orgId, salesOrderId);
    SalesOrder order = oi.order();
    InvoiceView live =
        oi.invoices().stream()
            .filter(v -> v.invoice().getStatus() != InvoiceStatus.VOID)
            .findFirst()
            .orElseThrow(
                () -> new NotFoundException("no issued invoice for order " + salesOrderId));
    SalesInvoice inv = live.invoice();
    String cur = inv.getCurrency();

    List<PaymentService.PaymentWithRefunds> tenders =
        paymentService.listForOrder(orgId, salesOrderId).payments();
    BigDecimal tender = BigDecimal.ZERO;
    for (PaymentService.PaymentWithRefunds pw : tenders) {
      tender = tender.add(nz(pw.payment().getAmount()));
    }
    BigDecimal change = tender.subtract(nz(inv.getGrandTotal()));
    Org org = org(orgId);

    List<SlipModel.Row> meta = new ArrayList<>();
    meta.add(new SlipModel.Row("Order", order.getOrderNumber()));
    meta.add(new SlipModel.Row("Invoice", inv.getInvoiceNumber()));
    meta.add(new SlipModel.Row("Date", fmtDate(inv.getIssuedAt())));
    if (!blank(inv.getCustomerName())) {
      meta.add(new SlipModel.Row("Customer", inv.getCustomerName()));
    }

    List<SlipModel.Line> lines =
        live.lines().stream()
            .map(
                l ->
                    new SlipModel.Line(
                        l.getDescription(),
                        l.getQuantity() + " x " + money(l.getUnitPrice()),
                        money(l.getLineTotal()) + " " + cur))
            .toList();

    List<SlipModel.Row> totals = new ArrayList<>();
    totals.add(new SlipModel.Row("Subtotal", money(inv.getSubtotal()) + " " + cur));
    totals.add(new SlipModel.Row("Tax", money(inv.getTaxTotal()) + " " + cur));
    // Only when there is one: until the counter discount (V88) this was always zero and the slip
    // printed nothing — a discounted receipt would otherwise show a subtotal and a total that
    // disagree with no line between them.
    if (nz(inv.getDiscountTotal()).signum() > 0) {
      totals.add(
          new SlipModel.Row(discountLabel(order), "-" + money(inv.getDiscountTotal()) + " " + cur));
    }

    // A split sale (stories/split_tender.md) prints one line per tender above the Tendered sum;
    // a single-tender slip carries none, so it stays byte-identical to before.
    List<SlipModel.Row> tenderRows = new ArrayList<>();
    if (tenders.size() > 1) {
      for (PaymentService.PaymentWithRefunds pw : tenders) {
        tenderRows.add(
            new SlipModel.Row(
                tenderLabel(pw.transaction().getProvider()),
                money(pw.payment().getAmount()) + " " + cur));
      }
    }

    return new SlipModel(
        headerName(org),
        orgAddressLines(org),
        logoBytes(org),
        "RECEIPT",
        order.getOrderNumber(),
        meta,
        lines,
        totals,
        new SlipModel.Row("TOTAL", money(inv.getGrandTotal()) + " " + cur),
        tenderRows,
        new SlipModel.Row("Tendered", money(tender) + " " + cur),
        change.signum() > 0 ? new SlipModel.Row("Change", money(change) + " " + cur) : null,
        null,
        "Thank you!");
  }

  /** An 80mm thermal receipt for a sale as a PDF (framed for in-store; carries tender + change). */
  public RenderedDocument renderReceipt(UUID orgId, UUID salesOrderId) {
    SlipModel m = receiptModel(orgId, salesOrderId);
    Image receiptLogo = pdfLogo(m.logo(), RECEIPT_LOGO_MAX_W, RECEIPT_LOGO_MAX_H);

    // 80mm ≈ 226.77 pt wide; height sized to content so the slip isn't mostly blank. A discounted
    // sale draws one more totals row (V88), so it gets one more row of height; a split sale one
    // per tender.
    float width = 226.77f;
    float height =
        220f
            + m.lines().size() * 16f
            + (m.totals().size() - 2) * 13f
            + m.tenders().size() * 13f
            + RECEIPT_BARCODE_EXTRA_H
            + (receiptLogo == null ? 0f : RECEIPT_LOGO_EXTRA_H);
    byte[] bytes =
        build(
            new Rectangle(width, height),
            10,
            (doc, writer) -> {
              if (receiptLogo != null) {
                receiptLogo.setAlignment(Element.ALIGN_CENTER);
                doc.add(receiptLogo);
              }
              receiptCenter(doc, m.name(), H2);
              for (String line : m.addressLines()) {
                receiptCenter(doc, line, MUTED_BODY);
              }
              receiptRule(doc);
              receiptCenter(doc, m.title(), BODY_BOLD);
              // The order number as a Code128 (stories/counter_return.md): the admin scanner
              // reads it back to find the sale for a return, instead of a cashier typing
              // SO-2026-00417 correctly while the customer waits. The number is still printed as
              // text on the next line, so the barcode carries no caption of its own.
              doc.add(orderNumberBarcode(writer, m.barcode()));
              for (SlipModel.Row r : m.meta()) {
                receiptLine(doc, r.label(), r.value());
              }
              receiptRule(doc);
              for (SlipModel.Line l : m.lines()) {
                doc.add(new Paragraph(l.title(), BODY));
                receiptLine(doc, l.qtyByPrice(), l.amount());
              }
              receiptRule(doc);
              for (SlipModel.Row r : m.totals()) {
                receiptLine(doc, r.label(), r.value());
              }
              receiptTotal(doc, m.total().label(), m.total().value());
              for (SlipModel.Row r : m.tenders()) {
                receiptLine(doc, r.label(), r.value());
              }
              if (m.tendered() != null) {
                receiptLine(doc, m.tendered().label(), m.tendered().value());
              }
              if (m.change() != null) {
                receiptLine(doc, m.change().label(), m.change().value());
              }
              receiptRule(doc);
              receiptCenter(doc, m.footer(), MUTED_BODY);
            });
    return new RenderedDocument(fileName(m.barcode(), "receipt", ".pdf"), bytes);
  }

  /**
   * The same receipt as ESC/POS bytes for a thermal printer {@code width} dots wide ({@link
   * Escpos#WIDTH_80MM} / {@link Escpos#WIDTH_58MM}): the slip painted as a raster, in bands, with
   * init, feed and cut — and no drawer kick, which is the client's call.
   */
  public RenderedDocument renderReceiptEscpos(UUID orgId, UUID salesOrderId, int width) {
    SlipModel m = receiptModel(orgId, salesOrderId);
    byte[] bytes = Escpos.encode(SlipRaster.paint(m, width));
    return new RenderedDocument(fileName(m.barcode(), "receipt", ".escpos"), bytes);
  }

  /**
   * The counter return's slip ({@code stories/counter_return.md} refund, printed): the note and the
   * original order (as barcode again — the next return scans it too), the returned lines, Subtotal
   * / Tax / the discount share when the sale was discounted, REFUND TOTAL, and how the money went
   * back. 404 for a DRAFT note — a slip is only for an issued one.
   */
  public SlipModel returnSlipModel(UUID orgId, UUID creditNoteId) {
    return returnSlip(orgId, creditNoteId).model();
  }

  /** The return slip as ESC/POS bytes; see {@link #renderReceiptEscpos}. */
  public RenderedDocument renderReturnSlipEscpos(UUID orgId, UUID creditNoteId, int width) {
    ReturnSlip slip = returnSlip(orgId, creditNoteId);
    byte[] bytes = Escpos.encode(SlipRaster.paint(slip.model(), width));
    return new RenderedDocument(fileName(slip.number(), "return", ".escpos"), bytes);
  }

  private record ReturnSlip(String number, SlipModel model) {}

  private ReturnSlip returnSlip(UUID orgId, UUID creditNoteId) {
    CreditNoteService.Detail detail = creditNoteService.get(orgId, creditNoteId);
    CreditNote cn = detail.creditNote();
    if (cn.getStatus() == CreditNoteStatus.DRAFT) {
      throw new NotFoundException("credit note not issued: " + creditNoteId);
    }
    InvoiceView inv = invoiceAdminService.get(orgId, cn.getSalesInvoiceId());
    SalesOrder order =
        invoiceAdminService.listForOrder(orgId, inv.invoice().getSalesOrderId()).order();
    Org org = org(orgId);
    String cur = cn.getCurrency();

    List<SlipModel.Row> meta = new ArrayList<>();
    meta.add(new SlipModel.Row("Credit note", cn.getCreditNoteNumber()));
    meta.add(new SlipModel.Row("Order", order.getOrderNumber()));
    meta.add(new SlipModel.Row("Invoice", inv.invoice().getInvoiceNumber()));
    meta.add(new SlipModel.Row("Date", fmtDate(cn.getIssuedAt())));
    if (!blank(inv.invoice().getCustomerName())) {
      meta.add(new SlipModel.Row("Customer", inv.invoice().getCustomerName()));
    }

    List<SlipModel.Line> lines =
        detail.lines().stream()
            .map(
                l ->
                    new SlipModel.Line(
                        l.getDescription(),
                        l.getQuantity() + " x " + money(l.getUnitPrice()),
                        money(l.getLineTotal()) + " " + cur))
            .toList();

    List<SlipModel.Row> totals = new ArrayList<>();
    totals.add(new SlipModel.Row("Subtotal", money(cn.getSubtotal()) + " " + cur));
    totals.add(new SlipModel.Row("Tax", money(cn.getTaxTotal()) + " " + cur));
    // V89: a counter return credits gross lines against a net invoice — the discount share is what
    // brings the refund down to what was paid. Only when there is one.
    if (nz(cn.getDiscountTotal()).signum() > 0) {
      totals.add(new SlipModel.Row("Discount", "-" + money(cn.getDiscountTotal()) + " " + cur));
    }

    SlipModel model =
        new SlipModel(
            headerName(org),
            orgAddressLines(org),
            logoBytes(org),
            cn.getStatus() == CreditNoteStatus.VOID ? "RETURN (VOID)" : "RETURN",
            order.getOrderNumber(),
            meta,
            lines,
            totals,
            new SlipModel.Row("REFUND TOTAL", money(cn.getTotal()) + " " + cur),
            List.of(),
            null,
            null,
            settlement(creditNoteService.refundsFor(orgId, creditNoteId)),
            "Thank you!");
    return new ReturnSlip(cn.getCreditNoteNumber(), model);
  }

  /**
   * How the refund went back, from the note's first live refund row: cash handed over at the
   * counter, a transfer already sent, or one still pending ({@code stories/counter_return.md}'s two
   * paths). A cancelled row never speaks; a note with no refund prints no row.
   */
  private static SlipModel.Row settlement(List<Refund> refunds) {
    for (Refund r : refunds) {
      if (r.getStatus() == RefundStatus.CANCELLED) {
        continue;
      }
      if (r.getStatus() == RefundStatus.EXECUTED) {
        return new SlipModel.Row(
            "Refunded",
            r.getMethod() == PaymentProvider.CASH ? "Cash handed back" : "InstaPay transfer");
      }
      return new SlipModel.Row("Refund", "Pending transfer");
    }
    return null;
  }

  private Org org(UUID orgId) {
    return orgService.getById(orgId);
  }

  /** The tender's name on the slip: {@code Cash} / {@code InstaPay}. */
  private static String tenderLabel(PaymentProvider provider) {
    return switch (provider) {
      case CASH -> "Cash";
      case INSTAPAY_IN_STORE, INSTAPAY_MANUAL -> "InstaPay";
    };
  }

  /** A safe download filename from a document number: {@code INV-2026-0007.pdf}. */
  private static String fileName(String number, String fallback) {
    return fileName(number, fallback, ".pdf");
  }

  /** The same, with the extension the body actually is ({@code .pdf} / {@code .escpos}). */
  private static String fileName(String number, String fallback, String ext) {
    String base = blank(number) ? fallback : number.replaceAll("[^A-Za-z0-9._-]", "_");
    return base + ext;
  }

  // ---- shared building blocks -----------------------------------------------------------------

  private interface Body {
    void render(Document doc, PdfWriter writer) throws DocumentException;
  }

  private static byte[] build(Rectangle pageSize, float margin, Body body) {
    Document doc = new Document(pageSize, margin, margin, margin, margin);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      PdfWriter writer = PdfWriter.getInstance(doc, out);
      doc.open();
      body.render(doc, writer);
      doc.close();
    } catch (DocumentException e) {
      throw new IllegalStateException("Failed to render PDF document", e);
    }
    return out.toByteArray();
  }

  private void orgHeader(Document doc, Org org) throws DocumentException {
    Image logo = logoImage(org, LOGO_MAX_W, LOGO_MAX_H);
    if (logo != null) {
      doc.add(logo);
      doc.add(spacer(4));
    }
    Paragraph name = new Paragraph(headerName(org), H2);
    doc.add(name);
    for (String line : orgAddressLines(org)) {
      Paragraph p = new Paragraph(line, MUTED_BODY);
      doc.add(p);
    }
    doc.add(spacer(8));
  }

  /**
   * The org's logo as a print-ready {@link Image} scaled into the given bounds, or {@code null}
   * when the org has none or the bytes can't be fetched/decoded — the header then falls back to
   * text only. Never throws: a broken logo must not break a finance document download.
   */
  private Image logoImage(Org org, float maxWidth, float maxHeight) {
    return pdfLogo(logoBytes(org), maxWidth, maxHeight);
  }

  /** The raw logo bytes, or {@code null} when the org has none or storage can't produce them. */
  private byte[] logoBytes(Org org) {
    String key = org.getLogoObjectKey();
    if (blank(key)) {
      return null;
    }
    try {
      byte[] bytes = logoSource.fetch(key);
      return bytes == null || bytes.length == 0 ? null : bytes;
    } catch (Exception e) {
      log.warn("Logo unavailable for document header (key={}): {}", key, e.toString());
      return null;
    }
  }

  /** {@code bytes} as an OpenPDF image scaled to fit, or {@code null} when they don't decode. */
  private static Image pdfLogo(byte[] bytes, float maxWidth, float maxHeight) {
    if (bytes == null) {
      return null;
    }
    try {
      Image img = Image.getInstance(bytes);
      img.scaleToFit(maxWidth, maxHeight);
      return img;
    } catch (Exception e) {
      log.warn("Logo not decodable for document header: {}", e.toString());
      return null;
    }
  }

  private static String headerName(Org org) {
    String legal = blank(org.getLegalName()) ? org.getName() : org.getLegalName();
    return legal == null ? "" : legal;
  }

  private static List<String> orgAddressLines(Org org) {
    java.util.ArrayList<String> lines = new java.util.ArrayList<>();
    addr(lines, org.getAddressLine1());
    addr(lines, org.getAddressLine2());
    String cityCountry = join(", ", org.getCity(), org.getCountry());
    addr(lines, cityCountry);
    if (!blank(org.getTaxRegistrationNumber())) {
      lines.add("Tax reg. no: " + org.getTaxRegistrationNumber());
    }
    addr(lines, join(" · ", org.getPhone(), org.getContactEmail()));
    return lines;
  }

  private static void addr(List<String> lines, String s) {
    if (!blank(s)) {
      lines.add(s);
    }
  }

  private static void documentTitle(
      Document doc, String type, String number, String status, boolean voided)
      throws DocumentException {
    Paragraph title = new Paragraph(type + "  " + (number == null ? "" : number), TITLE);
    doc.add(title);
    if (voided) {
      Paragraph v = new Paragraph("VOID", f(FontFactory.HELVETICA_BOLD, 12, new Color(0xB0, 0, 0)));
      doc.add(v);
    }
    doc.add(spacer(6));
  }

  private static void keyValueBlock(Document doc, String[][] rows) throws DocumentException {
    for (String[] kv : rows) {
      Paragraph p = new Paragraph();
      p.add(new Phrase(kv[0] + ": ", MUTED_BODY));
      p.add(new Phrase(kv[1] == null ? "-" : kv[1], BODY));
      doc.add(p);
    }
    doc.add(spacer(8));
  }

  private static void billTo(Document doc, String name, String email, String phone, String address)
      throws DocumentException {
    doc.add(new Paragraph("Billed to", TH));
    doc.add(new Paragraph(blank(name) ? "Walk-in customer" : name, BODY));
    for (String s : new String[] {email, phone, address}) {
      if (!blank(s)) {
        doc.add(new Paragraph(s, MUTED_BODY));
      }
    }
    doc.add(spacer(10));
  }

  private record Row(
      String description,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal lineTax,
      BigDecimal lineTotal) {}

  private static List<Row> toRows(List<SalesInvoiceLine> lines) {
    return lines.stream()
        .map(
            l ->
                new Row(
                    l.getDescription(),
                    l.getQuantity(),
                    l.getUnitPrice(),
                    l.getLineTax(),
                    l.getLineTotal()))
        .toList();
  }

  private static List<Row> toCnRows(List<CreditNoteLine> lines) {
    return lines.stream()
        .map(
            l ->
                new Row(
                    l.getDescription(),
                    l.getQuantity(),
                    l.getUnitPrice(),
                    l.getLineTax(),
                    l.getLineTotal()))
        .toList();
  }

  private static void lineItems(Document doc, List<Row> rows, String currency)
      throws DocumentException {
    PdfPTable table = new PdfPTable(new float[] {5f, 1f, 2f, 2f, 2f});
    table.setWidthPercentage(100);
    table.setSpacingBefore(2);
    th(table, "Description", Element.ALIGN_LEFT);
    th(table, "Qty", Element.ALIGN_RIGHT);
    th(table, "Unit", Element.ALIGN_RIGHT);
    th(table, "Tax", Element.ALIGN_RIGHT);
    th(table, "Amount", Element.ALIGN_RIGHT);
    for (Row r : rows) {
      td(table, r.description(), Element.ALIGN_LEFT);
      td(table, String.valueOf(r.quantity()), Element.ALIGN_RIGHT);
      td(table, money(r.unitPrice()), Element.ALIGN_RIGHT);
      td(table, money(r.lineTax()), Element.ALIGN_RIGHT);
      td(table, money(r.lineTotal()), Element.ALIGN_RIGHT);
    }
    doc.add(table);
    doc.add(spacer(6));
    doc.add(new Paragraph("All amounts in " + currency + ".", MUTED_BODY));
    doc.add(spacer(6));
  }

  private static void totals(Document doc, String currency, String[][] rows, int emphasisRow)
      throws DocumentException {
    PdfPTable table = new PdfPTable(new float[] {3f, 2f});
    table.setWidthPercentage(45);
    table.setHorizontalAlignment(Element.ALIGN_RIGHT);
    for (int i = 0; i < rows.length; i++) {
      Font font = i == emphasisRow ? BODY_BOLD : BODY;
      totalCell(table, rows[i][0], Element.ALIGN_LEFT, font);
      totalCell(table, rows[i][1] + " " + currency, Element.ALIGN_RIGHT, font);
    }
    doc.add(table);
  }

  // ---- receipt (80mm) helpers -----------------------------------------------------------------

  private static void receiptCenter(Document doc, String text, Font font) throws DocumentException {
    Paragraph p = new Paragraph(text, font);
    p.setAlignment(Element.ALIGN_CENTER);
    doc.add(p);
  }

  /**
   * The receipt's discount label names the intent when the order carries one: {@code Discount
   * (10%)} for a PERCENT counter discount, {@code Discount (SAVE10)} for a redeemed code, plain
   * {@code Discount} for a FIXED amount (the amount beside it already says everything).
   */
  private static String discountLabel(SalesOrder order) {
    if (order.getCounterDiscountType() == CouponType.PERCENT
        && order.getCounterDiscountValue() != null) {
      return "Discount ("
          + order.getCounterDiscountValue().stripTrailingZeros().toPlainString()
          + "%)";
    }
    if (!blank(order.getCouponCode())) {
      return "Discount (" + order.getCouponCode() + ")";
    }
    return "Discount";
  }

  /** A captionless Code128 of the order number, centred, ~10 mm tall, for the 80 mm slip. */
  private static Image orderNumberBarcode(PdfWriter writer, String orderNumber) {
    Barcode128 code = new Barcode128();
    code.setCode(orderNumber);
    code.setBarHeight(28f);
    code.setFont(null);
    Image img = code.createImageWithBarcode(writer.getDirectContent(), null, null);
    img.setAlignment(Element.ALIGN_CENTER);
    return img;
  }

  private static void receiptLine(Document doc, String left, String right)
      throws DocumentException {
    PdfPTable t = new PdfPTable(new float[] {1f, 1f});
    t.setWidthPercentage(100);
    t.addCell(borderless(left, Element.ALIGN_LEFT, BODY));
    t.addCell(borderless(right, Element.ALIGN_RIGHT, BODY));
    doc.add(t);
  }

  private static void receiptTotal(Document doc, String left, String right)
      throws DocumentException {
    PdfPTable t = new PdfPTable(new float[] {1f, 1f});
    t.setWidthPercentage(100);
    t.addCell(borderless(left, Element.ALIGN_LEFT, BODY_BOLD));
    t.addCell(borderless(right, Element.ALIGN_RIGHT, BODY_BOLD));
    doc.add(t);
  }

  private static void receiptRule(Document doc) throws DocumentException {
    Paragraph p = new Paragraph("------------------------------", MUTED_BODY);
    p.setAlignment(Element.ALIGN_CENTER);
    doc.add(p);
  }

  private static PdfPCell borderless(String text, int align, Font font) {
    PdfPCell c = new PdfPCell(new Phrase(text, font));
    c.setBorder(Rectangle.NO_BORDER);
    c.setHorizontalAlignment(align);
    c.setPadding(1);
    return c;
  }

  // ---- table cells ----------------------------------------------------------------------------

  private static void th(PdfPTable table, String text, int align) {
    PdfPCell c = new PdfPCell(new Phrase(text, TH));
    c.setHorizontalAlignment(align);
    c.setBorder(Rectangle.BOTTOM);
    c.setBorderColor(RULE);
    c.setPadding(4);
    table.addCell(c);
  }

  private static void td(PdfPTable table, String text, int align) {
    PdfPCell c = new PdfPCell(new Phrase(text, BODY));
    c.setHorizontalAlignment(align);
    c.setBorder(Rectangle.BOTTOM);
    c.setBorderColor(RULE);
    c.setPadding(4);
    table.addCell(c);
  }

  private static void totalCell(PdfPTable table, String text, int align, Font font) {
    PdfPCell c = new PdfPCell(new Phrase(text, font));
    c.setHorizontalAlignment(align);
    c.setBorder(Rectangle.NO_BORDER);
    c.setPadding(2);
    table.addCell(c);
  }

  private static Paragraph spacer(float pts) {
    Paragraph p = new Paragraph(" ");
    p.setLeading(pts);
    return p;
  }

  // ---- formatting -----------------------------------------------------------------------------

  private static String money(BigDecimal amt) {
    return nz(amt).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
  }

  private static BigDecimal nz(BigDecimal b) {
    return b == null ? BigDecimal.ZERO : b;
  }

  private static String fmtDate(OffsetDateTime t) {
    return t == null ? "-" : t.withOffsetSameInstant(ZoneOffset.UTC).format(DATE);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String join(String sep, String a, String b) {
    boolean ba = blank(a);
    boolean bb = blank(b);
    if (ba && bb) return null;
    if (ba) return b;
    if (bb) return a;
    return a + sep + b;
  }
}
