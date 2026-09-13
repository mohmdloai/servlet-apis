package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PlatformNotificationResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
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
 * {@code /api/admin/notifications} — the operator's own in-app feed, every tenant's rows ({@code
 * stories/support_ticket_reach.md}). A user-scoped read on the platform plane, the way {@code
 * /api/me} reads across a user's memberships: keyed on {@code recipient_user_id}, so nothing here
 * can return another user's row. The thin platform twin of {@link NotificationHandler}, delegating
 * to the same service.
 *
 * <pre>
 * GET   /                 ?unread=true&page&size   the caller's feed, newest first, + org {id,name}
 * POST  /{id}/read                                 204 · 404 for a row that is not the caller's
 * POST  /{id}/dismiss                              204 · 404
 * </pre>
 *
 * Gate: {@link AuthzHelper#requirePlatformRead} for the read, {@link
 * AuthzHelper#requireSupportDesk} for the two writes — both tiers, because marking one's own row
 * read changes no tenant data.
 */
public class NotificationAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(NotificationAdminHandler.class);

  private final NotificationService notificationService;
  private final ObjectMapper mapper;

  public NotificationAdminHandler(NotificationService notificationService, ObjectMapper mapper) {
    this.notificationService = notificationService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      String[] parts = splitPath(remaining);
      switch (method) {
        case "GET" -> {
          if (parts.length != 0) {
            throw new ValidationException("Unknown notifications path");
          }
          doList(req, resp);
        }
        case "POST" -> doAction(req, resp, parts);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/notifications{}", remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doList(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    SecurityContext ctx = AuthzHelper.requirePlatformRead(req);
    int page = intParam(req, "page", 0);
    int size = intParam(req, "size", 10);
    boolean unreadOnly = "true".equalsIgnoreCase(req.getParameter("unread"));

    List<NotificationService.UserFeedItem> feed =
        notificationService.getUserFeed(ctx.actorId(), unreadOnly, page, size);
    long total = notificationService.countUserFeed(ctx.actorId(), unreadOnly);
    List<PlatformNotificationResponse> data =
        feed.stream().map(PlatformNotificationResponse::from).toList();
    writeJson(resp, 200, new PageResponse<>(data, total, page, size));
  }

  private void doAction(HttpServletRequest req, HttpServletResponse resp, String[] parts)
      throws IOException {
    if (parts.length != 2) {
      throw new ValidationException("Expected /{id}/read or /{id}/dismiss");
    }
    SecurityContext ctx = AuthzHelper.requireSupportDesk(req);
    UUID notificationId = parseUuid(parts[0], "notification id");
    switch (parts[1]) {
      case "read" -> notificationService.markOwnRead(ctx.actorId(), notificationId);
      case "dismiss" -> notificationService.markOwnDismissed(ctx.actorId(), notificationId);
      default -> throw new ValidationException("Unknown action: " + parts[1]);
    }
    resp.setStatus(204);
  }

  // plumbing (per-handler, matching the other handlers)

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
