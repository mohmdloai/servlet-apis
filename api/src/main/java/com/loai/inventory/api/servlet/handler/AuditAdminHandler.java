package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.AdminAuditResponse;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.service.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The platform audit-log read at {@code GET /api/admin/audit} (PG2). Gated on {@code
 * requirePlatformRead} (ADMIN or SUPPORT): reading the accountability ledger is a read op, so
 * SUPPORT sees it too. Newest-first, paged, optionally filtered by {@code target_type} and {@code
 * actor_id}. The ledger is append-only - there are no mutations here.
 */
public class AuditAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(AuditAdminHandler.class);

  private final PlatformAuditService auditService;
  private final ObjectMapper mapper;

  public AuditAdminHandler(PlatformAuditService auditService, ObjectMapper mapper) {
    this.auditService = auditService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      if (remaining != null && !remaining.isEmpty() && !remaining.equals("/")) {
        writeError(resp, 404, "Unknown audit endpoint");
        return;
      }
      if (!"GET".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      AuthzHelper.requirePlatformRead(req);

      int page = intParam(req, "page", 0);
      int size = intParam(req, "size", PlatformAuditService.DEFAULT_PAGE_SIZE);
      String targetType = trimToNull(req.getParameter("target_type"));
      UUID actorId = uuidParam(req, "actor_id");

      PlatformAuditService.AuditPage result = auditService.list(page, size, targetType, actorId);
      List<AdminAuditResponse> data =
          result.entries().stream().map(e -> AdminAuditResponse.from(e, mapper)).toList();
      writeJson(resp, 200, new PageResponse<>(data, result.total(), result.page(), result.size()));
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/audit{}", remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private String trimToNull(String raw) {
    if (raw == null) {
      return null;
    }
    String t = raw.trim();
    return t.isEmpty() ? null : t;
  }

  private UUID uuidParam(HttpServletRequest req, String name) {
    String raw = trimToNull(req.getParameter(name));
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Parameter '" + name + "' must be a UUID");
    }
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
