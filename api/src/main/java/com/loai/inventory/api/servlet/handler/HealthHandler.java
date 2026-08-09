package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.OrgHealthResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.OrgHealthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code GET /api/orgs/{orgId}/health} — the org's operational rollup for the tenant
 * dashboard strip ({@code stories/org_health_rollup.md}). One call returns {@code {member_count,
 * pending_payment_orders, open_disputes, unallocated_payments}}, collapsing the strip's per-tile
 * {@code size=1} fan-out.
 *
 * <p><b>MANAGER+</b>: the rollup carries a roster-derived {@code member_count}, so it is gated no
 * more permissively than the members roster read itself. Suspension is enforced by {@code
 * requireOrgAccess} (platform-ADMIN bypass preserved).
 */
public class HealthHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(HealthHandler.class);

  private final OrgHealthService service;
  private final ObjectMapper mapper;

  public HealthHandler(OrgHealthService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
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
      if (remainingPath != null && !remainingPath.isEmpty() && !"/".equals(remainingPath)) {
        writeError(resp, 404, "Unknown health route: " + remainingPath);
        return;
      }
      if (!"GET".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
      writeJson(resp, 200, OrgHealthResponse.from(service.health(orgId)));
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/health{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
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
