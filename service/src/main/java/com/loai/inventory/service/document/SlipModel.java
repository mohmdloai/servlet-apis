package com.loai.inventory.service.document;

import java.util.List;

/**
 * The content of an 80 mm slip, decided once and painted twice ({@code stories/escpos_receipt.md}
 * §One model, two painters): the OpenPDF receipt and the ESC/POS raster both walk this value, so a
 * discount label or a tender row can never exist on one and not the other. Every string is already
 * formatted for print — money at scale 2, dates in UTC — because the model is the last place a
 * figure is decided; painters only place ink.
 *
 * <p>The in-store receipt fills every part. The counter-return slip ({@code returnSlipModel}) is
 * the same shape with no tender/change rows and a {@link #settlement} row saying how the money went
 * back — a slip is a slip, and one painter is enough.
 *
 * @param name the seller line (legal name, else org name)
 * @param addressLines the muted lines under it (address, tax reg., contact) — may be empty
 * @param logo raw image bytes of the org logo, or {@code null} for a text-only header
 * @param title {@code RECEIPT} / {@code RETURN}
 * @param barcode the order number as Code128 (scanned back for a return), or {@code null}
 * @param meta label/value rows under the title (Order, Invoice, Date, Customer…)
 * @param lines one per invoice / credit-note line
 * @param totals Subtotal, Tax and — only when there is one — the Discount row
 * @param total the emphasised row (TOTAL / REFUND TOTAL)
 * @param tenders one row per tender, only on a split sale; otherwise empty
 * @param tendered the Tendered sum, or {@code null} on a return slip
 * @param change the Change row, only when positive
 * @param settlement return slip only: how the refund was (or will be) paid back
 * @param footer the closing centred line
 */
public record SlipModel(
    String name,
    List<String> addressLines,
    byte[] logo,
    String title,
    String barcode,
    List<Row> meta,
    List<Line> lines,
    List<Row> totals,
    Row total,
    List<Row> tenders,
    Row tendered,
    Row change,
    Row settlement,
    String footer) {

  /** A label on the left, a value on the right. */
  public record Row(String label, String value) {}

  /**
   * One sold or returned line: the title on its own row, then {@code qty x unit} against the line
   * amount.
   */
  public record Line(String title, String qtyByPrice, String amount) {}
}
