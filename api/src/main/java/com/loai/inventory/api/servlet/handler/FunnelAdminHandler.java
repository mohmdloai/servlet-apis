package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PlatformFunnelResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.service.platform.PlatformFunnelService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tenant lifecycle funnel at {@code GET /api/admin/funnel?cohort=&path=} (slice 7 of the
 * platform-console epic, {@code stories/platform_tenant_funnel.md}) — the first console surface
 * that answers "is the product working" rather than "what is happening right now". Gated on {@code
 * requirePlatformRead} (ADMIN or SUPPORT): the whole resource is a read, so both tiers see
 * byte-identical output, unaudited like {@code /orgs}, {@code /users}, {@code /search}.
 *
 * <p><strong>Routing follows {@link OverviewAdminHandler} and {@link SearchAdminHandler}, not
 * {@link QueuesAdminHandler}:</strong> {@code GET} only → 405, and any subpath → 404. {@code
 * /funnel} takes no path segment — unlike {@code /queues/{kind}}, there is no enum value living in
 * the URL, so an unknown segment is the unknown-resource 404, not a 400.
 *
 * <p>Bad {@code ?cohort=} or {@code ?path=} — including a missing one, deliberately (see {@link
 * PlatformFunnelService}) — is the 400 here, naming the accepted values.
 */
public class FunnelAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(FunnelAdminHandler.class);

  private final PlatformFunnelService funnelService;
  private final ObjectMapper mapper;

  public FunnelAdminHandler(PlatformFunnelService funnelService, ObjectMapper mapper) {
    this.funnelService = funnelService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      if (remaining != null && !remaining.isEmpty() && !remaining.equals("/")) {
        writeError(resp, 404, "Unknown funnel endpoint");
        return;
      }
      if (!"GET".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      AuthzHelper.requirePlatformRead(req);

      PlatformFunnelService.Funnel funnel =
          funnelService.funnel(
              req.getParameter("cohort"), req.getParameter("path"), OffsetDateTime.now());
      writeJson(resp, 200, PlatformFunnelResponse.from(funnel));
    } catch (AppException e) {
      writeError(resp, e.getStatusCode(), e.getMessage());
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/funnel{}", remaining, e);
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
