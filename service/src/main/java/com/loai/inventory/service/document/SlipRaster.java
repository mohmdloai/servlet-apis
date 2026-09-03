package com.loai.inventory.service.document;

import com.lowagie.text.pdf.Barcode128;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Paints a {@link SlipModel} into a one-bit-bound grayscale image at 8 dots per millimetre — the
 * raster an ESC/POS printer prints ({@code stories/escpos_receipt.md} §Layout). Java2D's {@link
 * TextLayout} does the bidi and Arabic shaping, {@link SlipFont} supplies the glyphs; a title in
 * either script is placed by its own direction, while the money column stays on the right whatever
 * the script — the layout, not the text, owns the columns. The Code128 comes from the same OpenPDF
 * encoder the PDF slip uses, drawn bar by bar, so a scanner reads both slips back to the same sale.
 */
final class SlipRaster {

  private static final Logger log = LoggerFactory.getLogger(SlipRaster.class);

  /** Side margin in dots (1 mm). */
  static final int MARGIN = 8;

  private static final int GAP = 4;
  private static final int RULE_GAP = 6;
  private static final float NAME_PX = 26f;
  private static final float BODY_PX = 22f;
  private static final float MUTED_PX = 20f;
  private static final float TOTAL_PX = 26f;
  static final int BARCODE_H = 72;
  private static final int LOGO_MAX_H = 112;
  private static final int LABEL_VALUE_GAP = 12;

  private SlipRaster() {}

  /** The slip for a printer {@code width} dots wide (576 for 80 mm, 384 for 58 mm). */
  static BufferedImage paint(SlipModel m, int width) {
    Sheet s = new Sheet(width, estimateHeight(m));
    BufferedImage logo = decodeLogo(m.logo());
    if (logo != null) {
      s.image(logo, width / 2, LOGO_MAX_H);
    }
    s.center(m.name(), SlipFont.bold(NAME_PX));
    for (String line : m.addressLines()) {
      s.center(line, SlipFont.regular(MUTED_PX));
    }
    s.rule();
    s.center(m.title(), SlipFont.bold(BODY_PX));
    if (m.barcode() != null && !m.barcode().isBlank()) {
      s.barcode(m.barcode());
    }
    for (SlipModel.Row r : m.meta()) {
      s.row(r.label(), r.value(), SlipFont.regular(BODY_PX));
    }
    s.rule();
    for (SlipModel.Line l : m.lines()) {
      s.paragraph(l.title(), SlipFont.regular(BODY_PX), Align.AUTO);
      s.row(l.qtyByPrice(), l.amount(), SlipFont.regular(BODY_PX));
    }
    s.rule();
    for (SlipModel.Row r : m.totals()) {
      s.row(r.label(), r.value(), SlipFont.regular(BODY_PX));
    }
    s.row(m.total().label(), m.total().value(), SlipFont.bold(TOTAL_PX));
    for (SlipModel.Row r : m.tenders()) {
      s.row(r.label(), r.value(), SlipFont.regular(BODY_PX));
    }
    if (m.tendered() != null) {
      s.row(m.tendered().label(), m.tendered().value(), SlipFont.regular(BODY_PX));
    }
    if (m.change() != null) {
      s.row(m.change().label(), m.change().value(), SlipFont.regular(BODY_PX));
    }
    if (m.settlement() != null) {
      s.row(m.settlement().label(), m.settlement().value(), SlipFont.regular(BODY_PX));
    }
    s.rule();
    s.center(m.footer(), SlipFont.regular(MUTED_PX));
    return s.crop();
  }

  /** The Code128 module widths for {@code code} — the PDF's encoder, shared. */
  static byte[] barcodeModules(String code) {
    return Barcode128.getBarsCode128Raw(Barcode128.getRawText(code, false));
  }

  /** True when the text's first strong character is right-to-left. */
  static boolean isRtl(String text) {
    return text != null
        && !text.isEmpty()
        && !new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseIsLeftToRight();
  }

  private static int estimateHeight(SlipModel m) {
    int h = 2 * MARGIN + 40;
    h += m.logo() == null ? 0 : LOGO_MAX_H + GAP;
    h += (1 + m.addressLines().size()) * 34;
    h += 3 * (RULE_GAP * 2 + 1) + 34;
    h += m.barcode() == null ? 0 : BARCODE_H + GAP;
    h += m.meta().size() * 32;
    h += m.lines().size() * 64;
    h += (m.totals().size() + 1 + m.tenders().size() + 3) * 34;
    h += 40;
    return h;
  }

  private static BufferedImage decodeLogo(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return null;
    }
    try {
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
      if (img == null) {
        log.warn("Logo not decodable for the raster slip; printing a text header");
      }
      return img;
    } catch (Exception e) {
      log.warn("Logo unavailable for the raster slip: {}", e.toString());
      return null;
    }
  }

  private enum Align {
    LEFT,
    RIGHT,
    CENTER,
    /** By the text's own direction: Arabic to the right edge, Latin to the left. */
    AUTO
  }

  /** A growable white page with a cursor; every draw advances {@code y}. */
  private static final class Sheet {
    private final int width;
    private final int content;
    private BufferedImage img;
    private Graphics2D g;
    private int y = MARGIN;

    Sheet(int width, int height) {
      this.width = width;
      this.content = width - 2 * MARGIN;
      allocate(height);
    }

    private void allocate(int height) {
      BufferedImage next = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
      Graphics2D ng = next.createGraphics();
      ng.setColor(Color.WHITE);
      ng.fillRect(0, 0, width, height);
      if (img != null) {
        ng.drawImage(img, 0, 0, null);
        g.dispose();
      }
      ng.setColor(Color.BLACK);
      ng.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      ng.setRenderingHint(
          RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
      ng.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      img = next;
      g = ng;
    }

    private void ensure(int rows) {
      if (y + rows + MARGIN > img.getHeight()) {
        allocate(Math.max(img.getHeight() * 2, y + rows + MARGIN + 200));
      }
    }

    void center(String text, Font font) {
      paragraph(text, font, Align.CENTER);
    }

    /** A wrapped block of text, each line placed by {@code align}. */
    void paragraph(String text, Font font, Align align) {
      if (text == null || text.isEmpty()) {
        return;
      }
      Align a = align == Align.AUTO ? (isRtl(text) ? Align.RIGHT : Align.LEFT) : align;
      for (TextLayout line : wrap(text, font, content)) {
        draw(line, MARGIN, content, a);
      }
      y += GAP;
    }

    /**
     * {@code label} on the left (wrapping around it), {@code value} flush right on the first line.
     */
    void row(String label, String value, Font font) {
      String v = value == null ? "" : value;
      String l = label == null ? "" : label;
      TextLayout valueLayout =
          v.isEmpty() ? null : new TextLayout(v, font, g.getFontRenderContext());
      float valueW = valueLayout == null ? 0f : valueLayout.getAdvance();
      int labelW = Math.max(1, (int) (content - valueW - (valueW > 0 ? LABEL_VALUE_GAP : 0)));
      List<TextLayout> labelLines = l.isEmpty() ? List.of() : wrap(l, font, labelW);
      int startY = y;
      if (valueLayout != null) {
        ensure(lineHeight(valueLayout));
        valueLayout.draw(g, MARGIN + content - valueW, y + valueLayout.getAscent());
      }
      int afterValue = valueLayout == null ? y : y + lineHeight(valueLayout);
      y = startY;
      for (TextLayout line : labelLines) {
        draw(line, MARGIN, labelW, Align.LEFT);
      }
      y = Math.max(y, afterValue) + GAP;
    }

    void rule() {
      ensure(RULE_GAP * 2 + 1);
      y += RULE_GAP;
      g.fillRect(MARGIN, y, content, 1);
      y += 1 + RULE_GAP;
    }

    /** A centred image scaled to fit {@code maxW × maxH}, aspect kept. */
    void image(BufferedImage src, int maxW, int maxH) {
      double scale = Math.min((double) maxW / src.getWidth(), (double) maxH / src.getHeight());
      scale = Math.min(scale, 1.0);
      int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
      int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
      ensure(h + GAP);
      Object prev = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(src, MARGIN + (content - w) / 2, y, w, h, null);
      if (prev != null) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prev);
      }
      y += h + GAP;
    }

    /** The Code128 of {@code code}, centred, modules 1–3 dots wide so it fits and still scans. */
    void barcode(String code) {
      byte[] modules = barcodeModules(code);
      int total = 0;
      for (byte b : modules) {
        total += b;
      }
      int module = (int) Math.max(1, Math.min(3, Math.floor(content * 0.85 / Math.max(1, total))));
      int barcodeW = total * module;
      int x = MARGIN + Math.max(0, (content - barcodeW) / 2);
      ensure(BARCODE_H + GAP);
      boolean ink = true;
      for (byte b : modules) {
        int w = b * module;
        if (ink) {
          g.fillRect(x, y, w, BARCODE_H);
        }
        x += w;
        ink = !ink;
      }
      y += BARCODE_H + GAP;
    }

    BufferedImage crop() {
      int height = y + MARGIN;
      BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
      Graphics2D og = out.createGraphics();
      og.drawImage(img, 0, 0, width, height, 0, 0, width, height, null);
      og.dispose();
      g.dispose();
      return out;
    }

    private void draw(TextLayout line, int boxX, int boxW, Align align) {
      ensure(lineHeight(line));
      float adv = line.getAdvance();
      float x =
          switch (align) {
            case RIGHT -> boxX + boxW - adv;
            case CENTER -> boxX + (boxW - adv) / 2f;
            default -> boxX;
          };
      line.draw(g, Math.max(boxX, x), y + line.getAscent());
      y += lineHeight(line);
    }

    private static int lineHeight(TextLayout line) {
      return (int) Math.ceil(line.getAscent() + line.getDescent() + line.getLeading());
    }

    private List<TextLayout> wrap(String text, Font font, int maxWidth) {
      AttributedString as = new AttributedString(text);
      as.addAttribute(TextAttribute.FONT, font);
      AttributedCharacterIterator it = as.getIterator();
      LineBreakMeasurer measurer = new LineBreakMeasurer(it, g.getFontRenderContext());
      List<TextLayout> out = new ArrayList<>();
      while (measurer.getPosition() < it.getEndIndex()) {
        out.add(measurer.nextLayout(Math.max(1f, maxWidth)));
      }
      return out;
    }
  }
}
