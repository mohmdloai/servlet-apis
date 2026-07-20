package com.loai.inventory.common.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Canonical Unicode normalization for externally-supplied text, applied <strong>once, at the
 * ingress boundary</strong>. The backend stores UTF-8 end-to-end but performs no Unicode
 * normalization on its own, and for Arabic that silently corrupts identity: {@code "أحمد"} typed as
 * a precomposed letter, as base-letter-plus-combining-hamza, or pasted from the Arabic
 * presentation-forms block are three distinct byte sequences that a naive {@code UNIQUE} constraint
 * or {@code equals} treats as three different people. A phone typed with Arabic-Indic digits
 * ({@code ٠١٠…}) never dedups against its ASCII twin. Invisible bidi/zero-width controls let a
 * value render differently than it sorts or compares (a homograph/spoofing vector).
 *
 * <p>The fix is to compute a canonical form exactly once, on the way in, and store that. This class
 * is that boundary — a stateless, JDK-only ({@link Normalizer}, NFC) utility that replaces the
 * scattered per-service {@code trimOrNull} / {@code normalize*} helpers. Two representations are
 * separated deliberately: a faithful <em>storage</em> form ({@link #normalizeText} / {@link
 * #normalizeNumeric}) that preserves the user's intent (including Arabic diacritics), and a lossy
 * <em>match</em> form ({@link #foldForSearch}) that is never displayed, only used as a derived
 * search/dedup key.
 *
 * <p><strong>Why NFC, not NFKC:</strong> NFKC destructively collapses legitimate distinctions
 * (ligatures, super/subscripts, full-width forms) and is not safe for round-tripping display text.
 * NFC is the canonical, loss-free, forward-stable choice for storage; NFKC-style aggression lives
 * only in {@link #foldForSearch}, and only for the specific, enumerated folds below. Every function
 * here is idempotent: {@code f(f(x)).equals(f(x))}.
 */
public final class Text {

  private Text() {}

  /** Runs of Unicode whitespace (incl. NBSP, tabs, newlines, line/para separators). */
  private static final Pattern WHITESPACE_RUN = Pattern.compile("(?U)\\s+");

  private static final char TATWEEL = 'ـ';
  private static final int SUPERSCRIPT_ALEF = 0x0670; // combining, counted with the harakat below

  /**
   * Canonical <strong>storage</strong> form for human free-text (names, notes, addresses,
   * product/category/org names): NFC, strip invisible bidi and zero-width format controls, collapse
   * every internal whitespace run to a single space, trim, and map empty to {@code null}.
   * Idempotent and loss-free for letter identity — Arabic diacritics (tashkeel) and the
   * semantically meaningful joiners ZWNJ ({@code U+200C}) / ZWJ ({@code U+200D}) are preserved
   * (they carry orthographic meaning in Arabic/Persian names).
   *
   * @return the canonical value, or {@code null} if {@code s} is {@code null} or blank once
   *     cleaned.
   */
  public static String normalizeText(String s) {
    if (s == null) return null;
    // Fold the Arabic presentation-forms blocks to logical letters before the global NFC, so a
    // name pasted from that legacy block keys the same as its typed twin. Surgical on purpose: a
    // blanket NFKC would also destroy full-width Latin, superscripts, and non-Arabic ligatures —
    // the very distinctions the "NFC, not NFKC" rule protects (see class Javadoc).
    String deform = foldArabicPresentationForms(s);
    String nfc = Normalizer.normalize(deform, Normalizer.Form.NFC);
    String stripped = stripFormatChars(nfc, /* stripJoiners= */ false);
    String collapsed = WHITESPACE_RUN.matcher(stripped).replaceAll(" ").strip();
    return collapsed.isEmpty() ? null : collapsed;
  }

  /**
   * Storage form for <strong>numeric-semantic</strong> text (phone, external/provider refs,
   * barcodes, tracking numbers): everything {@link #normalizeText} does, plus fold Arabic-Indic
   * ({@code U+0660–0669}) and Extended/Persian Arabic ({@code U+06F0–06F9}) digits to ASCII {@code
   * 0–9}, and additionally strip the ZWNJ/ZWJ joiners (an identifier has no orthography to
   * preserve, and an invisible joiner inside a reference is pure spoofing surface). Non-digits are
   * kept — a phone may legitimately carry {@code '+'} — so this is not a "digits only" filter.
   *
   * @return the canonical value, or {@code null} if {@code s} is {@code null} or blank once
   *     cleaned.
   */
  public static String normalizeNumeric(String s) {
    if (s == null) return null;
    String nfc = Normalizer.normalize(s, Normalizer.Form.NFC);
    String stripped = stripFormatChars(nfc, /* stripJoiners= */ true);
    String folded = foldDigits(stripped);
    String collapsed = WHITESPACE_RUN.matcher(folded).replaceAll(" ").strip();
    return collapsed.isEmpty() ? null : collapsed;
  }

  /**
   * Lossy <strong>match</strong> key for search and meaning-based retrieval — never displayed,
   * never stored as the value, only as a derived {@code *_search} column. Starts from the storage
   * form, then aggressively folds so that visually/linguistically equivalent Arabic spellings
   * collapse to one key: strip tashkeel/harakat ({@code U+064B–0652}, {@code U+0670}), strip
   * tatweel/kashida ({@code U+0640}), unify the alef seats ({@code أإآٱ→ا}), waw-/yaa-hamza ({@code
   * ؤ→و}, {@code ئ→ي}), alef maqsura ({@code ى→ي}), taa marbuta ({@code ة→ه}), drop the standalone
   * hamza ({@code ء}) and the joiners, fold digits, and casefold Latin. So {@code "أحمد" / "إحمد" /
   * "آحمد" / "احمد"} all key to the same value, and {@code "مُحَمَّد"} keys to {@code "محمد"}.
   * Idempotent.
   *
   * @return the folded key, or {@code null} if {@code s} is {@code null} or blank once cleaned.
   */
  public static String foldForSearch(String s) {
    String base = normalizeText(s);
    if (base == null) return null;
    StringBuilder sb = new StringBuilder(base.length());
    base.codePoints()
        .forEach(
            cp -> {
              // Drop tashkeel/harakat, tatweel, standalone hamza, and the joiners entirely.
              if (cp >= 0x064B && cp <= 0x0652) return;
              if (cp == SUPERSCRIPT_ALEF) return;
              if (cp == TATWEEL) return;
              if (cp == 0x0621) return; // standalone hamza
              if (cp == 0x200C || cp == 0x200D) return; // ZWNJ / ZWJ
              int folded =
                  switch (cp) {
                    case 0x0623, 0x0625, 0x0622, 0x0671 -> 0x0627; // alef seats → bare alef
                    case 0x0624 -> 0x0648; // waw-hamza → waw
                    case 0x0626 -> 0x064A; // yaa-hamza → yaa
                    case 0x0649 -> 0x064A; // alef maqsura → yaa
                    case 0x0629 -> 0x0647; // taa marbuta → haa
                    default -> cp;
                  };
              appendFoldedDigit(sb, folded);
            });
    // NFC after folding keeps the key stable/idempotent; casefold Latin with the root locale so the
    // Turkish dotless-i rule can never make the fold locale-dependent.
    String result =
        Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    return result.isEmpty() ? null : result;
  }

  /**
   * ASCII e-mail normalization: NFC, strip format controls, trim, lowercase (root locale). Replaces
   * the divergent per-plane helpers (some trimmed without lowercasing, one stored the address raw),
   * so a given address keys identically everywhere. E-mail is ASCII-domain in v1 — IDN/EAI (Unicode
   * domains or local-parts) is out of scope and validated separately, downstream of this call.
   *
   * @return the normalized address, or {@code null} if {@code s} is {@code null} or blank.
   */
  public static String normalizeEmail(String s) {
    if (s == null) return null;
    String nfc = Normalizer.normalize(s, Normalizer.Form.NFC);
    String cleaned =
        stripFormatChars(nfc, /* stripJoiners= */ true).strip().toLowerCase(Locale.ROOT);
    return cleaned.isEmpty() ? null : cleaned;
  }

  /**
   * Currency-code normalization: trim, uppercase (root locale), default {@code "EGP"} when blank.
   * Behaviour-preserving consolidation of the duplicated {@code normalizeCurrency} helpers.
   */
  public static String normalizeCurrency(String s) {
    if (s == null || s.isBlank()) return "EGP";
    return s.strip().toUpperCase(Locale.ROOT);
  }

  // --- internals -------------------------------------------------------------------------------

  /**
   * Drop Unicode format characters (category {@code Cf}) — BOM, bidi embeddings/overrides/isolates,
   * LRM/RLM, ZWSP, soft hyphen, etc. When {@code stripJoiners} is false, ZWNJ/ZWJ survive (needed
   * in display text); when true they go too (identifier context).
   */
  private static String stripFormatChars(String s, boolean stripJoiners) {
    StringBuilder sb = new StringBuilder(s.length());
    s.codePoints()
        .forEach(
            cp -> {
              if (Character.getType(cp) == Character.FORMAT) {
                if (!stripJoiners && (cp == 0x200C || cp == 0x200D)) {
                  sb.appendCodePoint(cp);
                }
                return; // otherwise drop the format char
              }
              sb.appendCodePoint(cp);
            });
    return sb.toString();
  }

  /**
   * Compatibility-fold ONLY the Arabic Presentation Forms-A ({@code U+FB50–FDFF}) and Forms-B
   * ({@code U+FE70–FEFF}) blocks to their logical letters, by applying NFKC per-code-point over
   * just those ranges. This is the one place a compatibility decomposition is used in storage — it
   * is surgical on purpose, so Latin presentation ligatures ({@code U+FB00–FB4F}), full-width
   * forms, and superscripts are untouched. Returns the input unchanged when it contains no such
   * code point.
   */
  private static String foldArabicPresentationForms(String s) {
    boolean any = s.codePoints().anyMatch(Text::isArabicPresentationForm);
    if (!any) return s;
    StringBuilder sb = new StringBuilder(s.length());
    s.codePoints()
        .forEach(
            cp -> {
              if (isArabicPresentationForm(cp)) {
                sb.append(
                    Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC));
              } else {
                sb.appendCodePoint(cp);
              }
            });
    return sb.toString();
  }

  private static boolean isArabicPresentationForm(int cp) {
    return (cp >= 0xFB50 && cp <= 0xFDFF) || (cp >= 0xFE70 && cp <= 0xFEFF);
  }

  private static String foldDigits(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    s.codePoints().forEach(cp -> appendFoldedDigit(sb, cp));
    return sb.toString();
  }

  private static void appendFoldedDigit(StringBuilder sb, int cp) {
    if (cp >= 0x0660 && cp <= 0x0669) {
      sb.append((char) ('0' + (cp - 0x0660))); // Arabic-Indic
    } else if (cp >= 0x06F0 && cp <= 0x06F9) {
      sb.append((char) ('0' + (cp - 0x06F0))); // Extended (Persian) Arabic-Indic
    } else {
      sb.appendCodePoint(cp);
    }
  }
}
