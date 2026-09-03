package com.loai.inventory.service.document;

import com.loai.inventory.common.exception.ValidationException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * ESC/POS, the part of it a raster slip needs ({@code stories/escpos_receipt.md} §Bands, init,
 * cut): a pure function from a painted image to the bytes a thermal printer executes. No text mode,
 * no code pages — every glyph on the slip is ink in the image, which is why any ESC/POS printer
 * since the 1990s prints it the same. The drawer kick is deliberately absent: whether to open a
 * drawer is the client's knowledge (its tender, its wiring), so the server's stream is the slip and
 * nothing else.
 */
public final class Escpos {

  /** 80 mm paper at 203 dpi. */
  public static final int WIDTH_80MM = 576;

  /** 58 mm paper at 203 dpi. */
  public static final int WIDTH_58MM = 384;

  /** {@code ESC @} — initialise, clearing whatever state the previous job left. */
  static final byte[] INIT = {0x1B, 0x40};

  /** {@code ESC d 4} — feed four lines so the slip clears the tear bar. */
  static final byte[] FEED = {0x1B, 0x64, 0x04};

  /** {@code GS V 66 0} — feed-and-partial-cut; a printer without a cutter ignores it. */
  static final byte[] CUT = {0x1D, 0x56, 0x42, 0x00};

  /**
   * Rows per {@code GS v 0} band. A printer with a small receive buffer starts feeding the moment a
   * complete band arrives; one command carrying the whole slip would make it wait for the last byte
   * — and overflows a few firmwares.
   */
  static final int BAND_ROWS = 64;

  private Escpos() {}

  /**
   * The printer's dot width from the {@code width} query parameter: absent → 80 mm; {@code 576} or
   * {@code 384}; anything else is a 400. The raster is laid out for the width, not scaled to it.
   */
  public static int width(String raw) {
    if (raw == null || raw.isBlank()) {
      return WIDTH_80MM;
    }
    if (String.valueOf(WIDTH_80MM).equals(raw.trim())) {
      return WIDTH_80MM;
    }
    if (String.valueOf(WIDTH_58MM).equals(raw.trim())) {
      return WIDTH_58MM;
    }
    throw new ValidationException("width must be " + WIDTH_80MM + " or " + WIDTH_58MM);
  }

  /**
   * {@code INIT}, then the image top to bottom as {@code GS v 0 0 xL xH yL yH data} bands of at
   * most {@link #BAND_ROWS} rows, then {@code FEED} and {@code CUT}. Bits pack MSB-first, {@code 1}
   * = black; a pixel is black when its luminance is below half, so a grayscale image with
   * antialiased text and a thresholded logo both arrive here as-is.
   *
   * @throws IllegalArgumentException when the width is not a multiple of 8 — a raster row is whole
   *     bytes, and the two paper widths both are
   */
  public static byte[] encode(BufferedImage image) {
    int width = image.getWidth();
    if (width % 8 != 0) {
      throw new IllegalArgumentException("raster width must be a multiple of 8, got " + width);
    }
    int bytesPerRow = width / 8;
    int height = image.getHeight();
    ByteArrayOutputStream out = new ByteArrayOutputStream(height * bytesPerRow + 64);
    out.writeBytes(INIT);
    for (int top = 0; top < height; top += BAND_ROWS) {
      int rows = Math.min(BAND_ROWS, height - top);
      out.write(0x1D);
      out.write(0x76);
      out.write(0x30);
      out.write(0x00);
      out.write(bytesPerRow & 0xFF);
      out.write((bytesPerRow >> 8) & 0xFF);
      out.write(rows & 0xFF);
      out.write((rows >> 8) & 0xFF);
      for (int y = top; y < top + rows; y++) {
        for (int bx = 0; bx < bytesPerRow; bx++) {
          int packed = 0;
          for (int bit = 0; bit < 8; bit++) {
            if (isInk(image, bx * 8 + bit, y)) {
              packed |= 0x80 >> bit;
            }
          }
          out.write(packed);
        }
      }
    }
    out.writeBytes(FEED);
    out.writeBytes(CUT);
    return out.toByteArray();
  }

  /** Black when the pixel's luminance is below half — the one threshold the whole slip uses. */
  static boolean isInk(BufferedImage image, int x, int y) {
    if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
      return image.getRaster().getSample(x, y, 0) < 128;
    }
    int rgb = image.getRGB(x, y);
    int r = (rgb >> 16) & 0xFF;
    int g = (rgb >> 8) & 0xFF;
    int b = rgb & 0xFF;
    return (r * 299 + g * 587 + b * 114) / 1000 < 128;
  }
}
