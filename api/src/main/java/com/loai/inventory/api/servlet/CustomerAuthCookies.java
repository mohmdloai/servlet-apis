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
  public static final String ACCESS_PATH = "/";
  public static final String REFRESH_PATH = "/api/portal/auth";

  private CustomerAuthCookies() {}

  public static void writeAccess(
      HttpServletResponse resp, String token, int maxAgeSeconds, boolean secure) {
    resp.addCookie(cookie(ACCESS_COOKIE, token, ACCESS_PATH, maxAgeSeconds, secure));
  }

  public static void writeRefresh(
      HttpServletResponse resp, String token, int maxAgeSeconds, boolean secure) {
    resp.addCookie(cookie(REFRESH_COOKIE, token, REFRESH_PATH, maxAgeSeconds, secure));
  }

  public static void clearAll(HttpServletResponse resp, boolean secure) {
    resp.addCookie(cookie(ACCESS_COOKIE, "", ACCESS_PATH, 0, secure));
    resp.addCookie(cookie(REFRESH_COOKIE, "", REFRESH_PATH, 0, secure));
  }

  private static Cookie cookie(String name, String value, String path, int maxAge, boolean secure) {
    Cookie c = new Cookie(name, value);
    c.setHttpOnly(true);
    c.setSecure(secure);
    c.setPath(path);
    c.setMaxAge(maxAge);
    c.setAttribute("SameSite", "Strict");
    return c;
  }
}
