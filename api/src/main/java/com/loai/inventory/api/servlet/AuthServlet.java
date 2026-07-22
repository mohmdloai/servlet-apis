package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.AuthResponse;
import com.loai.inventory.api.dto.ForgotPasswordRequest;
import com.loai.inventory.api.dto.ImpersonationResponse;
import com.loai.inventory.api.dto.LoginRequest;
import com.loai.inventory.api.dto.RegisterRequest;
import com.loai.inventory.api.dto.SessionResponse;
import com.loai.inventory.api.dto.TokenPasswordRequest;
import com.loai.inventory.api.dto.VerifyEmailRequest;
import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.auth.AccountService;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(AuthServlet.class);

  private AuthService authService;
  private AccountService accountService;
  private ObjectMapper mapper;
  private boolean secureCookies;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.authService = config.authService;
    this.accountService = config.accountService;
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
        case "/register" -> handleRegister(req, resp);
        case "/verify-email" -> handleVerifyEmail(req, resp);
        case "/resend-verification" -> handleResendVerification(req, resp);
        case "/forgot-password" -> handleForgotPassword(req, resp);
        case "/reset-password" -> handleResetPassword(req, resp);
        case "/activate" -> handleActivate(req, resp);
        case "/refresh" -> handleRefresh(req, resp);
        case "/logout" -> handleLogout(req, resp);
        case "/logout-all" -> handleLogoutAll(req, resp);
        case "/stop-impersonating" -> handleStopImpersonating(req, resp);
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
    LoginRequest body = readBody(req, LoginRequest.class);
    if (body.getEmail() == null || body.getPassword() == null) {
      throw new ValidationException("email and password are required");
    }

    String deviceInfo = req.getHeader("User-Agent");
    String sourceIp = req.getRemoteAddr();

    AuthService.LoginResult result =
        authService.login(body.getEmail(), body.getPassword(), deviceInfo, sourceIp);

    AuthCookies.writeAccess(resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
    AuthCookies.writeRefresh(
        resp, result.refreshToken(), AuthCookies.REFRESH_MAX_AGE, secureCookies);

    writeJson(
        resp,
        200,
        new AuthResponse(
            result.expiresIn(), result.user().getId(), result.user().getActorType().name()));
  }

  private void handleRegister(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    RegisterRequest body = readBody(req, RegisterRequest.class);
    if (body.getEmail() == null || body.getPassword() == null) {
      throw new ValidationException("email and password are required");
    }
    // Verify-to-activate (story 88): the account is created unverified and NO session is issued —
    // the emailed link (POST /verify-email) is what activates login and signs the user in.
    var created = accountService.register(body.getEmail(), body.getPassword(), body.getOrgName());
    writeJson(resp, 201, Map.of("email", created.getEmail(), "verification", "sent"));
  }

  /** Redeem the emailed EMAIL_VERIFY link: stamps the account verified and signs the user in. */
  private void handleVerifyEmail(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    VerifyEmailRequest body = readBody(req, VerifyEmailRequest.class);
    if (body.getToken() == null || body.getToken().isBlank()) {
      throw new ValidationException("token is required");
    }
    AuthService.LoginResult result =
        accountService.verifyEmail(
            body.getToken(),
            OffsetDateTime.now(),
            req.getHeader("User-Agent"),
            req.getRemoteAddr());
    writeSession(resp, result, 200);
  }

  /** Uniform 200 always — mirrors forgot-password (no account enumeration). */
  private void handleResendVerification(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    ForgotPasswordRequest body = readBody(req, ForgotPasswordRequest.class);
    accountService.resendVerification(body.getEmail(), OffsetDateTime.now());
    writeJson(
        resp,
        200,
        Map.of(
            "message",
            "If an unverified account exists for that email, a new link has been sent."));
  }

  private void handleForgotPassword(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    ForgotPasswordRequest body = readBody(req, ForgotPasswordRequest.class);
    // Always 200 with an opaque message — never reveal whether the email is registered.
    accountService.requestPasswordReset(body.getEmail(), OffsetDateTime.now());
    writeJson(
        resp,
        200,
        Map.of("message", "If an account exists for that email, a reset link has been sent."));
  }

  private void handleResetPassword(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    TokenPasswordRequest body = readBody(req, TokenPasswordRequest.class);
    if (body.getToken() == null || body.getNewPassword() == null) {
      throw new ValidationException("token and new_password are required");
    }
    AuthService.LoginResult result =
        accountService.resetPassword(
            body.getToken(),
            body.getNewPassword(),
            OffsetDateTime.now(),
            req.getHeader("User-Agent"),
            req.getRemoteAddr());
    writeSession(resp, result, 200);
  }

  private void handleActivate(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    TokenPasswordRequest body = readBody(req, TokenPasswordRequest.class);
    if (body.getToken() == null || body.getNewPassword() == null) {
      throw new ValidationException("token and new_password are required");
    }
    AuthService.LoginResult result =
        accountService.activate(
            body.getToken(),
            body.getNewPassword(),
            OffsetDateTime.now(),
            req.getHeader("User-Agent"),
            req.getRemoteAddr());
    writeSession(resp, result, 200);
  }

  /** Write auth cookies + the {@link AuthResponse} body for a freshly-issued session. */
  private void writeSession(HttpServletResponse resp, AuthService.LoginResult result, int status)
      throws IOException {
    AuthCookies.writeAccess(resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
    AuthCookies.writeRefresh(
        resp, result.refreshToken(), AuthCookies.REFRESH_MAX_AGE, secureCookies);
    writeJson(
        resp,
        status,
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

    AuthCookies.writeAccess(resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
    AuthCookies.writeRefresh(
        resp, result.refreshToken(), AuthCookies.REFRESH_MAX_AGE, secureCookies);

    writeJson(
        resp,
        200,
        new AuthResponse(
            result.expiresIn(), result.user().getId(), result.user().getActorType().name()));
  }

  private void handleStopImpersonating(HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    SecurityContext ctx = requireAuth(req);
    Environment env = (Environment) req.getAttribute(JwtAuthFilter.ENVIRONMENT_ATTR);
    AuthService.ImpersonationResult result = authService.stopImpersonating(ctx, env);
    // Access cookie only — the driver's refresh cookie was never touched.
    AuthCookies.writeAccess(resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
    writeJson(resp, 200, ImpersonationResponse.stopped());
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

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Empty/truncated/malformed body — a 400, not an unhandled 500.
      throw new ValidationException("request body is required and must be valid JSON");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }
}
