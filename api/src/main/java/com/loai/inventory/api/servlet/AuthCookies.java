package com.loai.inventory.api.servlet;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Shared writer for auth cookies. Extracted so login/refresh, impersonation, and stop-impersonation
 * emit identical cookie attributes — and so impersonation can write the access cookie
 * <em>only</em>, never the refresh cookie (an overlay is a throwaway access token; the driver's
 * refresh session is left untouched).
 */
public final class AuthCookies {

  public static final int REFRESH_MAX_AGE = 604800; // 7 days
  private static final String REFRESH_PATH = "/api/auth";

  private AuthCookies() {}

  /** Set the {@code access_token} cookie (root path). */
  public static void writeAccess(
      HttpServletResponse resp, String token, int maxAgeSeconds, boolean secure) {
    Cookie c = new Cookie("access_token", token);
    c.setHttpOnly(true);
    c.setSecure(secure);
    c.setPath("/");
    c.setMaxAge(maxAgeSeconds);
    c.setAttribute("SameSite", "Strict");
    resp.addCookie(c);
  }

  /** Set the {@code refresh_token} cookie (scoped to {@code /api/auth}). */
  public static void writeRefresh(
      HttpServletResponse resp, String token, int maxAgeSeconds, boolean secure) {
    Cookie c = new Cookie("refresh_token", token);
    c.setHttpOnly(true);
    c.setSecure(secure);
    c.setPath(REFRESH_PATH);
    c.setMaxAge(maxAgeSeconds);
    c.setAttribute("SameSite", "Strict");
    resp.addCookie(c);
  }

  public static void clear(HttpServletResponse resp, String name, String path, boolean secure) {
    Cookie c = new Cookie(name, "");
    c.setHttpOnly(true);
    c.setSecure(secure);
    c.setPath(path);
    c.setMaxAge(0);
    c.setAttribute("SameSite", "Strict");
    resp.addCookie(c);
  }
}
