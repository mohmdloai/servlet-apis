package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.AuthResponse;
import com.loai.inventory.api.dto.LoginRequest;
import com.loai.inventory.api.dto.SessionResponse;
import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(AuthServlet.class);

  private AuthService authService;
  private ObjectMapper mapper;
  private boolean secureCookies;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.authService = config.authService;
    this.mapper = config.objectMapper;
    this.secureCookies = config.secureCookies;
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String path = req.getPathInfo();
      if (path == null) path = "/";

      switch (path) {
        case "/login" -> handleLogin(req, resp);
        case "/refresh" -> handleRefresh(req, resp);
        case "/logout" -> handleLogout(req, resp);
        case "/logout-all" -> handleLogoutAll(req, resp);
        default -> {
          resp.setStatus(404);
          writeJson(resp, 404, ApiError.of(404, "Unknown auth endpoint"));
        }
      }
    } catch (AppException e) {
      writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
    } catch (Exception e) {
      log.error("Unhandled exception in AuthServlet", e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String path = req.getPathInfo();
      if ("/sessions".equals(path)) {
        handleListSessions(req, resp);
      } else {
        resp.setStatus(404);
        writeJson(resp, 404, ApiError.of(404, "Unknown auth endpoint"));
      }
    } catch (AppException e) {
      writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
    } catch (Exception e) {
      log.error("Unhandled exception in AuthServlet", e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
  }

  @Override
  protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String path = req.getPathInfo();
      if (path != null && path.startsWith("/sessions/")) {
        String familyIdStr = path.substring("/sessions/".length());
        UUID familyId;
        try {
          familyId = UUID.fromString(familyIdStr);
        } catch (IllegalArgumentException e) {
          throw new ValidationException("Invalid session id: " + familyIdStr);
        }
        SecurityContext secCtx = requireAuth(req);
        authService.revokeSession(secCtx.actorId(), familyId);
        resp.setStatus(204);
      } else {
        resp.setStatus(404);
        writeJson(resp, 404, ApiError.of(404, "Unknown auth endpoint"));
      }
    } catch (AppException e) {
      writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
    } catch (Exception e) {
      log.error("Unhandled exception in AuthServlet", e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
  }

  private void handleLogin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    LoginRequest body = mapper.readValue(req.getInputStream(), LoginRequest.class);
    if (body.getEmail() == null || body.getPassword() == null) {
      throw new ValidationException("email and password are required");
    }

    String deviceInfo = req.getHeader("User-Agent");
    String sourceIp = req.getRemoteAddr();

    AuthService.LoginResult result =
        authService.login(body.getEmail(), body.getPassword(), deviceInfo, sourceIp);

    // Set access token cookie
    Cookie accessCookie = new Cookie("access_token", result.accessToken());
    accessCookie.setHttpOnly(true);
    accessCookie.setSecure(secureCookies);
    accessCookie.setPath("/");
    accessCookie.setMaxAge(900); // 15 min
    accessCookie.setAttribute("SameSite", "Strict");
    resp.addCookie(accessCookie);

    // Set refresh token cookie (scoped to /api/auth)
    Cookie refreshCookie = new Cookie("refresh_token", result.refreshToken());
    refreshCookie.setHttpOnly(true);
    refreshCookie.setSecure(secureCookies);
    refreshCookie.setPath("/api/auth");
    refreshCookie.setMaxAge(604800); // 7 days
    refreshCookie.setAttribute("SameSite", "Strict");
    resp.addCookie(refreshCookie);

    writeJson(
        resp,
        200,
        new AuthResponse(
            result.expiresIn(), result.user().getId(), result.user().getActorType().name()));
  }

  private void handleRefresh(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String rawRefreshToken = extractRefreshToken(req);
    if (rawRefreshToken == null) {
      throw new ValidationException("Refresh token is required");
    }

    String sourceIp = req.getRemoteAddr();
    AuthService.LoginResult result = authService.refresh(rawRefreshToken, sourceIp);

    // Set new access token cookie
    Cookie accessCookie = new Cookie("access_token", result.accessToken());
    accessCookie.setHttpOnly(true);
    accessCookie.setSecure(secureCookies);
    accessCookie.setPath("/");
    accessCookie.setMaxAge(900);
    accessCookie.setAttribute("SameSite", "Strict");
    resp.addCookie(accessCookie);

    // Set new refresh token cookie
    Cookie refreshCookie = new Cookie("refresh_token", result.refreshToken());
    refreshCookie.setHttpOnly(true);
    refreshCookie.setSecure(secureCookies);
    refreshCookie.setPath("/api/auth");
    refreshCookie.setMaxAge(604800);
    refreshCookie.setAttribute("SameSite", "Strict");
    resp.addCookie(refreshCookie);

    writeJson(
        resp,
        200,
        new AuthResponse(
            result.expiresIn(), result.user().getId(), result.user().getActorType().name()));
  }

  private void handleLogout(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    requireAuth(req);
    String rawRefreshToken = extractRefreshToken(req);
    if (rawRefreshToken != null) {
      authService.logout(rawRefreshToken);
    }

    // Clear cookies
    clearCookie(resp, "access_token", "/");
    clearCookie(resp, "refresh_token", "/api/auth");
    resp.setStatus(204);
  }

  private void handleLogoutAll(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    SecurityContext secCtx = requireAuth(req);
    authService.logoutAll(secCtx.actorId());

    clearCookie(resp, "access_token", "/");
    clearCookie(resp, "refresh_token", "/api/auth");
    resp.setStatus(204);
  }

  private void handleListSessions(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    SecurityContext secCtx = requireAuth(req);
    List<RefreshTokenStore.SessionInfo> sessions = authService.listSessions(secCtx.actorId());
    List<SessionResponse> items = sessions.stream().map(SessionResponse::from).toList();
    writeJson(resp, 200, items);
  }

  private SecurityContext requireAuth(HttpServletRequest req) {
    SecurityContext secCtx =
        (SecurityContext) req.getAttribute(JwtAuthFilter.SECURITY_CONTEXT_ATTR);
    if (secCtx == null) {
      throw new com.loai.inventory.common.exception.AuthenticationException(
          "Authentication required");
    }
    return secCtx;
  }

  private String extractRefreshToken(HttpServletRequest req) {
    Cookie[] cookies = req.getCookies();
    if (cookies != null) {
      for (Cookie cookie : cookies) {
        if ("refresh_token".equals(cookie.getName())) {
          return cookie.getValue();
        }
      }
    }
    return null;
  }

  private void clearCookie(HttpServletResponse resp, String name, String path) {
    Cookie cookie = new Cookie(name, "");
    cookie.setHttpOnly(true);
    cookie.setSecure(secureCookies);
    cookie.setPath(path);
    cookie.setMaxAge(0);
    cookie.setAttribute("SameSite", "Strict");
    resp.addCookie(cookie);
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }
}
