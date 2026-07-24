package com.loai.inventory.service.document;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteLine;
import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.Org;
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
 * <p>v1 uses OpenPDF with the built-in base-14 fonts (Latin/English, zero font assets). The
 * HTML-engine + embedded-Arabic-font upgrade is documented in the story and slots behind this same
 * class without changing callers.
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
            doc -> {
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
            doc -> {
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
              totals(
                  doc,
                  cn.getCurrency(),
                  new String[][] {
                    {"Subtotal", money(cn.getSubtotal())},
                    {"Tax", money(cn.getTaxTotal())},
                    {"Credit total", money(cn.getTotal())},
                  },
                  2);
            });
    return new RenderedDocument(fileName(cn.getCreditNoteNumber(), "credit-note"), bytes);
  }

  /** An 80mm thermal receipt for a sale (framed for in-store; carries tender + change). */
  public RenderedDocument renderReceipt(UUID orgId, UUID salesOrderId) {
    InvoiceAdminService.OrderInvoices oi = invoiceAdminService.listForOrder(orgId, salesOrderId);
    SalesOrder order = oi.order();
    InvoiceView live =
        oi.invoices().stream()
            .filter(v -> v.invoice().getStatus() != InvoiceStatus.VOID)
            .findFirst()
            .orElseThrow(
                () -> new NotFoundException("no issued invoice for order " + salesOrderId));
    SalesInvoice inv = live.invoice();

    BigDecimal tender = BigDecimal.ZERO;
    for (PaymentService.PaymentWithRefunds pw :
        paymentService.listForOrder(orgId, salesOrderId).payments()) {
      tender = tender.add(nz(pw.payment().getAmount()));
    }
    BigDecimal change = tender.subtract(nz(inv.getGrandTotal()));
    final BigDecimal tenderF = tender;
    final BigDecimal changeF = change;

    Org org = org(orgId);
    Image receiptLogo = logoImage(org, RECEIPT_LOGO_MAX_W, RECEIPT_LOGO_MAX_H);

    // 80mm ≈ 226.77 pt wide; height sized to content so the slip isn't mostly blank.
    float width = 226.77f;
    float height =
        220f + live.lines().size() * 16f + (receiptLogo == null ? 0f : RECEIPT_LOGO_EXTRA_H);
    byte[] bytes =
        build(
            new Rectangle(width, height),
            10,
            doc -> {
              if (receiptLogo != null) {
                receiptLogo.setAlignment(Element.ALIGN_CENTER);
                doc.add(receiptLogo);
              }
              receiptCenter(doc, headerName(org), H2);
              for (String line : orgAddressLines(org)) {
                receiptCenter(doc, line, MUTED_BODY);
              }
              receiptRule(doc);
              receiptCenter(doc, "RECEIPT", BODY_BOLD);
              receiptLine(doc, "Order", order.getOrderNumber());
              receiptLine(doc, "Invoice", inv.getInvoiceNumber());
              receiptLine(doc, "Date", fmtDate(inv.getIssuedAt()));
              if (inv.getCustomerName() != null && !inv.getCustomerName().isBlank()) {
                receiptLine(doc, "Customer", inv.getCustomerName());
              }
              receiptRule(doc);
              for (SalesInvoiceLine l : live.lines()) {
                doc.add(new Paragraph(l.getDescription(), BODY));
                receiptLine(
                    doc,
                    l.getQuantity() + " x " + money(l.getUnitPrice()),
                    money(l.getLineTotal()) + " " + inv.getCurrency());
              }
              receiptRule(doc);
              receiptLine(doc, "Subtotal", money(inv.getSubtotal()) + " " + inv.getCurrency());
              receiptLine(doc, "Tax", money(inv.getTaxTotal()) + " " + inv.getCurrency());
              receiptTotal(doc, "TOTAL", money(inv.getGrandTotal()) + " " + inv.getCurrency());
              receiptLine(doc, "Tendered", money(tenderF) + " " + inv.getCurrency());
              if (changeF.signum() > 0) {
                receiptLine(doc, "Change", money(changeF) + " " + inv.getCurrency());
              }
              receiptRule(doc);
              receiptCenter(doc, "Thank you!", MUTED_BODY);
            });
    return new RenderedDocument(fileName(order.getOrderNumber(), "receipt"), bytes);
  }

  private Org org(UUID orgId) {
    return orgService.getById(orgId);
  }

  /** A safe download filename from a document number: {@code INV-2026-0007.pdf}. */
  private static String fileName(String number, String fallback) {
    String base = blank(number) ? fallback : number.replaceAll("[^A-Za-z0-9._-]", "_");
    return base + ".pdf";
  }

  // ---- shared building blocks -----------------------------------------------------------------

  private interface Body {
    void render(Document doc) throws DocumentException;
  }

  private static byte[] build(Rectangle pageSize, float margin, Body body) {
    Document doc = new Document(pageSize, margin, margin, margin, margin);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      PdfWriter.getInstance(doc, out);
      doc.open();
      body.render(doc);
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
    String key = org.getLogoObjectKey();
    if (blank(key)) {
      return null;
    }
    try {
      byte[] bytes = logoSource.fetch(key);
      if (bytes == null || bytes.length == 0) {
        return null;
      }
      Image img = Image.getInstance(bytes);
      img.scaleToFit(maxWidth, maxHeight);
      return img;
    } catch (Exception e) {
      log.warn("Logo unavailable for document header (key={}): {}", key, e.toString());
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
