package com.loai.inventory.api.servlet;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Shared writer for the customer-portal cookies. Distinct <em>names</em> from the staff {@code
 * access_token}/{@code refresh_token}, HttpOnly + {@code SameSite=Strict} + {@code Secure} (when
 * {@code COOKIE_SECURE}). Mirrors {@link AuthCookies}.
 *
 * <p><b>Paths (mirrors the staff spine).</b> {@code customer_access} is scoped to {@code /} — like
 * the staff {@code access_token} — so it rides <em>page</em> navigations too, not just {@code
 * /api/portal/*}. The storefront's server-layout account gate reads it from {@code next/headers} on
 * the {@code /{orgSlug}/account} request and forwards it on the server-side {@code /api/portal/me}
 * call; a {@code /api/portal}-scoped access cookie is invisible on that page request, so the SSR
 * gate could never see the session. Isolation is preserved without the tight path: a customer token
 * presented on the staff plane is rejected by {@code JwtAuthFilter}'s {@code aud=customer} check
 * (plus the separate signing key). {@code customer_refresh} stays tightly scoped to {@code
 * /api/portal/auth} — it is only ever presented to the refresh/logout endpoints. (Refines epic
 * decision #6, which over-tightened the access path and broke the documented SSR gate.)
 */
public final class CustomerAuthCookies {

  public static final String ACCESS_COOKIE = "customer_access";
  public static final String REFRESH_COOKIE = "customer_refresh";
  public static final String HINT_COOKIE = "customer_hint";
  public static final String ACCESS_PATH = "/";
  public static final String REFRESH_PATH = "/api/portal/auth";

  private CustomerAuthCookies() {}

  public static void writeAccess(
      HttpServletResponse resp, String token, int maxAgeSeconds, boolean secure) {
    resp.addCookie(cookie(ACCESS_COOKIE, token, ACCESS_PATH, maxAgeSeconds, secure, true));
  }

  public static void writeRefresh(
      HttpServletResponse resp, String token, int maxAgeSeconds, boolean secure) {
    resp.addCookie(cookie(REFRESH_COOKIE, token, REFRESH_PATH, maxAgeSeconds, secure, true));
  }

  /**
   * The UI-only session hint (slice R1, epic §11): {@code customer_hint=1}, deliberately <b>not</b>
   * HttpOnly — client JS on the cookie-free ISR listing page reads it to branch the review CTA
   * without firing a portal call for anonymous shoppers. No server code path ever reads it; the
   * authority stays the HttpOnly session cookie + filter. Rides every response that writes the
   * session cookies (verify-code, refresh) with the <em>session</em> lifetime (the refresh
   * max-age), and is cleared with them on logout(-all). A stale hint (dead session) is harmless:
   * the first portal call 401s and the UI degrades to signed-out.
   */
  public static void writeHint(HttpServletResponse resp, int maxAgeSeconds, boolean secure) {
    resp.addCookie(cookie(HINT_COOKIE, "1", ACCESS_PATH, maxAgeSeconds, secure, false));
  }

  public static void clearAll(HttpServletResponse resp, boolean secure) {
    resp.addCookie(cookie(ACCESS_COOKIE, "", ACCESS_PATH, 0, secure, true));
    resp.addCookie(cookie(REFRESH_COOKIE, "", REFRESH_PATH, 0, secure, true));
    resp.addCookie(cookie(HINT_COOKIE, "", ACCESS_PATH, 0, secure, false));
  }

  private static Cookie cookie(
      String name, String value, String path, int maxAge, boolean secure, boolean httpOnly) {
    Cookie c = new Cookie(name, value);
    c.setHttpOnly(httpOnly);
    c.setSecure(secure);
    c.setPath(path);
    c.setMaxAge(maxAge);
    c.setAttribute("SameSite", "Strict");
    return c;
  }
}
