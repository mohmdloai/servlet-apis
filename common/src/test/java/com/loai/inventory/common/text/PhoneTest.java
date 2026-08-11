package com.loai.inventory.common.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link Phone#toE164} — the canonicalization every phone-based channel depends on. The cases below
 * are the shapes that are actually live in {@code customer.phone} today, because nothing has ever
 * validated that column.
 */
class PhoneTest {

  private static final String CANONICAL = "+201012345678";

  @Nested
  @DisplayName("Egyptian mobile numbers, however they were typed")
  class EgyptianMobiles {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "01012345678", // the way an Egyptian writes it
          "+201012345678", // already canonical
          "00201012345678", // international access code instead of "+"
          "201012345678", // country-coded, missing the "+"
          "1012345678", // trunk prefix omitted (typed from memory)
          "0101 234 5678", // spaces
          "0101-234-5678", // dashes
          "(0101) 234 5678", // parentheses
          "٠١٠١٢٣٤٥٦٧٨", // Arabic-Indic
          "۰۱۰۱۲۳۴۵۶۷۸" // Persian digits
        })
    void allCanonicalizeToTheSameNumber(String raw) {
      assertEquals(CANONICAL, Phone.toE164(raw));
    }

    @Test
    void everyEgyptianMobilePrefixIsAccepted() {
      // 010 Vodafone, 011 Etisalat, 012 Orange, 015 WE — all four networks must round-trip.
      assertEquals("+201012345678", Phone.toE164("01012345678"));
      assertEquals("+201112345678", Phone.toE164("01112345678"));
      assertEquals("+201212345678", Phone.toE164("01212345678"));
      assertEquals("+201512345678", Phone.toE164("01512345678"));
    }

    @Test
    void anUnassignedMobilePrefixIsRejected() {
      // 013/014 are not Egyptian mobile prefixes; 10 digits alone is not enough to accept.
      assertNull(Phone.toE164("01312345678"));
      assertNull(Phone.toE164("01412345678"));
    }
  }

  @Nested
  @DisplayName("Egyptian landlines")
  class EgyptianLandlines {

    @Test
    void cairoAndGovernorateLandlinesCanonicalize() {
      assertEquals("+20223456789", Phone.toE164("0223456789")); // Cairo, 9-digit NSN
      assertEquals("+2034567890", Phone.toE164("034567890")); // Alexandria, 8-digit NSN
    }

    @Test
    void aLandlineAreaCodeOfZeroOrOneIsRejected() {
      // "1" is the mobile space and "0" is the trunk prefix — neither is an area code.
      assertNull(Phone.toE164("+2012345678"));
    }
  }

  @Nested
  @DisplayName("International numbers")
  class International {

    @Test
    void anExplicitlyInternationalNumberPassesThroughOnLength() {
      assertEquals("+14155550132", Phone.toE164("+1 415 555 0132"));
      assertEquals("+447911123456", Phone.toE164("00 44 7911 123456"));
      assertEquals("+971501234567", Phone.toE164("+971 50 123 4567"));
    }

    @Test
    void aBareForeignNumberWithNoMarkerIsNullRatherThanAGuess() {
      // No "+", no "00", no leading zero, and not Egyptian — assigning a country code would be an
      // invention, and a wrong dialable-looking number is worse than an honest unknown.
      assertNull(Phone.toE164("4155550132"));
    }
  }

  @Nested
  @DisplayName("The fail-open contract: garbage returns null, never an exception")
  class FailOpen {

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "-", "+", "abc", "12345", "0", "00", "n/a", "call me"})
    void unusableInputReturnsNull(String raw) {
      assertNull(Phone.toE164(raw));
    }

    @Test
    void nullInReturnsNullOut() {
      assertNull(Phone.toE164(null));
    }

    @Test
    void tooLongForE164IsRejected() {
      assertNull(Phone.toE164("+1234567890123456")); // 16 digits, one over the E.164 cap
    }

    @Test
    void aNumberThatIsAllFormattingIsRejected() {
      assertNull(Phone.toE164("()- ()"));
    }
  }

  @Test
  void isIdempotent_soABackfillCanBeReRunSafely() {
    String once = Phone.toE164("0101 234 5678");
    assertEquals(once, Phone.toE164(once));
    assertEquals(CANONICAL, once);
  }
}
