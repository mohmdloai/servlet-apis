package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ImpersonateRequest;
import com.loai.inventory.api.dto.ImpersonationResponse;
import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.api.servlet.AuthCookies;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.ImpersonationTier;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Org-tier impersonation — {@code POST /api/orgs/{orgId}/impersonate/{userId}}. Requires a REAL
 * OWNER of {@code orgId} (the system-admin bypass is deliberately not honored — an admin uses the
 * platform endpoint). The overlay is scoped to {@code orgId} only. Enforcement is in {@link
 * AuthService#impersonate}. Sets the access cookie only.
 */
public class ImpersonationHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(ImpersonationHandler.class);

  private final AuthService authService;
  private final ObjectMapper mapper;
  private final boolean secureCookies;

  public ImpersonationHandler(AuthService authService, ObjectMapper mapper, boolean secureCookies) {
    this.authService = authService;
    this.mapper = mapper;
    this.secureCookies = secureCookies;
  }

  @Override
  public void handle(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID orgId,
      String remainingPath)
      throws IOException {
    try {
      if (!"POST".equals(method)) {
        writeJson(resp, 405, ApiError.of(405, "Method not allowed"));
        return;
      }
      UUID targetId = parseTargetId(remainingPath);
      SecurityContext ctx = AuthzHelper.requireAuth(req);
      Environment env = (Environment) req.getAttribute(JwtAuthFilter.ENVIRONMENT_ATTR);

      ImpersonateRequest body = readBodyOrNull(req);
      String reason = body == null ? null : body.getReason();

      AuthService.ImpersonationResult result =
          authService.impersonate(ctx, targetId, ImpersonationTier.ORG, orgId, reason, env);

      AuthCookies.writeAccess(resp, result.accessToken(), (int) result.expiresIn(), secureCookies);
      writeJson(resp, 200, ImpersonationResponse.started(result));
    } catch (AppException e) {
      writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/impersonate{}", orgId, remainingPath, e);
      writeJson(resp, 500, ApiError.of(500, "Internal server error"));
    }
  }

  private UUID parseTargetId(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || remainingPath.equals("/")) {
      throw new ValidationException("Target user id is required in the path");
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    if (raw.contains("/")) {
      throw new ValidationException("Unknown impersonation endpoint");
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid user id format: " + raw);
    }
  }

  private ImpersonateRequest readBodyOrNull(HttpServletRequest req) throws IOException {
    if (req.getContentLength() <= 0) {
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
