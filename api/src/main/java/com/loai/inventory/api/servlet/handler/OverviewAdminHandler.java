package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.AdminOverviewResponse;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.service.platform.PlatformOverviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The platform operator's home read at {@code GET /api/admin/overview} (slice 1 of the
 * platform-console epic). Gated on {@code requirePlatformRead} (ADMIN or SUPPORT) — the whole
 * resource is a read, so both tiers see byte-identical output. {@code GET} only: any other verb is
 * a 405 and any subpath a 404, mirroring {@link AuditAdminHandler}.
 *
 * <p>One call rather than a fan-out, because it is the only way to hand the client a single
 * coherent {@code as_of}. Section-level failures never reach here as errors — {@link
 * PlatformOverviewService} degrades them to {@code null} plus a name in {@code degraded}, and this
 * read still returns 200.
 */
public class OverviewAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(OverviewAdminHandler.class);

  private final PlatformOverviewService overviewService;
  private final ObjectMapper mapper;

  public OverviewAdminHandler(PlatformOverviewService overviewService, ObjectMapper mapper) {
    this.overviewService = overviewService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      if (remaining != null && !remaining.isEmpty() && !remaining.equals("/")) {
        writeError(resp, 404, "Unknown overview endpoint");
        return;
      }
      if (!"GET".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      AuthzHelper.requirePlatformRead(req);

      writeJson(resp, 200, AdminOverviewResponse.from(overviewService.overview()));
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/overview{}", remaining, e);
      writeError(resp, 500, "Internal server error");
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
