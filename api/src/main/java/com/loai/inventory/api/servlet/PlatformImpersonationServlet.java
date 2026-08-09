package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.ImpersonateRequest;
import com.loai.inventory.api.dto.ImpersonationResponse;
import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.ImpersonationTier;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.auth.AuthService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform-tier impersonation — {@code POST /api/admin/impersonate/{userId}}. The first real
 * cross-org platform route beyond the expiry sweep. ADMIN gets full write, SUPPORT gets a read-only
 * view-as; both enforced in {@link AuthService#impersonate}. Sets the access cookie only.
 */
public class PlatformImpersonationServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(PlatformImpersonationServlet.class);

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
      UUID targetId = parseTargetId(req.getPathInfo());
      SecurityContext ctx = AuthzHelper.requireAuth(req);
      Environment env = (Environment) req.getAttribute(JwtAuthFilter.ENVIRONMENT_ATTR);

      ImpersonateRequest body = readBodyOrNull(req);
      String reason = body == null ? null : body.getReason();

      AuthService.ImpersonationResult result =
          authService.impersonate(ctx, targetId, ImpersonationTier.PLATFORM, null, reason, env);

      AuthCookies.writeAccess(resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
      writeJson(resp, 200, ImpersonationResponse.started(result));
    } catch (AppException e) {
      ApiErrors.applyHeaders(resp, e);
      writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
    } catch (Exception e) {
      log.error("Unhandled exception in PlatformImpersonationServlet", e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
  }

  private UUID parseTargetId(String pathInfo) {
    if (pathInfo == null || pathInfo.length() <= 1) {
      throw new ValidationException(
          "Target user id is required: POST /api/admin/impersonate/{userId}");
    }
    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    // Only the /{userId} form is valid — reject deeper paths.
    if (raw.contains("/")) {
      throw new ValidationException("Unknown admin impersonation endpoint");
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid user id format: " + raw);
    }
  }

  private ImpersonateRequest readBodyOrNull(HttpServletRequest req) throws IOException {
    if (req.getInputStream() == null || req.getContentLength() <= 0) {
      return null;
    }
    try {
      return mapper.readValue(req.getInputStream(), ImpersonateRequest.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("malformed JSON body");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }
}
