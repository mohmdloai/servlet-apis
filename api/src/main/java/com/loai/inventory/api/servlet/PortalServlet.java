package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PortalMeResponse;
import com.loai.inventory.api.dto.PortalProfileUpdateRequest;
import com.loai.inventory.api.dto.PortalSessionResponse;
import com.loai.inventory.api.filter.CustomerAuthFilter;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CustomerPrincipal;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.auth.CustomerAuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The authenticated customer-portal API at {@code /api/portal/*} (behind {@link CustomerAuthFilter}
 * — CSRF + customer session). Endpoints:
 *
 * <ul>
 *   <li>{@code POST /auth/refresh} — rotate the session off the {@code customer_refresh} cookie
 *   <li>{@code POST /auth/logout} — kill this device; {@code POST /auth/logout-all} — every device
 *   <li>{@code GET /auth/sessions} — this customer's active devices
 *   <li>{@code GET|PATCH /me} — read / merge-update the caller's own profile
 * </ul>
 *
 * All identity comes from the {@link CustomerPrincipal} the filter published — never from the URL
 * or body (epic decision #4). See {@code stories/portal_auth_core.md}.
 */
public class PortalServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(PortalServlet.class);

  private CustomerAuthService authService;
  private CustomerPortalService portalService;
  private ObjectMapper mapper;
  private boolean secureCookies;
  private int refreshMaxAge;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.authService = config.customerAuthService;
    this.portalService = config.customerPortalService;
    this.mapper = config.objectMapper;
    this.secureCookies = config.secureCookies;
    this.refreshMaxAge = config.customerRefreshMaxAgeSeconds;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
    String method = req.getMethod();
    try {
      switch (path) {
        case "/auth/refresh" -> requirePost(method, () -> handleRefresh(req, resp));
        case "/auth/logout" -> requirePost(method, () -> handleLogout(req, resp));
        case "/auth/logout-all" -> requirePost(method, () -> handleLogoutAll(req, resp));
        case "/auth/sessions" -> requireGet(method, () -> handleSessions(req, resp));
        case "/me" -> {
          if ("GET".equals(method)) {
            handleGetMe(req, resp);
          } else if ("PATCH".equals(method)) {
            handlePatchMe(req, resp);
          } else {
            writeError(resp, 405, "Method not allowed");
          }
        }
        default -> writeError(resp, 404, "Unknown portal endpoint");
      }
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      log.error("Unhandled exception in PortalServlet", e);
      writeError(resp, 500, "Internal server error");
    }
  }

  // ── auth ────────────────────────────────────────────────────────────────────

  private void handleRefresh(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String rawRefresh = extractRefreshCookie(req);
    if (rawRefresh == null) {
      throw new AuthenticationException("Missing refresh token");
    }
    CustomerAuthService.SessionResult result = authService.refresh(rawRefresh, req.getRemoteAddr());
    writeCookies(resp, result);
    writeJson(resp, 200, Map.of("expires_in", result.expiresIn()));
  }

  private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    authService.logout(extractRefreshCookie(req));
    CustomerAuthCookies.clearAll(resp, secureCookies);
    resp.setStatus(204);
  }

  private void handleLogoutAll(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    authService.logoutAll(principal.orgId(), principal.customerId());
    CustomerAuthCookies.clearAll(resp, secureCookies);
    resp.setStatus(204);
  }

  private void handleSessions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    List<PortalSessionResponse> items =
        authService.listSessions(principal.orgId(), principal.customerId()).stream()
            .map(PortalSessionResponse::from)
            .toList();
    writeJson(resp, 200, items);
  }

  // ── profile ──────────────────────────────────────────────────────────────────

  private void handleGetMe(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    writeJson(
        resp,
        200,
        PortalMeResponse.from(portalService.me(principal.orgId(), principal.customerId())));
  }

  private void handlePatchMe(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    CustomerPrincipal principal = requirePrincipal(req);
    PortalProfileUpdateRequest body = readBody(req, PortalProfileUpdateRequest.class);
    CustomerPortalService.ProfileUpdate update =
        new CustomerPortalService.ProfileUpdate(body.getName(), body.getPhone(), body.getAddress());
    writeJson(
        resp,
        200,
        PortalMeResponse.from(
            portalService.updateProfile(principal.orgId(), principal.customerId(), update)));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private void writeCookies(HttpServletResponse resp, CustomerAuthService.SessionResult result) {
    CustomerAuthCookies.writeAccess(
        resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
    CustomerAuthCookies.writeRefresh(resp, result.refreshToken(), refreshMaxAge, secureCookies);
  }

  private CustomerPrincipal requirePrincipal(HttpServletRequest req) {
    CustomerPrincipal principal =
        (CustomerPrincipal) req.getAttribute(CustomerAuthFilter.PRINCIPAL_ATTR);
    if (principal == null) {
      throw new AuthenticationException("Authentication required");
    }
    return principal;
  }

  private String extractRefreshCookie(HttpServletRequest req) {
    Cookie[] cookies = req.getCookies();
    if (cookies != null) {
      for (Cookie c : cookies) {
        if (CustomerAuthCookies.REFRESH_COOKIE.equals(c.getName())) {
          return c.getValue();
        }
      }
    }
    return null;
  }

  private interface Handler {
    void run() throws IOException;
  }

  private void requirePost(String method, Handler h) throws IOException {
    if (!"POST".equals(method)) {
      throw new ValidationException("Method not allowed");
    }
    h.run();
  }

  private void requireGet(String method, Handler h) throws IOException {
    if (!"GET".equals(method)) {
      throw new ValidationException("Method not allowed");
    }
    h.run();
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("request body is required and must be valid JSON");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
