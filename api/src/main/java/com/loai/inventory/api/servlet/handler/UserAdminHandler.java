package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.AdminUserDetailResponse;
import com.loai.inventory.api.dto.AdminUserResponse;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CreateUserRequest;
import com.loai.inventory.api.dto.OrgRoleRequest;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.ResetPasswordRequest;
import com.loai.inventory.api.dto.SessionResponse;
import com.loai.inventory.api.dto.SetUserActiveRequest;
import com.loai.inventory.api.dto.SystemRoleRequest;
import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PlatformAuditEvent;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.UserAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform user, role &amp; session administration at {@code /api/admin/users[/…]} (see {@code
 * docs/platform-admin-plan.md}, slices 3 &amp; 4). Reads (list, detail) require {@code
 * requirePlatformRead}; every mutation and every session operation requires {@code requireAdmin}.
 *
 * <ul>
 *   <li>{@code GET /users?page&size&q=} · {@code POST /users}
 *   <li>{@code GET /users/{id}} · {@code PATCH /users/{id}}
 *   <li>{@code POST /users/{id}/system-roles} · {@code DELETE .../system-roles/{role}}
 *   <li>{@code POST /users/{id}/org-roles} · {@code DELETE .../org-roles/{orgId}/{role}}
 *   <li>{@code POST /users/{id}/reset-password}
 *   <li>{@code GET /users/{id}/sessions} · {@code DELETE .../sessions/{familyId}} · {@code POST
 *       .../logout-all}
 * </ul>
 */
public class UserAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(UserAdminHandler.class);

  private final UserAdminService userAdminService;
  private final AuthService authService;
  private final PlatformAuditService audit;
  private final ObjectMapper mapper;

  public UserAdminHandler(
      UserAdminService userAdminService,
      AuthService authService,
      PlatformAuditService audit,
      ObjectMapper mapper) {
    this.userAdminService = userAdminService;
    this.authService = authService;
    this.audit = audit;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      Path path = parsePath(remaining);
      if (path.userId() == null) {
        collection(method, req, resp);
      } else if (path.sub() == null) {
        singleUser(method, req, resp, path.userId());
      } else {
        subResource(method, req, resp, path);
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/users{}", remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  // ───────────────────────── /users ─────────────────────────

  private void collection(String method, HttpServletRequest req, HttpServletResponse resp)
      throws IOException {
    switch (method) {
      case "GET" -> {
        AuthzHelper.requirePlatformRead(req);
        int page = intParam(req, "page", 0);
        int size = intParam(req, "size", UserAdminService.DEFAULT_PAGE_SIZE);
        String q = req.getParameter("q");
        UserAdminService.UserPage result = userAdminService.list(page, size, q);
        List<AdminUserResponse> data =
            result.users().stream().map(AdminUserResponse::from).toList();
        writeJson(
            resp, 200, new PageResponse<>(data, result.total(), result.page(), result.size()));
      }
      case "POST" -> {
        SecurityContext ctx = AuthzHelper.requireAdmin(req);
        CreateUserRequest body = readBody(req, CreateUserRequest.class);
        ActorType actorType = parseActorType(body.getActorType());
        var created =
            userAdminService.createUser(
                ctx, env(req), body.getEmail(), body.getPassword(), actorType);
        writeJson(resp, 201, AdminUserResponse.from(created));
      }
      default -> writeError(resp, 405, "Method not allowed");
    }
  }

  // ───────────────────────── /users/{id} ─────────────────────────

  private void singleUser(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID userId)
      throws IOException {
    switch (method) {
      case "GET" -> {
        AuthzHelper.requirePlatformRead(req);
        writeJson(resp, 200, AdminUserDetailResponse.from(userAdminService.get(userId)));
      }
      case "PATCH" -> {
        SecurityContext ctx = AuthzHelper.requireAdmin(req);
        SetUserActiveRequest body = readBody(req, SetUserActiveRequest.class);
        if (body.getActive() == null) {
          throw new ValidationException("active is required");
        }
        var updated = userAdminService.setActive(ctx, env(req), userId, body.getActive());
        writeJson(resp, 200, AdminUserResponse.from(updated));
      }
      default -> writeError(resp, 405, "Method not allowed");
    }
  }

  // ───────────────────────── /users/{id}/{sub}/… ─────────────────────────

  private void subResource(
      String method, HttpServletRequest req, HttpServletResponse resp, Path path)
      throws IOException {
    UUID userId = path.userId();
    switch (path.sub()) {
      case "system-roles" -> systemRoles(method, req, resp, userId, path.rest());
      case "org-roles" -> orgRoles(method, req, resp, userId, path.rest());
      case "reset-password" -> resetPassword(method, req, resp, userId);
      case "sessions" -> sessions(method, req, resp, userId, path.rest());
      case "logout-all" -> logoutAll(method, req, resp, userId);
      default -> writeError(resp, 404, "Unknown user endpoint");
    }
  }

  private void systemRoles(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID userId,
      List<String> rest)
      throws IOException {
    switch (method) {
      case "POST" -> {
        SecurityContext ctx = AuthzHelper.requireAdmin(req);
        SystemRoleRequest body = readBody(req, SystemRoleRequest.class);
        userAdminService.grantSystemRole(ctx, env(req), userId, parseSystemRole(body.getRole()));
        resp.setStatus(204);
      }
      case "DELETE" -> {
        SecurityContext ctx = AuthzHelper.requireAdmin(req);
        if (rest.isEmpty()) {
          throw new ValidationException("role is required in the path");
        }
        userAdminService.revokeSystemRole(ctx, env(req), userId, parseSystemRole(rest.get(0)));
        resp.setStatus(204);
      }
      default -> writeError(resp, 405, "Method not allowed");
    }
  }

  private void orgRoles(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID userId,
      List<String> rest)
      throws IOException {
    switch (method) {
      case "POST" -> {
        SecurityContext ctx = AuthzHelper.requireAdmin(req);
        OrgRoleRequest body = readBody(req, OrgRoleRequest.class);
        userAdminService.grantOrgRole(
            ctx,
            env(req),
            userId,
            parseUuid(body.getOrgId(), "org_id"),
            parseOrgRole(body.getRole()));
        resp.setStatus(204);
      }
      case "DELETE" -> {
        SecurityContext ctx = AuthzHelper.requireAdmin(req);
        if (rest.size() < 2) {
          throw new ValidationException("org id and role are required in the path");
        }
        userAdminService.revokeOrgRole(
            ctx, env(req), userId, parseUuid(rest.get(0), "org_id"), parseOrgRole(rest.get(1)));
        resp.setStatus(204);
      }
      default -> writeError(resp, 405, "Method not allowed");
    }
  }

  private void resetPassword(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID userId)
      throws IOException {
    if (!"POST".equals(method)) {
      writeError(resp, 405, "Method not allowed");
      return;
    }
    SecurityContext ctx = AuthzHelper.requireAdmin(req);
    ResetPasswordRequest body = readBody(req, ResetPasswordRequest.class);
    userAdminService.resetPassword(ctx, env(req), userId, body.getPassword());
    resp.setStatus(204);
  }

  // ───────────────────────── session ops (slice 4) ─────────────────────────

  private void sessions(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID userId,
      List<String> rest)
      throws IOException {
    switch (method) {
      case "GET" -> {
        AuthzHelper.requireAdmin(req);
        List<SessionResponse> data =
            authService.listSessions(userId).stream().map(SessionResponse::from).toList();
        writeJson(resp, 200, data);
      }
      case "DELETE" -> {
        SecurityContext ctx = AuthzHelper.requireAdmin(req);
        if (rest.isEmpty()) {
          throw new ValidationException("family id is required in the path");
        }
        UUID familyId = parseUuid(rest.get(0), "family id");
        // Intent-first: write the audit row before the irreversible Redis revoke, so a security
        // op can never leave the session killed but untraceable if the audit write fails.
        audit.record(
            ctx,
            env(req),
            "SESSION_REVOKE",
            PlatformAuditEvent.Target.SESSION,
            familyId,
            Map.of("user_id", userId.toString()));
        authService.revokeSession(userId, familyId); // 404 if not the target's session
        resp.setStatus(204);
      }
      default -> writeError(resp, 405, "Method not allowed");
    }
  }

  private void logoutAll(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID userId)
      throws IOException {
    if (!"POST".equals(method)) {
      writeError(resp, 405, "Method not allowed");
      return;
    }
    SecurityContext ctx = AuthzHelper.requireAdmin(req);
    // Intent-first: audit the forced logout before it takes effect (see revoke above).
    audit.record(
        ctx, env(req), "FORCE_LOGOUT_ALL", PlatformAuditEvent.Target.USER, userId, Map.of());
    authService.logoutAll(userId);
    resp.setStatus(204);
  }

  // ───────────────────────── parsing ─────────────────────────

  /**
   * {@code userId} null at the collection; {@code sub} the segment after the id; {@code rest} the
   * tail.
   */
  private record Path(UUID userId, String sub, List<String> rest) {}

  private Path parsePath(String remaining) {
    if (remaining == null || remaining.isEmpty() || remaining.equals("/")) {
      return new Path(null, null, List.of());
    }
    String raw = remaining.startsWith("/") ? remaining.substring(1) : remaining;
    String[] parts = raw.split("/");
    UUID userId = parseUuid(parts[0], "user id");
    String sub = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
    List<String> rest = parts.length > 2 ? List.of(parts).subList(2, parts.length) : List.of();
    return new Path(userId, sub, rest);
  }

  private ActorType parseActorType(String raw) {
    if (raw == null || raw.isBlank()) {
      return ActorType.USER;
    }
    try {
      return ActorType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid actor_type: " + raw);
    }
  }

  private SystemRole parseSystemRole(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("role is required");
    }
    try {
      return SystemRole.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid system role: " + raw);
    }
  }

  private OrgRole parseOrgRole(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("role is required");
    }
    try {
      return OrgRole.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid org role: " + raw);
    }
  }

  private UUID parseUuid(String raw, String what) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid " + what + " format: " + raw);
    }
  }

  private Environment env(HttpServletRequest req) {
    return (Environment) req.getAttribute(JwtAuthFilter.ENVIRONMENT_ATTR);
  }

  private int intParam(HttpServletRequest req, String name, int defaultValue) {
    String value = req.getParameter(name);
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    return mapper.readValue(req.getInputStream(), type);
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
