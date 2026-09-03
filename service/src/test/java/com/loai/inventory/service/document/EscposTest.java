package com.loai.inventory.service.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ValidationException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure byte encoder ({@code stories/escpos_receipt.md} §Bands, init, cut), pinned by parsing
 * its own output.
 */
class EscposTest {

  @Test
  void width_defaultsTo80mm_acceptsBoth_rejectsTheRest() {
    assertEquals(576, Escpos.width(null));
    assertEquals(576, Escpos.width(""));
    assertEquals(576, Escpos.width("576"));
    assertEquals(384, Escpos.width(" 384 "));
    ValidationException e = assertThrows(ValidationException.class, () -> Escpos.width("500"));
    assertEquals("width must be 576 or 384", e.getMessage());
    assertThrows(ValidationException.class, () -> Escpos.width("abc"));
  }

  /** A 16×130 image → three bands of 64, 64 and 2 rows; init before, feed + cut after. */
  @Test
  void encode_bandsOf64RowsTileTheImage() {
    BufferedImage img = white(16, 130);
    img.getRaster().setSample(3, 0, 0, 0); // x=3 in row 0 → byte 0, bit 4 (MSB-first)
    img.getRaster().setSample(15, 129, 0, 0); // x=15 in the last row → byte 1, bit 0

    byte[] out = Escpos.encode(img);

    assertArrayEquals(new byte[] {0x1B, 0x40}, Arrays.copyOfRange(out, 0, 2));
    EscposTestSupport.Parsed p = EscposTestSupport.parse(out);
    assertEquals(List.of(64, 64, 2), p.bands().stream().map(EscposTestSupport.Band::rows).toList());
    for (EscposTestSupport.Band b : p.bands()) {
      assertEquals(2, b.bytesPerRow());
    }
    assertEquals(0x10, out[p.bands().get(0).dataOffset()] & 0xFF);
    EscposTestSupport.Band last = p.bands().get(2);
    assertEquals(0x01, out[last.dataOffset() + 1 * 2 + 1] & 0xFF);
    assertArrayEquals(
        new byte[] {0x1B, 0x64, 0x04, 0x1D, 0x56, 0x42, 0x00},
        Arrays.copyOfRange(out, p.tailOffset(), out.length));
    assertEquals(2 + 3 * 8 + 130 * 2 + 7, out.length);
  }

  @Test
  void encode_thresholdsGrayAtHalf() {
    BufferedImage img = white(8, 1);
    img.getRaster().setSample(0, 0, 0, 127); // just under half → ink
    img.getRaster().setSample(1, 0, 0, 128); // half → paper
    byte[] out = Escpos.encode(img);
    assertEquals(0x80, out[EscposTestSupport.parse(out).bands().get(0).dataOffset()] & 0xFF);
  }

  @Test
  void encode_rejectsAWidthThatIsNotWholeBytes() {
    assertThrows(IllegalArgumentException.class, () -> Escpos.encode(white(12, 1)));
  }

  private static BufferedImage white(int w, int h) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
    Graphics2D g = img.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, w, h);
    g.dispose();
    return img;
  }
}
