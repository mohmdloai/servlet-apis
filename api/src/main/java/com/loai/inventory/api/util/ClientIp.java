package com.loai.inventory.api.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one resolver of "which IP is this request really from" (stories/session_source_ip.md).
 *
 * <p>Default: the connecting socket ({@code getRemoteAddr()}). With {@code TRUST_PROXY=true}: the
 * first hop of {@code X-Forwarded-For} (the originating client the proxy recorded), falling back to
 * {@code getRemoteAddr()} when the header is absent/blank. Behind the deploy's Caddy container the
 * socket peer is the proxy's Docker-network address, so before this existed every session row
 * stamped the proxy hop (10.0.x.x) instead of the user's address — {@code TRUST_PROXY} was already
 * on in prod but only {@code RateLimitFilter} consulted it.
 *
 * <p>Same trust rule as the filter, single-sourced here so the two can never drift: XFF is
 * client-spoofable, so the header is honoured only when an operator declares the proxy overwrites
 * it. The env is read once at class load, matching the filter's construction-time read.
 */
public final class ClientIp {

  private static final boolean TRUST_PROXY = Boolean.parseBoolean(System.getenv("TRUST_PROXY"));

  private ClientIp() {}

  /** The client IP to record/key on, honouring {@code TRUST_PROXY}. */
  public static String resolve(HttpServletRequest req) {
    return resolve(req, TRUST_PROXY);
  }

  /** Explicit-trust variant — the filter passes its constructor-injected flag; tests too. */
  public static String resolve(HttpServletRequest req, boolean trustProxy) {
    if (trustProxy) {
      String xff = req.getHeader("X-Forwarded-For");
      if (xff != null && !xff.isBlank()) {
        String first = xff.split(",", 2)[0].trim();
        if (!first.isEmpty()) {
          return first;
        }
      }
    }
    return req.getRemoteAddr();
  }
}
