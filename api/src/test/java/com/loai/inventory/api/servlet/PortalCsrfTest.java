package com.loai.inventory.api.servlet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The portal's CSRF rules as pure decisions (D11). {@code CustomerAuthFilterTest} proves the filter
 * consults them; this proves what they answer, including the two judgement calls that are easy to
 * "fix" into a hole.
 */
class PortalCsrfTest {

  private static final Set<String> ALLOWED =
      Set.of("https://shop.example", "https://admin.example");

  private static HttpServletRequest req(String method, String origin, String referer, String hdr) {
    HttpServletRequest r = mock(HttpServletRequest.class);
    when(r.getMethod()).thenReturn(method);
    when(r.getHeader("Origin")).thenReturn(origin);
    when(r.getHeader("Referer")).thenReturn(referer);
    when(r.getHeader(PortalCsrf.HEADER)).thenReturn(hdr);
    return r;
  }

  @Test
  void theHeaderMustBeExactlyOne() {
    assertTrue(PortalCsrf.hasHeader(req("GET", null, null, "1")));
    assertFalse(PortalCsrf.hasHeader(req("GET", null, null, null)));
    assertFalse(PortalCsrf.hasHeader(req("GET", null, null, "")));
    // Not "truthy" — a cross-site form cannot set a custom header at all, so the value is a
    // constant, not a flag to be parsed leniently.
    assertFalse(PortalCsrf.hasHeader(req("GET", null, null, "true")));
    assertFalse(PortalCsrf.hasHeader(req("GET", null, null, "0")));
  }

  @Test
  void everyStateChangingVerbIsAMutation() {
    for (String m : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
      assertTrue(PortalCsrf.isMutation(m), m + " must take the Origin check");
    }
    for (String m : new String[] {"GET", "HEAD", "OPTIONS"}) {
      assertFalse(PortalCsrf.isMutation(m));
    }
  }

  @Test
  void anAllowlistedOriginPasses_andAForeignOneDoesNot() {
    assertTrue(PortalCsrf.originAllowed(req("POST", "https://shop.example", null, "1"), ALLOWED));
    assertFalse(PortalCsrf.originAllowed(req("POST", "https://evil.example", null, "1"), ALLOWED));
  }

  @Test
  void refererIsTheFallbackWhenOriginIsAbsent() {
    assertTrue(
        PortalCsrf.originAllowed(req("POST", null, "https://shop.example/cart", null), ALLOWED));
    assertFalse(
        PortalCsrf.originAllowed(req("POST", null, "https://evil.example/cart", null), ALLOWED));
  }

  /**
   * Origin wins outright. A request carrying both must not be able to launder a foreign Origin past
   * the check by attaching an allowlisted Referer.
   */
  @Test
  void aForeignOriginIsNotRescuedByAnAllowlistedReferer() {
    assertFalse(
        PortalCsrf.originAllowed(
            req("POST", "https://evil.example", "https://shop.example/", "1"), ALLOWED),
        "Origin is the stronger signal — a Referer must never override it");
  }

  /**
   * Neither header present → allowed, and that is deliberate: browsers always send at least one on
   * a cross-site request, so this case is a non-browser client, where the required custom header
   * plus {@code SameSite=Strict} already carry the guard. Pinned because it looks like a hole and
   * is not one — anyone tightening it should have to change this test on purpose.
   */
  @Test
  void neitherHeader_isAllowed_becauseThatIsNotABrowser() {
    assertTrue(PortalCsrf.originAllowed(req("POST", null, null, "1"), ALLOWED));
  }

  @Test
  void blankHeadersAreTreatedAsAbsent() {
    assertTrue(PortalCsrf.originAllowed(req("POST", "   ", "  ", "1"), ALLOWED));
  }

  @Test
  void anEmptyAllowlistRefusesEveryBrowserOrigin() {
    assertFalse(PortalCsrf.originAllowed(req("POST", "https://shop.example", null, "1"), Set.of()));
  }
}
