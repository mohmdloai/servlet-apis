package com.loai.inventory.service.document;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The one typeface the raster slip is set in: IBM Plex Sans Arabic (SIL OFL, {@code
 * resources/fonts/}), a single family that carries Latin and Arabic, so a product title in either
 * script — or both — shapes correctly without a fallback lookup. Loaded once from the classpath
 * with {@link Font#createFont}; never a logical font ({@code Dialog}, {@code SansSerif}), because
 * the runtime image ships no system fonts and a logical-font lookup there throws.
 */
final class SlipFont {

  private static final Font REGULAR = load("IBMPlexSansArabic-Regular.ttf");
  private static final Font BOLD = load("IBMPlexSansArabic-Bold.ttf");

  private SlipFont() {}

  /**
   * The regular face at {@code px} pixels (Java2D points are pixels under the identity transform).
   */
  static Font regular(float px) {
    return REGULAR.deriveFont(px);
  }

  static Font bold(float px) {
    return BOLD.deriveFont(px);
  }

  /** {@code -1} when every character has a glyph; else the index of the first that hasn't. */
  static int canDisplayUpTo(String text) {
    return REGULAR.canDisplayUpTo(text);
  }

  private static Font load(String file) {
    try (InputStream in = SlipFont.class.getResourceAsStream("/fonts/" + file)) {
      if (in == null) {
        throw new IllegalStateException("bundled font missing from classpath: fonts/" + file);
      }
      return Font.createFont(Font.TRUETYPE_FONT, in);
    } catch (IOException | FontFormatException e) {
      throw new IllegalStateException("bundled font unreadable: fonts/" + file, e);
    }
  }
}
