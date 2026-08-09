package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.NotificationResponse;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.InAppFeedItem;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /api/orgs/{orgId}/notifications} — a user's own in-app feed.
 *
 * <ul>
 *   <li>{@code GET } — list the caller's feed ({@code ?page&size&unread=true}); a platform ADMIN
 *       may read another user's feed via {@code ?user_id=}.
 *   <li>{@code POST /{id}/read} · {@code POST /{id}/dismiss} — mark the caller's own entry.
 * </ul>
 *
 * Membership is required ({@code VIEWER}); mutations only ever touch the caller's own rows.
 */
public class NotificationHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(NotificationHandler.class);

  private final NotificationService notificationService;
  private final ObjectMapper mapper;

  public NotificationHandler(NotificationService notificationService, ObjectMapper mapper) {
    this.notificationService = notificationService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID orgId, String remaining)
      throws IOException {
    try {
      String[] parts = splitPath(remaining);
      switch (method) {
        case "GET" -> {
          if (parts.length != 0) {
            throw new ValidationException("Unknown notifications path");
          }
          doList(req, resp, orgId);
        }
        case "POST" -> doAction(req, resp, orgId, parts);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/notifications{}", orgId, remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext ctx = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    UUID target = ctx.actorId();

    String override = req.getParameter("user_id");
    if (override != null && !override.isBlank()) {
      UUID requested = parseUuid(override, "user_id");
      if (!requested.equals(ctx.actorId())) {
        AuthzHelper.requireAdminOrSelf(req, requested); // 403 unless platform ADMIN
        target = requested;
      }
    }

    int page = intParam(req, "page", 0);
    int size = intParam(req, "size", 10);
    boolean unreadOnly = "true".equalsIgnoreCase(req.getParameter("unread"));

    List<InAppFeedItem> feed = notificationService.getFeed(orgId, target, unreadOnly, page, size);
    long total = notificationService.countFeed(orgId, target, unreadOnly);
    List<NotificationResponse> data = feed.stream().map(NotificationResponse::from).toList();
    writeJson(resp, 200, new PageResponse<>(data, total, page, size));
  }

  private void doAction(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, String[] parts)
      throws IOException {
    if (parts.length != 2) {
      throw new ValidationException("Expected /{id}/read or /{id}/dismiss");
    }
    UUID notificationId = parseUuid(parts[0], "notification id");
    String action = parts[1];
    SecurityContext ctx = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);

    switch (action) {
      case "read" -> notificationService.markRead(orgId, ctx.actorId(), notificationId);
      case "dismiss" -> notificationService.markDismissed(orgId, ctx.actorId(), notificationId);
      default -> throw new ValidationException("Unknown action: " + action);
    }
    resp.setStatus(204);
  }

  // ── plumbing (per-handler, matching the other handlers) ─────────────────────

  private String[] splitPath(String remaining) {
    if (remaining == null || remaining.isEmpty() || remaining.equals("/")) {
      return new String[0];
    }
    String raw = remaining.startsWith("/") ? remaining.substring(1) : remaining;
    return raw.split("/");
  }

  private UUID parseUuid(String raw, String what) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid " + what + " format: " + raw);
    }
  }

  private int intParam(HttpServletRequest req, String name, int defaultValue) {
    String value = req.getParameter(name);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    ApiErrors.applyHeaders(resp, e);
    writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
