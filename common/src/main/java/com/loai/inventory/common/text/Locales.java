package com.loai.inventory.common.text;

import java.util.Set;

/**
 * The storefront's supported content locales and the one resolution rule everything should share.
 *
 * <p><b>Why this exists.</b> {@code SUPPORTED_LOCALES} and a near-identical {@code
 * resolveRequestedLocale} are currently declared privately in six services ({@code
 * StorefrontService}, {@code StorefrontPageService}, {@code CollectionService}, {@code
 * CategoryService}, {@code ProductListingService}, {@code CustomerPortalService}). Adding a seventh
 * copy for notifications is exactly the drift this codebase keeps structurally impossible
 * elsewhere, so the new code consumes this instead. Migrating the existing six is a mechanical
 * follow-up, deliberately not bundled into a localization slice — see {@code
 * stories/localized_notification_templates.md} §Out.
 *
 * <p>Stateless and JDK-only, like its neighbours {@link Text} and {@link Phone}.
 */
public final class Locales {

  private Locales() {}

  /** Arabic — the market default, and {@code org.default_locale}'s own DB default (V52). */
  public static final String ARABIC = "ar";

  public static final String ENGLISH = "en";

  /** Exactly what {@code org.default_locale}'s CHECK constraint allows (V52). */
  public static final Set<String> SUPPORTED = Set.of(ARABIC, ENGLISH);

  /**
   * The canonical form of a locale tag, or {@code null} when it names nothing we serve. Accepts a
   * region subtag and drops it ({@code ar-EG} → {@code ar}): a browser or an {@code
   * Accept-Language} header routinely sends one, and refusing it would reject a locale we do in
   * fact support.
   */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.strip().toLowerCase();
    int dash = s.indexOf('-');
    if (dash > 0) {
      s = s.substring(0, dash);
    }
    return SUPPORTED.contains(s) ? s : null;
  }

  public static boolean isSupported(String raw) {
    return normalize(raw) != null;
  }

  /**
   * Resolve the locale to render in: the subject's own preference, else the org's default, else
   * Arabic.
   *
   * <p>Total by design — it never throws and never returns null. This is called from inside
   * business transactions (notification production runs in the caller's txn and must not roll an
   * order back), so an unrecognised stored value degrades to the org default rather than failing
   * the sale. Endpoints that want to *reject* an unknown {@code ?locale=} still do so at their own
   * boundary with {@link #isSupported}; that is a different question from what to render.
   */
  public static String resolve(String preferred, String orgDefault) {
    String p = normalize(preferred);
    if (p != null) {
      return p;
    }
    String d = normalize(orgDefault);
    return d != null ? d : ARABIC;
  }

  /** {@code "rtl"} for Arabic, else {@code "ltr"} — the direction an email body must declare. */
  public static String direction(String locale) {
    return ARABIC.equals(normalize(locale)) ? "rtl" : "ltr";
  }
}
