package com.loai.inventory.api.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.servlet.CustomerAuthCookies;
import com.loai.inventory.api.servlet.PortalCsrf;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.model.CustomerPrincipal;
import com.loai.inventory.service.auth.CustomerAuthService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * The customer-portal auth gate on {@code /api/portal/*} — the mirror of {@link JwtAuthFilter} on
 * the isolated customer plane. It shares nothing with the staff filter:
 *
 * <ol>
 *   <li><b>CSRF</b> (all portal paths): require {@code X-Portal-Request: 1}; on a mutation, require
 *       an allowlisted {@code Origin}/{@code Referer} ({@link PortalCsrf}).
 *   <li><b>Bypass auth</b> for {@code /api/portal/auth/refresh} and {@code /api/portal/auth/logout}
 *       — they authenticate off the {@code customer_refresh} cookie alone, so no access token is
 *       required (but the CSRF checks above still apply).
 *   <li><b>Authenticate</b> everything else: parse the {@code customer_access} cookie with the
 *       <em>customer</em> {@link JwtUtil} (a different signing key than staff), require {@code
 *       aud=customer} <em>and</em> {@code actor_type=CUSTOMER}, validate the {@code token_version}
 *       fail-closed, honour the per-device kill-switch, then publish a {@link CustomerPrincipal}.
 * </ol>
 *
 * A staff token cannot pass here (wrong key → signature failure, and no {@code aud=customer}); a
 * customer token cannot pass {@link JwtAuthFilter} (that filter rejects {@code aud=customer} and
 * bypasses {@code /api/portal/}). See {@code stories/portal_auth_core.md} + the epic threat model.
 */
public class CustomerAuthFilter implements Filter {

  public static final String PRINCIPAL_ATTR = "customerPrincipal";
  private static final String CUSTOMER_AUDIENCE = "customer";

  private JwtUtil customerJwtUtil;
  private CustomerAuthService customerAuthService;
  private ObjectMapper objectMapper;
  private Set<String> allowedOrigins;

  /** No-arg constructor for the servlet container; config is read in {@link #init}. */
  public CustomerAuthFilter() {}

  /**
   * Test constructor: inject the collaborators directly (bypasses {@link #init}), as CorsFilter and
   * {@link JwtAuthFilter} do — {@code init} reads a live {@code AppConfig}, which boots Postgres
   * and Redis, so the branch logic here is otherwise only reachable through a full IT.
   */
  CustomerAuthFilter(
      JwtUtil customerJwtUtil,
      CustomerAuthService customerAuthService,
      ObjectMapper objectMapper,
      Set<String> allowedOrigins) {
    this.customerJwtUtil = customerJwtUtil;
    this.customerAuthService = customerAuthService;
    this.objectMapper = objectMapper;
    this.allowedOrigins = allowedOrigins;
  }

  @Override
  public void init(FilterConfig filterConfig) {
    AppConfig config =
        (AppConfig) filterConfig.getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.customerJwtUtil = config.customerJwtUtil;
    this.customerAuthService = config.customerAuthService;
    this.objectMapper = config.objectMapper;
    this.allowedOrigins = config.corsAllowedOrigins;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse resp = (HttpServletResponse) response;

    String path = req.getServletPath() + (req.getPathInfo() != null ? req.getPathInfo() : "");

    // 1. CSRF — a custom header on every call, an Origin allowlist on mutations.
    if (!PortalCsrf.hasHeader(req)) {
      write(resp, 400, "Missing " + PortalCsrf.HEADER + " header");
      return;
    }
    if (PortalCsrf.isMutation(req.getMethod()) && !PortalCsrf.originAllowed(req, allowedOrigins)) {
      write(resp, 403, "Origin not allowed");
      return;
    }

    // 2. Bypass auth for the refresh/logout endpoints — they use the refresh cookie only.
    if (path.equals("/api/portal/auth/refresh") || path.equals("/api/portal/auth/logout")) {
      chain.doFilter(request, response);
      return;
    }

    // 3. Authenticate off the customer_access cookie.
    String token = extractAccessToken(req);
    if (token == null) {
      write(resp, 401, "Missing customer session");
      return;
    }
    try {
      Claims claims = customerJwtUtil.parseAndVerify(token);
      Set<String> aud = claims.getAudience();
      if (aud == null || !aud.contains(CUSTOMER_AUDIENCE)) {
        write(resp, 401, "Wrong token audience");
        return;
      }
      if (!"CUSTOMER".equals(claims.get("actor_type", String.class))) {
        write(resp, 401, "Wrong token type");
        return;
      }
      UUID customerId = UUID.fromString(claims.getSubject());
      UUID orgId = UUID.fromString(claims.get("org_id", String.class));
      int tokenVersion = claims.get("token_version", Integer.class);

      if (!customerAuthService.isTokenVersionValid(orgId, customerId, tokenVersion)) {
        write(resp, 401, "Session revoked");
        return;
      }
      UUID familyId = UUID.fromString(claims.get("fam", String.class));
      if (customerAuthService.isDeviceRevoked(familyId)) {
        write(resp, 401, "Session revoked");
        return;
      }

      req.setAttribute(
          PRINCIPAL_ATTR, new CustomerPrincipal(customerId, orgId, tokenVersion, familyId));
      chain.doFilter(request, response);
    } catch (JwtException | IllegalArgumentException e) {
      write(resp, 401, "Invalid customer session");
    }
  }

  @Override
  public void destroy() {}

  private String extractAccessToken(HttpServletRequest req) {
    Cookie[] cookies = req.getCookies();
    if (cookies != null) {
      for (Cookie c : cookies) {
        if (CustomerAuthCookies.ACCESS_COOKIE.equals(c.getName())) {
          return c.getValue();
        }
      }
    }
    return null;
  }

  private void write(HttpServletResponse resp, int status, String message) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(resp.getOutputStream(), ApiError.of(status, message));
  }
}
