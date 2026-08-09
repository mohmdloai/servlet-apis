package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.NotificationPreferenceResponse;
import com.loai.inventory.api.dto.NotificationPreferencesRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.NotificationChannel;
import com.loai.inventory.domain.model.NotificationPreference;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.NotificationService.PreferenceInput;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /api/orgs/{orgId}/notification-preferences} — a staff user's own opt-out settings.
 *
 * <ul>
 *   <li>{@code GET } — list the caller's preferences.
 *   <li>{@code PUT } — upsert (merge) the listed preferences for the caller; returns the set.
 * </ul>
 *
 * Membership is required ({@code VIEWER}); the caller only ever touches their own rows (keyed on
 * {@code ctx.actorId()} — no {@code user_id} override).
 */
public class NotificationPreferenceHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(NotificationPreferenceHandler.class);

  private final NotificationService notificationService;
  private final ObjectMapper mapper;

  public NotificationPreferenceHandler(
      NotificationService notificationService, ObjectMapper mapper) {
    this.notificationService = notificationService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID orgId, String remaining)
      throws IOException {
    try {
      if (!isRoot(remaining)) {
        throw new ValidationException("Unknown notification-preferences path");
      }
      SecurityContext ctx = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
      switch (method) {
        case "GET" -> writeSet(resp, notificationService.getUserPreferences(orgId, ctx.actorId()));
        case "PUT" -> doPut(req, resp, orgId, ctx.actorId());
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/notification-preferences", orgId, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doPut(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID userId)
      throws IOException {
    NotificationPreferencesRequest body =
        mapper.readValue(req.getInputStream(), NotificationPreferencesRequest.class);
    if (body == null || body.getPreferences() == null || body.getPreferences().isEmpty()) {
      throw new ValidationException("preferences must not be empty");
    }
    List<PreferenceInput> inputs = new ArrayList<>(body.getPreferences().size());
    for (NotificationPreferencesRequest.Item item : body.getPreferences()) {
      if (item == null || item.getEnabled() == null) {
        throw new ValidationException("each preference needs type, channel, enabled");
      }
      inputs.add(
          new PreferenceInput(item.getType(), parseChannel(item.getChannel()), item.getEnabled()));
    }
    writeSet(resp, notificationService.setUserPreferences(orgId, userId, inputs));
  }

  private static NotificationChannel parseChannel(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException("channel is required");
    }
    try {
      return NotificationChannel.fromDbValue(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("unknown channel: " + raw);
    }
  }

  private void writeSet(HttpServletResponse resp, List<NotificationPreference> prefs)
      throws IOException {
    List<NotificationPreferenceResponse> data =
        prefs.stream().map(NotificationPreferenceResponse::from).toList();
    writeJson(resp, 200, Map.of("preferences", data));
  }

  private static boolean isRoot(String remaining) {
    return remaining == null || remaining.isEmpty() || remaining.equals("/");
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
