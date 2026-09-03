# Slice: The receipt as printer bytes — an ESC/POS slip for the counter's thermal printer

> Loyverse gap #5, the last on the owner's ranked list, after
> [`capture_walk_in_customer.md`](./capture_walk_in_customer.md) (V87),
> [`counter_discount.md`](./counter_discount.md) (V88), [`counter_return.md`](./counter_return.md)
> (V89) and [`split_tender.md`](./split_tender.md). Extends
> [`document_pdf_rendering.md`](./document_pdf_rendering.md) Part B — the same receipt, a second
> output. **Branch `172_feat/escpos-receipt` off `master`, no migration** — nothing about a sale
> changes; only how its slip leaves the server. Frontend pair:
> `frontst/stories/135_st_receipt_printer.md`.

---

## Goal

Two reads that return the slip as **ESC/POS bytes** a thermal printer executes directly, instead of
a PDF a browser has to rasterise through a print dialog:

1. `GET /api/orgs/{orgId}/sales-orders/{id}/receipt.escpos?width=576` (VIEWER) — the in-store
   sale's receipt: the same content as `receipt.pdf` (header, logo, Code128 order number, lines,
   discount, totals, tenders, change, footer).
2. `GET /api/orgs/{orgId}/credit-notes/{id}/receipt.escpos?width=576` (VIEWER) — the counter
   return's slip: credit-note number, the original order number, the returned lines, the refund
   total and how it was (or will be) paid back.

Done means: a cashier on an Android phone taps *Charge*, and the slip is coming out of the
Bluetooth printer on the counter before the customer has put their wallet away — no tab, no print
sheet, no "select a printer" — and the Arabic on it is Arabic, not blanks.

---

## What exists, what is missing

- `DocumentRenderService.renderReceipt` builds the 80 mm receipt PDF with OpenPDF: content-sized
  height, org header + logo (`LogoSource`), a `Barcode128` of the order number the admin scanner
  reads back for a return, one line per invoice line, discount row (V88), tenders (split-aware),
  change. `SalesOrderHandler.doReceiptPdf` serves it as `application/pdf`, VIEWER.
- The credit note has an **A4 PDF** only (`/credit-notes/{id}/pdf`); the admin's post-return screen
  prints that A4 through the same dialog. There is no 80 mm return slip at all.
- Every document draws with the base-14 **Helvetica**, so Arabic text renders as nothing
  (`document_pdf_rendering.md` §v1 — "English-only until a bundled Arabic font"). On real data
  that is the customer's name and most product titles.
- Nothing on the server knows ESC/POS. Nothing in the JVM image ships a font.

Missing, therefore: an output the printer understands, a content model the two outputs share so
they cannot drift, a font that has Arabic in it, and the return slip.

---

## Why this shape

### Raster, not text mode

ESC/POS has a text mode (send bytes, the printer's ROM font draws them) and a raster mode
(`GS v 0`: send a 1-bit image, the printer prints it). Text mode is tiny and fast — a whole
receipt is a kilobyte — and it is the wrong choice here, for one reason: **Arabic**. Text-mode
Arabic needs the printer to hold a matching code page (CP864 / CP1256 / a vendor page), to shape
letters into their initial/medial/final forms, and to run bidi so a mixed `EGP 450.00` line comes
out in order. The cheap Bluetooth printers a stationery shop buys do none of the three reliably,
and each vendor that does it does it differently. A raster slip is **printer-agnostic** — every
ESC/POS printer since the 1990s executes `GS v 0` identically — and moves shaping, bidi and fonts
to the one place that already lays the receipt out: the server. Loyverse takes the same path for
non-Latin scripts.

The cost is bytes: a 576-dot-wide slip is 72 bytes per row; a three-line sale is ~700 rows,
~50 KB. Over BLE at the 10–30 KB/s a negotiated MTU gives, that is 2–5 s end to end — and the
slip is sent in **bands** (below) so the printer starts feeding paper after the first kilobyte,
not the last. The number is measured, not assumed: see Tests.

### One model, two painters

`renderReceipt` today is one method that both *decides* what goes on the slip and *draws* it with
OpenPDF calls. Adding a second drawer by copy-pasting the method is how the PDF and the printer
slip start disagreeing about a discount label six months from now. So the slice splits it:

- `SlipModel` — a plain value: `name` + `addressLines`, `logo` (bytes or null), `title`,
  `barcode` (the order number), `meta` rows (Order / Invoice / Date / Customer), `lines`
  (`title`, `qtyByPrice`, `amount`), `totals` rows (Subtotal / Tax / Discount(label)), `total`,
  `tenders`, `tendered`, `change` (nullable), `settlement` (return slip only), `footer`. Built by
  **one** method, `receiptModel(orgId, salesOrderId)`, from the same reads `renderReceipt` uses
  now.
- The existing OpenPDF painter, rewritten to walk the model. **Byte-identical output** to today for
  every existing test — the refactor is proven by the current `DocumentRenderServiceTest` cases
  passing unchanged.
- `SlipRaster` — the new painter: Java2D with antialiasing onto a grayscale `BufferedImage` of the
  requested width, height grown to content; `Escpos.encode` thresholds it to one bit at pack time
  (antialiased-then-thresholded glyphs keep their shape better than binary rendering).

The credit-note slip is the **same shape** — `returnSlipModel(orgId, creditNoteId)` fills a
`SlipModel` with the credit-note number, the original order number (as barcode again, so the next
return scans it too), date, returned lines, the discount share when the sale was discounted,
`REFUND TOTAL`, and a `settlement` row (*Cash handed back* / *Pending transfer* / *InstaPay
transfer*) from the note's first live refund via `CreditNoteService.refundsFor` — raster painter
only; an 80 mm PDF of it is not asked for. A slip is a slip; one painter is enough.

### Java2D, a bundled font, headless

The JRE image (`eclipse-temurin:25-jre`) carries `java.desktop`, so `BufferedImage` +
`Graphics2D` + `TextLayout` (which does bidi and Arabic shaping) are already there. What is not
there is a font. The slice bundles **one OFL family that covers Latin and Arabic in the same file**
— IBM Plex Sans Arabic (Regular + Bold) is the recommendation; Cairo is the acceptable alternate —
under `service/src/main/resources/fonts/`, loaded once with `Font.createFont`. Rules that keep it
working in the container:

- Never touch a logical font (`Dialog`, `SansSerif`): every `setFont` is the bundled family. A
  logical-font lookup on a fontless image throws from `sun.awt.FontConfiguration`.
- `java.awt.headless=true` is set in the Dockerfile `ENV JAVA_TOOL_OPTIONS` **and** asserted by a
  test, so a future base-image change fails loudly.
- If the image still needs `fontconfig`'s cache to exist, `fonts-dejavu-core` is the one apt line
  to add; the DoD's production test print is what proves this either way.

This is also the font the PDF Arabic defect needs
(`document_pdf_rendering.md` §v1 — "bundled Arabic font"). Fixing the PDFs is **out** of this
slice, but the font files land here so that fix is a `BaseFont.createFont` away.

### Bands, init, cut — what the bytes are

```
ESC @                       1B 40                  initialise (clears a previous job's state)
GS v 0 m xL xH yL yH d…     1D 76 30 00 …          one raster band, m=0 normal density,
                                                   x = width/8 bytes per row, y ≤ 64 rows
… (repeat per band, top to bottom)
ESC d 4                     1B 64 04               feed 4 lines clear of the tear bar
GS V 66 0                   1D 56 42 00            feed-and-partial-cut (no cutter ⇒ ignored)
```

**Why bands of 64 rows**, not one image: a printer with a small receive buffer starts printing
the moment a complete `GS v 0` arrives; one 50 KB command makes it wait for the whole transfer,
and on a few firmwares overflows. **Why no drawer kick here:** whether to open the drawer depends
on the tender and on whether *this phone's* printer has a drawer wired to it — both facts the
client holds. The client prepends `ESC p 0 25 250` when it decides to; the server's stream is the
slip and nothing else, so the same bytes print correctly from a test tool.

`width` is the printer's dot width: **576** (80 mm paper, default) or **384** (58 mm). Anything
else → 400 `width must be 576 or 384`. The raster is laid out for that width — a 58 mm slip is
not a shrunken 80 mm one; the line-item columns rewrap.

### Barcode and logo in the raster

The Code128 comes from the **same `Barcode128` object** the PDF uses, via
`Barcode.createAwtImage(fg, bg)` — one encoder, so the scanner reads either slip back to the same
sale. The logo is `ImageIO.read` → grayscale → 50 % threshold into the 1-bit image, scaled to
`RECEIPT_LOGO_MAX_W/H` at 8 dots/mm. (Floyd–Steinberg dithering for photographic logos is
deferred; a threshold is right for the flat-colour marks shops actually upload.)

### Layout at 8 dots per millimetre

The raster reuses the PDF's proportions, in dots: Bold 26 px header, 22 px body, 20 px muted,
1 px rule, 6 px gutters; line items as title on one row and `qty × unit … amount` right-aligned on
the next, exactly as the PDF does; Arabic titles right-aligned by `TextLayout`'s bidi (`dir` of the
run decides, not the org's language). Digits stay Western on the slip (`EGP 450.00`) — the price
column is compared against the screen, and the screen shows Western digits.

---

## Data model

None. No migration.

---

## Application & wire

### `GET /api/orgs/{orgId}/sales-orders/{id}/receipt.escpos[?width=576|384]`

`SalesOrderHandler`, beside `doReceiptPdf`; same 404 when the order has no issued invoice; 400 on a
bad `width`. Response `200`, `Content-Type: application/octet-stream`,
`Content-Disposition: attachment; filename="SO-2026-00417.escpos"`, `Cache-Control: private,
no-store`, body = the byte stream above.

### `GET /api/orgs/{orgId}/credit-notes/{id}/receipt.escpos[?width=]`

`CreditNoteHandler`, beside the A4 `/pdf`. Same envelope; 404 for a DRAFT/unknown note (a slip is
only for an issued one).

### Service

```
DocumentRenderService
  SlipModel        receiptModel(orgId, salesOrderId)         // one builder
  RenderedDocument renderReceipt(orgId, salesOrderId)        // PDF painter over the model (unchanged output)
  RenderedDocument renderReceiptEscpos(orgId, salesOrderId, int width)
  SlipModel        returnSlipModel(orgId, creditNoteId)
  RenderedDocument renderReturnSlipEscpos(orgId, creditNoteId, int width)
CreditNoteService.refundsFor(orgId, creditNoteId)            // the note's refund rows, oldest first

document/SlipModel         // the content, decided once
document/SlipRaster        // model → BufferedImage (gray, thresholded at pack time), width-aware layout
document/Escpos            // BufferedImage → bytes: INIT, bands, FEED, CUT  (pure, no I/O)
document/SlipFont          // bundled family, loaded once; canDisplayUpTo guard in tests
```

`Escpos` is a pure function of an image; it is where the bands and the constants live and it is
unit-tested by parsing its own output.

---

## Authorization

VIEWER, exactly as `receipt.pdf` — a slip restates a closed sale; printing it is not a money
action. Cross-org id → 404 through the existing org-scoped reads.

---

## Out (deferred)

- **Text-mode ESC/POS** and per-printer code pages — see *Raster, not text mode*.
- **The PDFs' Arabic** — the font lands here; the OpenPDF `BaseFont` swap is its own fix story.
- **Drawer kick, cut/no-cut, copies** — client-side flags; the server emits one slip.
- **An 80 mm PDF of the credit note**, **QR codes** (ETA e-receipt QR is a tax-integration slice),
  **a merchant-editable footer line** (needs an org setting — a later knob), **dithering**,
  **image compression** (`GS ( L` is not universal; bands are enough).
- **Printing an online order's receipt** — the model is built from the in-store invoice; a
  fulfilled online order prints its invoice PDF, as today.

---

## Tests

`DocumentRenderServiceTest` (+ the existing receipt cases **unchanged and green** — the refactor
guard):
- `renderReceiptEscpos_beginsWithInitAndEndsWithFeedAndCut` — first two bytes `1B 40`, tail
  `1B 64 04 1D 56 42 00`.
- `renderReceiptEscpos_bandsTileTheSlipExactly` — parse every `1D 76 30 00 xL xH yL yH` header:
  every `x` = 72 (576/8), every `y` ≤ 64, the `y`s sum to the raster's height, and the payload
  length after each header is `x·y`; no bytes between bands.
- `renderReceiptEscpos_honoursWidth384` — `x` = 48; `width=500` → `ValidationException`.
- `renderReceiptEscpos_arabicTitleHasGlyphs` — a line titled `دفتر ملاحظات`:
  `SlipFont.canDisplayUpTo(title) == -1` (and `!= -1` for a script the font lacks, so the guard
  is real), and the slip carries markedly more ink than the same slip titled `.` — the assertion
  that would have failed under Helvetica.
- `slipRaster_arabicTitleSitsRight_latinTitleSitsLeft` — `isRtl` pinned; a slip whose only title is
  `قلم` has a row whose ink sits entirely right of centre, a `Pen` slip has none; no ink in either
  margin — the layout, not the script, owns the columns.
- `renderReceiptEscpos_barcodeRowsScanAsBars` — some row flips black/white ≥ 40 times; the
  modules come from `Barcode128.getBarsCode128Raw`, the PDF's own encoder.
- `receiptModel_carriesTenderDiscountAndChangeRowsOnlyWhenTheyExist` — one tender row per
  payment on a split, the discount row only when discounted, change only when positive — asserted
  on the **model**, which is what both painters consume; the plain sale has none of them.
- `returnSlipModel_namesTheOriginalOrderAndTheRefundMethod` — cash → *Cash handed back*, a
  pending transfer after a cancelled row → *Pending transfer*, no refund → no row; a discounted
  sale's slip carries the discount line; `returnSlip_draftNoteIs404`.
- `slipPaintsHeadless` — the class sets `java.awt.headless=true` before any AWT use and asserts
  `GraphicsEnvironment.isHeadless()`; every render above ran under it.
- `renderReceiptEscpos_sizeStaysWithinTheBleBudget` — prints the size table and caps a slip at
  120 KB.

`EscposTest` (unit, `document/Escpos`): a 16×130 synthetic image → 3 bands (64, 64, 2); a
1-pixel-wide ink column lands in the right byte/bit (MSB-first, 1 = black); gray thresholds at
half; `width` parses 576/384 and 400s the rest.

`api/.../sale/ReceiptEscposHandlerTest` (handler, mocked renderer): VIEWER → 200 +
`application/octet-stream` + `attachment` + `private, no-store` + the bytes; `width=384` reaches
the renderer; `width=100` → 400 before anything renders; non-member → 403; the credit-note route
the same.

`api/.../sale/ReceiptEscposIT` (the `CounterReturnIT` harness, headless): a real cash sale of an
Arabic-titled product → a banded 80 mm slip whose model carries the title, the barcode, Tendered
and Change; the counter return that follows → a 58 mm return slip naming the note, the original
order and *Cash handed back*; ids nothing was sold under → 404 for both.

**Measured, recorded in `tools/seed/results/escpos-slip-size.md`** (the file, not prose): byte
size of a 1-, 3- and 10-line slip at 576 and 384 — 59 / 70 / 108 KB at 80 mm — so the frontend
pair can state a transfer time honestly.

Regression green: `InStoreSaleIT`, `CounterReturnIT`, `SplitTenderIT`, `DocumentRenderServiceTest`.

## Definition of done

Model split + two painters + `Escpos` + bundled font + two handlers + Dockerfile `ENV` + the
suites above green + the size table; `spotless:apply`; story committed on
`172_feat/escpos-receipt`; the frontend pair `frontst/stories/135_st_receipt_printer.md` ships
after this merges (its fetch of `receipt.escpos` 404s against the old API and falls back to the
print dialog — degraded, not broken). A test print from the **deployed** image on a real 80 mm
Bluetooth printer with an Arabic product title closes the story; the ubuntu CI runner has fonts
the container does not, so green CI is not that proof.
