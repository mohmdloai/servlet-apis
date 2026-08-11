package com.loai.inventory.common.text;

/**
 * Turns a human-typed phone number into <strong>E.164</strong> — the only form a messaging provider
 * can dial ({@code +201012345678}). Stateless and JDK-only, like {@link Text}, whose {@code
 * normalizeNumeric} it builds on.
 *
 * <p><strong>Why this exists.</strong> {@code customer.phone} looks like a phone number and is not
 * one. It is stored through {@link Text#normalizeNumeric}, which folds Arabic-Indic/Persian digits
 * and strips invisible controls — genuinely useful — but which <em>deliberately keeps
 * non-digits</em> ("a phone may legitimately carry {@code +}") and only collapses whitespace. So
 * {@code 01012345678}, {@code +20 100 123 4567}, {@code 0100-123-4567} and {@code ٠١٠١٢٣٤٥٦٧٨} are
 * all valid, live values for the same person, and none of them can be handed to WhatsApp or an SMS
 * gateway. This class is the missing canonicalization; the result is stored beside the typed value
 * in {@code customer.phone_e164} (the storage-form / derived-form split {@code Text} already
 * established with {@code name} / {@code name_search}).
 *
 * <p><strong>Egypt-first, by decision.</strong> The rules below encode Egypt's numbering plan
 * (country code {@code 20}, mobile {@code 1[0125]} + 8 digits, landline area codes {@code 2}–{@code
 * 9}) because that is the market this system serves; a full worldwide parser would mean a
 * metadata-heavy dependency in {@code common}, the module every other module inherits. An
 * already-international number is passed through on a length check, so a foreign number typed
 * correctly still canonicalizes. If non-Egyptian traffic ever justifies it, swapping the body of
 * {@link #toE164} for libphonenumber is a one-class change — nothing else reads the rules.
 *
 * <p><strong>Fail-open contract: an unparseable number returns {@code null}, never an
 * exception.</strong> Callers store the null and carry on — an unreachable number is a suppressed
 * channel, exactly like a customer with no email address, never a failed checkout. This class must
 * therefore stay total: every input, including deliberate garbage, produces either a valid E.164
 * string or {@code null}.
 *
 * <p><strong>When in doubt it returns {@code null}, not a guess.</strong> A bare number that is
 * neither trunk-prefixed nor explicitly international and does not parse as Egyptian would need an
 * invented country code, and a plausible-looking wrong number in a column whose entire purpose is
 * to be dialable is worse than an honest "unknown".
 */
public final class Phone {

  private Phone() {}

  /** Egypt's E.164 country calling code. */
  private static final String EGYPT = "20";

  /** The international access code ("dial out") prefix, i.e. what {@code +} replaces. */
  private static final String INTERNATIONAL_ACCESS = "00";

  /** E.164 caps a full number at 15 digits; below 8 nothing is a real subscriber line. */
  private static final int MAX_DIGITS = 15;

  private static final int MIN_DIGITS = 8;

  /** Egyptian mobile: {@code 1} then one of these, then 8 subscriber digits. */
  private static final String EGYPT_MOBILE_PREFIXES = "0125";

  private static final int EGYPT_MOBILE_LENGTH = 10;

  /**
   * Canonicalize {@code raw} to E.164 with a leading {@code +}, or {@code null} when it cannot be
   * understood as a dialable number.
   *
   * <p>Idempotent: {@code toE164(toE164(x))} equals {@code toE164(x)}, so re-running it over
   * already-canonical data (a backfill re-run, a profile save that changed only the name) is safe.
   */
  public static String toE164(String raw) {
    // Fold Arabic-Indic/Persian digits and strip invisible controls first — a number pasted from an
    // Arabic-locale keyboard or SMS must canonicalize identically to its ASCII twin.
    String cleaned = Text.normalizeNumeric(raw);
    if (cleaned == null) {
      return null;
    }
    boolean explicitlyInternational = cleaned.startsWith("+");
    String digits = digitsOnly(cleaned);
    if (digits.isEmpty()) {
      return null;
    }

    String candidate;
    if (explicitlyInternational) {
      candidate = digits;
    } else if (digits.startsWith(INTERNATIONAL_ACCESS)) {
      candidate = digits.substring(INTERNATIONAL_ACCESS.length());
    } else if (digits.startsWith("0")) {
      // A leading zero is the national trunk prefix: drop it and apply the local country code.
      candidate = EGYPT + digits.substring(1);
    } else if (digits.startsWith(EGYPT) && isEgyptianNationalNumber(digits.substring(2))) {
      // Country-coded already, just missing its "+".
      candidate = digits;
    } else if (isEgyptianNationalNumber(digits)) {
      // National number with the trunk prefix omitted — common when typed from memory.
      candidate = EGYPT + digits;
    } else {
      // Not trunk-prefixed, not marked international, not recognizably Egyptian: assigning a
      // country code here would be a guess. See the class note — null beats a wrong number.
      return null;
    }
    return isPlausible(candidate) ? "+" + candidate : null;
  }

  /** Keep only ASCII digits; {@code +}, spaces, dashes and parentheses are formatting, not data. */
  private static String digitsOnly(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * A country code never starts with {@code 0}, and E.164 bounds the total length; an Egyptian
   * number is additionally held to Egypt's own plan so a mistyped local number is rejected rather
   * than canonicalized into something undialable.
   */
  private static boolean isPlausible(String candidate) {
    if (candidate.length() < MIN_DIGITS || candidate.length() > MAX_DIGITS) {
      return false;
    }
    if (candidate.charAt(0) == '0') {
      return false;
    }
    if (candidate.startsWith(EGYPT)) {
      return isEgyptianNationalNumber(candidate.substring(2));
    }
    return true;
  }

  /**
   * Egypt's national significant number: a 10-digit mobile ({@code 10/11/12/15} + 8 digits) or an
   * 8–9 digit landline behind a {@code 2}–{@code 9} area code. Landlines are accepted on the area
   * code's first digit rather than an enumerated list — the list changes, and over-accepting a
   * landline only means a messaging channel will find it undeliverable, while under-accepting would
   * silently drop a real contact number.
   */
  private static boolean isEgyptianNationalNumber(String nsn) {
    if (nsn.length() == EGYPT_MOBILE_LENGTH) {
      return nsn.charAt(0) == '1' && EGYPT_MOBILE_PREFIXES.indexOf(nsn.charAt(1)) >= 0;
    }
    if (nsn.length() == 8 || nsn.length() == 9) {
      char area = nsn.charAt(0);
      return area >= '2' && area <= '9';
    }
    return false;
  }
}
