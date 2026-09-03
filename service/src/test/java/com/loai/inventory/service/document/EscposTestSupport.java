package com.loai.inventory.service.document;

import java.util.ArrayList;
import java.util.List;

/** Parses the byte stream {@link Escpos#encode} writes, so tests assert on structure, not bytes. */
final class EscposTestSupport {

  /** One {@code GS v 0} band: bytes per row, rows, and where its pixel data starts. */
  record Band(int bytesPerRow, int rows, int dataOffset) {}

  /** The bands in order and the offset of the first byte after the last band. */
  record Parsed(List<Band> bands, int tailOffset) {}

  private EscposTestSupport() {}

  static Parsed parse(byte[] b) {
    if (b.length < 2 || b[0] != 0x1B || b[1] != 0x40) {
      throw new AssertionError("stream must begin with ESC @");
    }
    int i = 2;
    List<Band> bands = new ArrayList<>();
    while (i + 8 <= b.length && b[i] == 0x1D && b[i + 1] == 0x76 && b[i + 2] == 0x30) {
      if (b[i + 3] != 0x00) {
        throw new AssertionError("band mode must be 0 (normal density) at " + i);
      }
      int x = (b[i + 4] & 0xFF) | ((b[i + 5] & 0xFF) << 8);
      int y = (b[i + 6] & 0xFF) | ((b[i + 7] & 0xFF) << 8);
      bands.add(new Band(x, y, i + 8));
      i += 8 + x * y;
    }
    return new Parsed(bands, i);
  }

  /** The whole slip as {@code [row][x]} ink flags, bands concatenated. */
  static boolean[][] bitmap(byte[] b) {
    Parsed p = parse(b);
    int width = p.bands().isEmpty() ? 0 : p.bands().get(0).bytesPerRow() * 8;
    List<boolean[]> rows = new ArrayList<>();
    for (Band band : p.bands()) {
      for (int r = 0; r < band.rows(); r++) {
        boolean[] row = new boolean[width];
        for (int bx = 0; bx < band.bytesPerRow(); bx++) {
          int packed = b[band.dataOffset() + r * band.bytesPerRow() + bx] & 0xFF;
          for (int bit = 0; bit < 8; bit++) {
            row[bx * 8 + bit] = (packed & (0x80 >> bit)) != 0;
          }
        }
        rows.add(row);
      }
    }
    return rows.toArray(new boolean[0][]);
  }

  static int ink(boolean[][] bitmap) {
    int n = 0;
    for (boolean[] row : bitmap) {
      for (boolean px : row) {
        if (px) {
          n++;
        }
      }
    }
    return n;
  }

  /** Leftmost ink column in the row, or {@code -1} for a blank row. */
  static int firstInk(boolean[] row) {
    for (int x = 0; x < row.length; x++) {
      if (row[x]) {
        return x;
      }
    }
    return -1;
  }

  /** Rightmost ink column in the row, or {@code -1} for a blank row. */
  static int lastInk(boolean[] row) {
    for (int x = row.length - 1; x >= 0; x--) {
      if (row[x]) {
        return x;
      }
    }
    return -1;
  }

  /** Black/white transitions across the row — a barcode row has dozens, a text row few. */
  static int transitions(boolean[] row) {
    int n = 0;
    for (int x = 1; x < row.length; x++) {
      if (row[x] != row[x - 1]) {
        n++;
      }
    }
    return n;
  }
}
