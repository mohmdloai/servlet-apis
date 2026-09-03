# ESC/POS slip size (stories/escpos_receipt.md)

Measured by `DocumentRenderServiceTest.renderReceiptEscpos_sizeStaysWithinTheBleBudget` on
2026-09-03: the receipt for a fully-profiled org (legal name, address, tax reg., contact — five
header lines), an order-number barcode, a single cash tender with change, and N lines alternating an
Arabic and a Latin title. Raster mode (`GS v 0`, 64-row bands), 8 dots/mm, no compression.

| lines | width (dots) | paper | rows | bytes  |
|------:|-------------:|-------|-----:|-------:|
|     1 |          576 | 80 mm |  820 | 59 153 |
|     1 |          384 | 58 mm |  850 | 40 921 |
|     3 |          576 | 80 mm |  972 | 70 121 |
|     3 |          384 | 58 mm | 1002 | 48 233 |
|    10 |          576 | 80 mm | 1504 | 108 489 |
|    10 |          384 | 58 mm | 1534 | 73 833 |

Rows ÷ 8 = slip length in mm: a one-line receipt is ~10 cm, ten lines ~19 cm.

What the frontend can say about transfer time: at the 10–30 KB/s a negotiated-MTU BLE link
delivers, a three-line 80 mm slip is 2.5–7 s on the wire, and the printer starts feeding after the
first band (~4.6 KB), so the customer sees paper moving within a second. A 58 mm printer is ~30 %
cheaper per slip.

Not done, recorded for later: blank rows (gaps, margins, the space around the barcode) still cost
72 bytes each; replacing runs of blank rows with `ESC J n` (feed n dots) would cut roughly a quarter
of the bytes with no visible change. Deferred until a real printer says the transfer is too slow.
