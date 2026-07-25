package com.loai.inventory.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Per-IP fixed-window (Redis {@code INCR} + 60s TTL) rate limiter. Mapped to {@code /api/auth/*}
 * (login/refresh) and {@code /api/public/*} (the anonymous storefront). Each request selects a
 * bucket by method + path:
 *
 * <ul>
 *   <li>{@code /login} → {@code rl:login:} (10/min), {@code /refresh} → {@code rl:refresh:}
 *       (30/min) — the pre-existing auth buckets, untouched.
 *   <li>{@code POST /api/auth/register} → {@code rl:auth-register:} (strict, {@code
 *       AUTH_REGISTER_LIMIT}, default 3/min) and {@code /api/auth/forgot-password} → {@code
 *       rl:auth-forgot:} ({@code AUTH_FORGOT_LIMIT}, default 5/min) — story 87; both previously
 *       fell through the fail-open else. {@code /reset-password} and {@code /activate} stay
 *       unbucketed on purpose: they redeem 256-bit single-use tokens — the token space is the rate
 *       limit.
 *   <li>{@code POST /api/public/**​/checkout} → {@code rl:pub-checkout:} (strict, {@code
 *       PUBLIC_CHECKOUT_LIMIT}, default 5/min) — a checkout is an expensive anonymous write
 *       (customer upsert + stock reservation + magic link + email).
 *   <li>every other {@code /api/public/*} request → {@code rl:pub-read:} (generous, {@code
 *       PUBLIC_READ_LIMIT}, default 120/min).
 *   <li>{@code POST /api/portal/checkout} → {@code rl:portal-checkout:} (strict, mirrors {@code
 *       rl:pub-checkout:} at {@code PUBLIC_CHECKOUT_LIMIT} — slice P6); other {@code /api/portal/*}
 *       requests keep the generous {@code rl:portal-read:} bucket.
 * </ul>
 *
 * <p>The buckets use distinct key prefixes, so the read and checkout counters are fully
 * independent. See {@code stories/public_rate_limiting.md} (B4).
 *
 * <p><b>Client-IP resolution behind a proxy.</b> A storefront normally sits behind a CDN/LB, where
 * {@code getRemoteAddr()} is the proxy's IP — keying on it would put every shopper in one bucket
 * and self-DoS the store. When {@code TRUST_PROXY=true} the key is the first hop of {@code
 * X-Forwarded-For} (the originating client), falling back to {@code getRemoteAddr()} when the
 * header is absent/blank. It is gated (default false) because XFF is client-spoofable unless a
 * proxy we control overwrites it — only enable it once the edge proxy is confirmed to strip inbound
 * XFF.
 */
public class RateLimitFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

  private static final int LOGIN_LIMIT = 10;
  private static final int REFRESH_LIMIT = 30;
  private static final int WINDOW_SECONDS = 60;

  static final int DEFAULT_PUBLIC_READ_LIMIT = 120;
  static final int DEFAULT_PUBLIC_CHECKOUT_LIMIT = 5;

  // Customer-portal buckets (per-IP; the per-email OTP throttle lives in CustomerOtpStore). Tight
  // on
  // the OTP paths (inbox-spam / code brute-force), generous on authenticated portal reads.
  static final int DEFAULT_PORTAL_OTP_REQUEST_LIMIT = 5;
  static final int DEFAULT_PORTAL_OTP_VERIFY_LIMIT = 10;
  static final int DEFAULT_PORTAL_REFRESH_LIMIT = 30;
  // Review writes (slice R1): strict — a review is a moderation-queue write, not a browse.
  static final int DEFAULT_PORTAL_REVIEW_LIMIT = 10;
  // Comment writes (slice R2): same shape — every ask lands in the merchant's answer queue.
  static final int DEFAULT_PORTAL_COMMENT_LIMIT = 10;
  // Payment-proof claim + presign (roadmap item 2): a shopper attaching evidence to their order —
  // a write on both the public (magic-link) and portal planes. Strict, its own bucket. ~10/min
  // allows presign + claim + a couple retries per submission.
  static final int DEFAULT_PAYMENT_CLAIM_LIMIT = 10;
  // Self-serve auth writes (story 87): register mints a live account + org per call, and
  // forgot-password emails an arbitrary address per call — both previously fell through the
  // final fail-open else, i.e. completely unthrottled.
  // Coupon preview (roadmap item 9): an anonymous endpoint that answers "does this code exist and
  // what does it give?" — i.e. an enumeration surface. Strict and its own bucket, so a scraper
  // hunting codes exhausts 10/min rather than riding the generous public-read budget.
  static final int DEFAULT_PUBLIC_COUPON_LIMIT = 10;
  static final int DEFAULT_AUTH_REGISTER_LIMIT = 3;
  static final int DEFAULT_AUTH_FORGOT_LIMIT = 5;

  private JedisPool jedisPool;
  private ObjectMapper objectMapper;
  private int publicReadLimit = DEFAULT_PUBLIC_READ_LIMIT;
  private int publicCheckoutLimit = DEFAULT_PUBLIC_CHECKOUT_LIMIT;
  private int portalOtpRequestLimit = DEFAULT_PORTAL_OTP_REQUEST_LIMIT;
  private int portalOtpVerifyLimit = DEFAULT_PORTAL_OTP_VERIFY_LIMIT;
  private int portalRefreshLimit = DEFAULT_PORTAL_REFRESH_LIMIT;
  private int portalReviewLimit = DEFAULT_PORTAL_REVIEW_LIMIT;
  private int portalCommentLimit = DEFAULT_PORTAL_COMMENT_LIMIT;
  private int paymentClaimLimit = DEFAULT_PAYMENT_CLAIM_LIMIT;
  private int publicCouponLimit = DEFAULT_PUBLIC_COUPON_LIMIT;
  private int authRegisterLimit = DEFAULT_AUTH_REGISTER_LIMIT;
  private int authForgotLimit = DEFAULT_AUTH_FORGOT_LIMIT;
  private boolean trustProxy;

  /** No-arg constructor for the servlet container; config is read in {@link #init}. */
  public RateLimitFilter() {}

  /** Test constructor: inject the collaborators + limits directly (bypasses {@link #init}). */
  RateLimitFilter(
      JedisPool jedisPool,
      ObjectMapper objectMapper,
      int publicReadLimit,
      int publicCheckoutLimit,
      boolean trustProxy) {
    this.jedisPool = jedisPool;
    this.objectMapper = objectMapper;
    this.publicReadLimit = publicReadLimit;
    this.publicCheckoutLimit = publicCheckoutLimit;
    this.trustProxy = trustProxy;
  }

  @Override
  public void init(FilterConfig filterConfig) {
    AppConfig config =
        (AppConfig) filterConfig.getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.jedisPool = config.jedisPool;
    this.objectMapper = config.objectMapper;
    // Env-tunable public limits + proxy trust; a bad/unset value falls back to the default and logs
    // once rather than failing startup (a typo in a limit must not take the storefront down).
    this.publicReadLimit = envIntOrDefault("PUBLIC_READ_LIMIT", DEFAULT_PUBLIC_READ_LIMIT);
    this.publicCheckoutLimit =
        envIntOrDefault("PUBLIC_CHECKOUT_LIMIT", DEFAULT_PUBLIC_CHECKOUT_LIMIT);
    this.portalOtpRequestLimit =
        envIntOrDefault("PORTAL_OTP_REQUEST_LIMIT", DEFAULT_PORTAL_OTP_REQUEST_LIMIT);
    this.portalOtpVerifyLimit =
        envIntOrDefault("PORTAL_OTP_VERIFY_LIMIT", DEFAULT_PORTAL_OTP_VERIFY_LIMIT);
    this.portalReviewLimit = envIntOrDefault("PORTAL_REVIEW_LIMIT", DEFAULT_PORTAL_REVIEW_LIMIT);
    this.portalCommentLimit = envIntOrDefault("PORTAL_COMMENT_LIMIT", DEFAULT_PORTAL_COMMENT_LIMIT);
    this.paymentClaimLimit = envIntOrDefault("PAYMENT_CLAIM_LIMIT", DEFAULT_PAYMENT_CLAIM_LIMIT);
    this.publicCouponLimit = envIntOrDefault("PUBLIC_COUPON_LIMIT", DEFAULT_PUBLIC_COUPON_LIMIT);
    this.authRegisterLimit = envIntOrDefault("AUTH_REGISTER_LIMIT", DEFAULT_AUTH_REGISTER_LIMIT);
    this.authForgotLimit = envIntOrDefault("AUTH_FORGOT_LIMIT", DEFAULT_AUTH_FORGOT_LIMIT);
    this.trustProxy = Boolean.parseBoolean(System.getenv("TRUST_PROXY"));
    log.info(
        "RateLimitFilter: pub-read={}/min, pub-checkout={}/min, trustProxy={}",
        publicReadLimit,
        publicCheckoutLimit,
        trustProxy);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse resp = (HttpServletResponse) response;

    String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");
    String ip = resolveClientIp(req);

    int limit;
    String keyPrefix;
    // Customer-portal buckets first — the OTP bootstrap lives under /api/public/ and portal-refresh
    // ends with /refresh, so both must be matched ahead of the generic public/staff rules below.
    if (path.endsWith("/portal/request-code")) {
      keyPrefix = "rl:portal-otp-req:";
      limit = portalOtpRequestLimit;
    } else if (path.endsWith("/portal/verify-code")) {
      keyPrefix = "rl:portal-otp-verify:";
      limit = portalOtpVerifyLimit;
    } else if (path.equals("/api/portal/auth/refresh")) {
      keyPrefix = "rl:portal-refresh:";
      limit = portalRefreshLimit;
    } else if (path.equals("/api/portal/checkout") && "POST".equals(req.getMethod())) {
      // The authenticated checkout mirrors the strict anonymous bucket (slice P6) — an order
      // placement is the same expensive write whether or not the caller is logged in.
      keyPrefix = "rl:portal-checkout:";
      limit = publicCheckoutLimit;
    } else if (path.startsWith("/api/portal/reviews") && !"GET".equals(req.getMethod())) {
      // Review mutations (slice R1) get their own strict bucket; the GET (my reviews) stays on
      // the generous portal-read bucket below.
      keyPrefix = "rl:portal-review:";
      limit = portalReviewLimit;
    } else if (path.startsWith("/api/portal/comments") && !"GET".equals(req.getMethod())) {
      // Comment mutations (slice R2) — same strict shape as reviews, its own bucket.
      keyPrefix = "rl:portal-comment:";
      limit = portalCommentLimit;
    } else if ("POST".equals(req.getMethod())
        && (path.endsWith("/payment-claim") || path.endsWith("/payment-proof/presign"))) {
      // Payment-proof claim + presign (roadmap item 2) — a strict write on both the public
      // (magic-link) and portal planes; matched ahead of the /api/portal/ and /api/public/
      // catch-alls so it isn't absorbed by the generous read buckets.
      keyPrefix = "rl:payment-claim:";
      limit = paymentClaimLimit;
    } else if ("POST".equals(req.getMethod()) && path.endsWith("/coupons/validate")) {
      // The coupon preview (roadmap item 9) — matched AHEAD of the /api/public/ catch-all so it
      // gets
      // its own strict budget instead of the generous read one. It lives under /api/public/, so
      // without this branch it would inherit pub-read's 120/min, which is a code-scraping
      // allowance.
      keyPrefix = "rl:pub-coupon:";
      limit = publicCouponLimit;
    } else if (path.startsWith("/api/portal/")) {
      keyPrefix = "rl:portal-read:";
      limit = publicReadLimit;
    } else if (path.equals("/api/auth/register")) {
      // Story 87: register/forgot-password previously fell through the fail-open else below.
      keyPrefix = "rl:auth-register:";
      limit = authRegisterLimit;
    } else if (path.equals("/api/auth/forgot-password")) {
      keyPrefix = "rl:auth-forgot:";
      limit = authForgotLimit;
    } else if (path.equals("/api/auth/resend-verification")) {
      // Story 88: same shape as forgot-password (an anonymous email-send trigger) — own bucket,
      // shared AUTH_FORGOT_LIMIT budget. /verify-email stays unbucketed like the other
      // token-redemption endpoints (256-bit single-use tokens).
      keyPrefix = "rl:auth-resend:";
      limit = authForgotLimit;
    } else if (path.endsWith("/login")) {
      keyPrefix = "rl:login:";
      limit = LOGIN_LIMIT;
    } else if (path.endsWith("/refresh")) {
      keyPrefix = "rl:refresh:";
      limit = REFRESH_LIMIT;
    } else if (path.startsWith("/api/public/")) {
      if ("POST".equals(req.getMethod()) && path.endsWith("/checkout")) {
        keyPrefix = "rl:pub-checkout:";
        limit = publicCheckoutLimit;
      } else {
        keyPrefix = "rl:pub-read:";
        limit = publicReadLimit;
      }
    } else {
      // Neither auth nor public — shouldn't occur given the two mappings. Fail open.
      chain.doFilter(request, response);
      return;
    }

    String key = keyPrefix + ip;
    try (Jedis jedis = jedisPool.getResource()) {
      long current = jedis.incr(key);
      if (current == 1) {
        jedis.expire(key, WINDOW_SECONDS);
      }
      if (current > limit) {
        resp.setStatus(429);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
            resp.getOutputStream(), ApiError.of(429, "Too many requests. Try again later."));
        return;
      }
    }

    chain.doFilter(request, response);
  }

  /**
   * The client IP the bucket is keyed on. Default: the connecting socket ({@code getRemoteAddr()}).
   * With {@code TRUST_PROXY=true}: the first hop of {@code X-Forwarded-For} (the originating client
   * the proxy recorded), falling back to {@code getRemoteAddr()} when the header is absent/blank.
   */
  String resolveClientIp(HttpServletRequest req) {
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

  private static int envIntOrDefault(String name, int defaultValue) {
    String v = System.getenv(name);
    if (v == null || v.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(v.trim());
    } catch (NumberFormatException e) {
      log.warn("Ignoring unparseable {}='{}' — using default {}", name, v, defaultValue);
      return defaultValue;
    }
  }

  @Override
  public void destroy() {}
}
