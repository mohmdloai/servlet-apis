package com.loai.inventory.common.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The correctness core of the Unicode-normalization slice — pure unit tests, no container. Every
 * invisible or combining character is written as an explicit {@code \\uXXXX} escape so the intent
 * is unambiguous (a literal ZWNJ or combining hamza is indistinguishable from a bug in source).
 */
class TextTest {

  // Arabic letters (normal, spacing letters — unambiguous as literals).
  private static final String ALEF = "ا"; // ا  bare alef
  private static final String ALEF_HAMZA_ABOVE = "أ"; // أ  precomposed
  private static final String ALEF_HAMZA_BELOW = "إ"; // إ
  private static final String ALEF_MADDA = "آ"; // آ
  private static final String HAA = "ح"; // ح
  private static final String MEEM = "م"; // م
  private static final String DAL = "د"; // د
  private static final String LAM = "ل"; // ل
  private static final String HEH = "ه"; // ه
  private static final String YAA = "ي"; // ي

  // Combining marks / joiners / invisibles.
  private static final String COMBINING_HAMZA_ABOVE = "ٔ";
  private static final String DAMMA = "ُ";
  private static final String FATHA = "َ";
  private static final String SHADDA = "ّ";
  private static final String TATWEEL = "ـ";
  private static final String ZWNJ = "‌";
  private static final String LRM = "‎";
  private static final String RLM = "‏";
  private static final String RLO = "‮";
  private static final String BOM = "﻿";
  private static final String NBSP = " ";

  private static final String AHMAD_BARE = ALEF + HAA + MEEM + DAL; // احمد
  private static final String AHMAD_HAMZA = ALEF_HAMZA_ABOVE + HAA + MEEM + DAL; // أحمد
  private static final String MUHAMMAD = MEEM + HAA + MEEM + DAL; // محمد

  // ---- NFC: precomposed vs combining vs presentation-form all converge ------------------------

  @Test
  void precomposedAndCombiningAlefHamzaAreOneStorageForm() {
    String combining = ALEF + COMBINING_HAMZA_ABOVE + HAA + MEEM + DAL; // alef + combining hamza
    assertEquals(Text.normalizeText(AHMAD_HAMZA), Text.normalizeText(combining));
    assertEquals(AHMAD_HAMZA, Text.normalizeText(combining));
  }

  @Test
  void arabicPresentationFormsFoldToCanonicalLetters() {
    // U+FE83 = ALEF WITH HAMZA ABOVE, ISOLATED FORM → U+0623.
    String fromPresentationBlock = "ﺃ" + HAA + MEEM + DAL;
    assertEquals(AHMAD_HAMZA, Text.normalizeText(fromPresentationBlock));
  }

  @Test
  void lamAlefLigatureFoldsToTwoLogicalLetters() {
    // U+FEFB = LAM WITH ALEF ISOLATED FORM → ل + ا.
    assertEquals(LAM + ALEF, Text.normalizeText("ﻻ"));
  }

  @Test
  void nonArabicPresentationFormsAreNotFolded() {
    // The "NFC not NFKC" guarantee: full-width 'Ａ' (U+FF21) and Latin 'ﬁ' ligature (U+FB01)
    // survive.
    assertEquals("Ａ", Text.normalizeText("Ａ"));
    assertEquals("ﬁ", Text.normalizeText("ﬁ"));
  }

  @Test
  void allThreeFunctionsAreIdempotent() {
    String[] samples = {
      AHMAD_HAMZA,
      ALEF + COMBINING_HAMZA_ABOVE + HAA,
      "ﺃ" + HAA + MEEM + DAL,
      "  John  Doe  ",
      MEEM + DAMMA + HAA + FATHA + MEEM + FATHA + SHADDA + DAL, // tashkeel
      RLM + "0٠۱" + LRM, // mixed digits + bidi controls
      "a😀b", // emoji
      "  MiXeD@Example.COM ",
    };
    for (String s : samples) {
      assertEquals(Text.normalizeText(s), Text.normalizeText(Text.normalizeText(s)));
      assertEquals(Text.normalizeNumeric(s), Text.normalizeNumeric(Text.normalizeNumeric(s)));
      assertEquals(Text.foldForSearch(s), Text.foldForSearch(Text.foldForSearch(s)));
    }
  }

  // ---- digit folding --------------------------------------------------------------------------

  @Test
  void arabicIndicAndPersianDigitsFoldToAsciiForNumeric() {
    assertEquals("0123456789", Text.normalizeNumeric("٠١٢٣٤٥٦٧٨٩"));
    assertEquals("0123456789", Text.normalizeNumeric("۰۱۲۳۴۵۶۷۸۹"));
  }

  @Test
  void phoneInArabicIndicDigitsEqualsAsciiForm() {
    // ٠١٠٠٦١٢٣٥٨٤
    String arabic = "٠١٠٠٦١٢٣٥٨٤";
    assertEquals("01006123584", Text.normalizeNumeric(arabic));
  }

  @Test
  void normalizeTextDoesNotFoldDigitsButNumericDoes() {
    String arabicZero = "٠"; // ٠
    assertEquals(arabicZero, Text.normalizeText(arabicZero));
    assertEquals("0", Text.normalizeNumeric(arabicZero));
  }

  @Test
  void numericKeepsPlusAndCollapsesSpaces() {
    assertEquals("+20 100", Text.normalizeNumeric("  +20   100  "));
  }

  // ---- search fold ----------------------------------------------------------------------------

  @Test
  void alefVariantsFoldToOneSearchKey() {
    String bare = Text.foldForSearch(AHMAD_BARE);
    assertEquals(bare, Text.foldForSearch(AHMAD_HAMZA));
    assertEquals(bare, Text.foldForSearch(ALEF_HAMZA_BELOW + HAA + MEEM + DAL));
    assertEquals(bare, Text.foldForSearch(ALEF_MADDA + HAA + MEEM + DAL));
    assertEquals(AHMAD_BARE, bare);
  }

  @Test
  void tashkeelStrippedInSearchKey() {
    String withTashkeel = MEEM + DAMMA + HAA + FATHA + MEEM + FATHA + SHADDA + DAL; // مُحَمَّد
    assertEquals(MUHAMMAD, Text.foldForSearch(withTashkeel));
  }

  @Test
  void tatweelStrippedInSearchKey() {
    assertEquals(
        MUHAMMAD, Text.foldForSearch(MEEM + HAA + TATWEEL + TATWEEL + TATWEEL + MEEM + DAL));
  }

  @Test
  void taaMarbutaFoldsToHaaInSearchKey() {
    // فاطمة ↔ فاطمه ; taa marbuta U+0629 → haa U+0647
    String stem = "فاطم"; // فاطم
    String withTaaMarbuta = stem + "ة";
    String withHaa = stem + HEH;
    assertEquals(Text.foldForSearch(withHaa), Text.foldForSearch(withTaaMarbuta));
  }

  @Test
  void alefMaqsuraFoldsToYaaInSearchKey() {
    // على ↔ علي ; alef maqsura U+0649 → yaa U+064A
    String stem = "عل"; // عل
    String withMaqsura = stem + "ى";
    String withYaa = stem + YAA;
    assertEquals(Text.foldForSearch(withYaa), Text.foldForSearch(withMaqsura));
  }

  @Test
  void searchKeyCasefoldsLatin() {
    assertEquals("ahmad", Text.foldForSearch("AhMaD"));
  }

  // ---- bidi / zero-width controls -------------------------------------------------------------

  @Test
  void bidiControlsStrippedFromNumeric() {
    assertEquals("0123", Text.normalizeNumeric(RLM + "01" + LRM + "23" + RLO));
  }

  @Test
  void zwnjPreservedByTextButRemovedByNumeric() {
    String withZwnj = "a" + ZWNJ + "b"; // ZWNJ meaningful in Persian orthography
    assertEquals("a" + ZWNJ + "b", Text.normalizeText(withZwnj));
    assertEquals("ab", Text.normalizeNumeric(withZwnj));
  }

  @Test
  void bomStrippedEverywhere() {
    assertEquals("abc", Text.normalizeText(BOM + "abc"));
    assertEquals("abc", Text.normalizeNumeric("abc" + BOM));
    assertEquals("a@b.com", Text.normalizeEmail(BOM + "a@b.com"));
  }

  // ---- whitespace -----------------------------------------------------------------------------

  @Test
  void internalWhitespaceRunsCollapseAndTrim() {
    assertEquals("a b c", Text.normalizeText("  a   b\t\nc  "));
  }

  @Test
  void nbspTreatedAsWhitespace() {
    assertEquals("a b", Text.normalizeText("a" + NBSP + "b"));
  }

  @Test
  void blankAndNullMapToNull() {
    assertNull(Text.normalizeText(null));
    assertNull(Text.normalizeText(""));
    assertNull(Text.normalizeText("   \t  "));
    assertNull(Text.normalizeNumeric("   "));
    assertNull(Text.foldForSearch("   "));
    assertNull(Text.normalizeEmail("   "));
  }

  // ---- astral / emoji safety ------------------------------------------------------------------

  @Test
  void astralCodePointsPassThroughIntact() {
    String withEmoji = "a😀b"; // 😀 is a single 4-byte code point
    String out = Text.normalizeText(withEmoji);
    assertEquals(withEmoji, out);
    assertEquals(3, out.codePointCount(0, out.length()));
  }

  // ---- Latin regression (behaviour of the helpers being replaced) -----------------------------

  @Test
  void emailLowercasedAndTrimmed() {
    assertEquals("foo@bar.com", Text.normalizeEmail("  Foo@Bar.COM "));
    assertNull(Text.normalizeEmail(null));
  }

  @Test
  void latinNameCollapsesLikeTrim() {
    assertEquals("John Doe", Text.normalizeText("  John   Doe "));
  }

  @Test
  void asciiBarcodeUnchanged() {
    assertEquals("012345", Text.normalizeNumeric("012345"));
  }

  // ---- currency (consolidated helper) ---------------------------------------------------------

  @Test
  void currencyDefaultsToEgpAndUppercases() {
    assertEquals("EGP", Text.normalizeCurrency(null));
    assertEquals("EGP", Text.normalizeCurrency("  "));
    assertEquals("USD", Text.normalizeCurrency(" usd "));
    assertEquals("EGP", Text.normalizeCurrency("egp"));
  }

  @Test
  void foldForSearchNeverReturnsBlank() {
    // A string of only tashkeel + tatweel folds away entirely → null, never "".
    assertNull(Text.foldForSearch(TATWEEL + FATHA + DAMMA));
    assertTrue(Text.foldForSearch(AHMAD_BARE).length() > 0);
  }
}
