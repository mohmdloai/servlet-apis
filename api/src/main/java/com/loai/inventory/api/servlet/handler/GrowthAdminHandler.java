package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PlatformGrowthResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.service.platform.PlatformGrowthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The growth series at {@code GET /api/admin/growth?window=&bucket=&path=} (slice 8 of the
 * platform-console epic, {@code stories/platform_growth_series.md}) — the <em>event</em> companion
 * to the funnel's <em>cohort</em> read: how many tenants arrived at each stage per week or month,
 * not how far one set of tenants got. Gated on {@code requirePlatformRead} (ADMIN or SUPPORT): the
 * whole resource is a read, so both tiers see byte-identical output, unaudited like {@code /orgs},
 * {@code /users}, {@code /funnel}.
 *
 * <p><strong>Routing follows {@link OverviewAdminHandler} and {@link FunnelAdminHandler}, not
 * {@link QueuesAdminHandler}:</strong> {@code GET} only → 405, and any subpath → 404. {@code
 * /growth} takes no path segment — no enum value lives in the URL, so an unknown segment is the
 * unknown-resource 404, not a 400.
 *
 * <p>Bad {@code ?window=}, {@code ?bucket=}, or {@code ?path=} — including a missing one,
 * deliberately (the slice-7 convention for enum-shaped parameters) — is the 400 here, naming the
 * accepted values.
 */
public class GrowthAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(GrowthAdminHandler.class);

  private final PlatformGrowthService growthService;
  private final ObjectMapper mapper;

  public GrowthAdminHandler(PlatformGrowthService growthService, ObjectMapper mapper) {
    this.growthService = growthService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      if (remaining != null && !remaining.isEmpty() && !remaining.equals("/")) {
        writeError(resp, 404, "Unknown growth endpoint");
        return;
      }
      if (!"GET".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      AuthzHelper.requirePlatformRead(req);

      PlatformGrowthService.Growth growth =
          growthService.growth(
              req.getParameter("window"),
              req.getParameter("bucket"),
              req.getParameter("path"),
              OffsetDateTime.now());
      writeJson(resp, 200, PlatformGrowthResponse.from(growth));
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/growth{}", remaining, e);
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
