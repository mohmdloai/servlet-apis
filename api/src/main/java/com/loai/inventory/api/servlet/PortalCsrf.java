package com.loai.inventory.api.servlet;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/**
 * CSRF posture for the customer portal (epic decision #9), layered on the cookies' {@code
 * SameSite=Strict}:
 *
 * <ul>
 *   <li><b>Custom header</b> — every {@code /api/portal/*} call (and the OTP bootstrap) must carry
 *       {@code X-Portal-Request: 1}. A cross-site HTML {@code <form>} cannot set a custom header,
 *       so this alone defeats form-based CSRF; a non-simple {@code fetch} triggers a CORS preflight
 *       the browser blocks for a disallowed origin.
 *   <li><b>Origin allowlist</b> — mutations (POST/PATCH/DELETE) additionally require the {@code
 *       Origin}/{@code Referer}, when present, to be one of {@code CORS_ALLOWED_ORIGINS}.
 * </ul>
 *
 * A double-submit token is a documented later hardening, not v1.
 */
public final class PortalCsrf {

  public static final String HEADER = "X-Portal-Request";

  private PortalCsrf() {}

  /** True iff the request carries the required {@code X-Portal-Request: 1} header. */
  public static boolean hasHeader(HttpServletRequest req) {
    return "1".equals(req.getHeader(HEADER));
  }

  /**
   * True iff the request's origin is acceptable for a mutation: an {@code Origin} (else {@code
   * Referer}) that, when present, matches the allowlist. Absent on both → allowed (a non-browser
   * client; the header check + {@code SameSite} already carry the guard).
   */
  public static boolean originAllowed(HttpServletRequest req, Set<String> allowedOrigins) {
    String origin = req.getHeader("Origin");
    if (origin != null && !origin.isBlank()) {
      return allowedOrigins.contains(origin.trim());
    }
    String referer = req.getHeader("Referer");
    if (referer != null && !referer.isBlank()) {
      for (String allowed : allowedOrigins) {
        if (referer.startsWith(allowed)) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  /** Whether an HTTP method mutates state (and so gets the extra Origin check). */
  public static boolean isMutation(String method) {
    return "POST".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
  }
}
