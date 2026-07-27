package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.AdminQueueRowResponse;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PlatformQueueKind;
import com.loai.inventory.service.platform.PlatformQueueService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cross-org queue drill-down at {@code GET /api/admin/queues/{kind}} (slice 2 of the
 * platform-console epic, {@code stories/platform_queues.md}). Gated on {@code requirePlatformRead}
 * (ADMIN or SUPPORT) — the whole resource is a read, so both tiers see byte-identical output.
 * {@code GET} only: any other verb is a 405, mirroring {@link OverviewAdminHandler} and {@link
 * AuditAdminHandler}.
 *
 * <p><strong>Bad input is a 400 here, not a 404.</strong> An unknown {@code kind}, and the bare
 * {@code GET /api/admin/queues}, both answer with a 400 naming the five: {@code kind} is an enum
 * value that happens to sit in the path, so it follows the {@code ?status=} convention rather than
 * the unknown-resource one, and a reserved route root is the 400 that {@code GET /sales-orders} and
 * {@code GET /credit-notes} already return. An {@code ?org_id=} that is not a UUID is a 400 (the
 * {@link AuditAdminHandler#uuidParam} precedent), but an {@code ?org_id=} no tenant holds is an
 * <em>empty page</em> — it is a filter, not a lookup, exactly as {@code credit_note_id} behaves.
 *
 * <p><strong>Ordering is oldest-first, always, and there is no ledger mode.</strong> Every other
 * worklist in this codebase switches between queue order (filtered, oldest-first) and ledger order
 * (unfiltered, newest-first). That convention is not missing here by oversight: a newest-first list
 * of every failed email across every tenant is not a thing anyone works. All five kinds are queues
 * by construction, so the convention collapses to its queue half.
 */
public class QueuesAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(QueuesAdminHandler.class);

  private final PlatformQueueService queueService;
  private final ObjectMapper mapper;

  public QueuesAdminHandler(PlatformQueueService queueService, ObjectMapper mapper) {
    this.queueService = queueService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      if (!"GET".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      AuthzHelper.requirePlatformRead(req);

      // parseKind turns the bare root ("" or "/") and an unknown segment into the same 400 naming
      // the five — one message for one class of mistake.
      PlatformQueueKind kind = PlatformQueueService.parseKind(segment(remaining));
      UUID orgId = uuidParam(req, "org_id");
      int page = intParam(req, "page", 0);
      int size = intParam(req, "size", PlatformQueueService.DEFAULT_PAGE_SIZE);

      PlatformQueueService.QueuePage result = queueService.list(kind, orgId, page, size);
      List<AdminQueueRowResponse> data =
          result.rows().stream().map(AdminQueueRowResponse::from).toList();
      writeJson(resp, 200, new PageResponse<>(data, result.total(), result.page(), result.size()));
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/queues{}", remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  /**
   * The single path segment after {@code /queues}. A deeper path ({@code /queues/x/y}) keeps its
   * tail so it cannot be mistaken for a valid kind, and falls into the same 400.
   */
  private String segment(String remaining) {
    if (remaining == null || remaining.isEmpty() || remaining.equals("/")) {
      return "";
    }
    String raw = remaining.startsWith("/") ? remaining.substring(1) : remaining;
    return raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
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
