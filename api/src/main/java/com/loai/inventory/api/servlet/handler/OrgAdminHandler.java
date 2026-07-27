package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.AdminCreateOrgRequest;
import com.loai.inventory.api.dto.AdminOrgDetailResponse;
import com.loai.inventory.api.dto.AdminOrgSummaryResponse;
import com.loai.inventory.api.dto.AdminProvisionOrgResponse;
import com.loai.inventory.api.dto.AdminUpdateOrgRequest;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.OrgResponse;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.SuspendOrgRequest;
import com.loai.inventory.api.filter.JwtAuthFilter;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.platform.PlatformOrgService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform cross-org console at {@code /api/admin/orgs[/{orgId}]} (see {@code
 * docs/platform-admin-plan.md}, slice 2). Reads require {@link AuthzHelper#requirePlatformRead}
 * (ADMIN or SUPPORT) - no org role needed. Org lifecycle mutations (slice 5) are added here later.
 *
 * <ul>
 *   <li>{@code GET /api/admin/orgs?page&size&status=active|pending|suspended} - paged list + member
 *       counts
 *   <li>{@code GET /api/admin/orgs/{orgId}} - org + operational rollup
 *   <li>{@code POST /api/admin/orgs/{orgId}/suspend} · {@code .../reactivate} - ADMIN only
 * </ul>
 */
public class OrgAdminHandler implements AdminResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(OrgAdminHandler.class);

  private final PlatformOrgService platformOrgService;
  private final ObjectMapper mapper;

  public OrgAdminHandler(PlatformOrgService platformOrgService, ObjectMapper mapper) {
    this.platformOrgService = platformOrgService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, String remaining)
      throws IOException {
    try {
      Path path = parsePath(remaining);
      switch (method) {
        case "GET" -> doGet(req, resp, path);
        case "POST" -> doPost(req, resp, path);
        case "PATCH" -> doPatch(req, resp, path);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/admin/orgs{}", remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, Path path)
      throws IOException {
    AuthzHelper.requirePlatformRead(req);

    if (path.orgId() == null) {
      int page = intParam(req, "page", 0);
      int size = intParam(req, "size", PlatformOrgService.DEFAULT_PAGE_SIZE);
      OrgStatus status = parseStatus(req.getParameter("status"));

      PlatformOrgService.OrgPage result = platformOrgService.list(page, size, status);
      List<AdminOrgSummaryResponse> data =
          result.items().stream().map(AdminOrgSummaryResponse::from).toList();
      writeJson(resp, 200, new PageResponse<>(data, result.total(), result.page(), result.size()));
    } else {
      PlatformOrgService.OrgWithHealth detail = platformOrgService.getWithHealth(path.orgId());
      writeJson(resp, 200, AdminOrgDetailResponse.from(detail));
    }
  }

  /**
   * {@code POST /api/admin/orgs} provisions a client org with a first OWNER (PG1); {@code POST
   * /api/admin/orgs/{orgId}/suspend|reactivate} runs org lifecycle (slice 5). Both ADMIN only.
   */
  private void doPost(HttpServletRequest req, HttpServletResponse resp, Path path)
      throws IOException {
    if (path.orgId() == null) {
      SecurityContext ctx = AuthzHelper.requireAdmin(req);
      Environment env = (Environment) req.getAttribute(JwtAuthFilter.ENVIRONMENT_ATTR);
      AdminCreateOrgRequest body = readBody(req, AdminCreateOrgRequest.class);
      PlatformOrgService.ProvisionResult result =
          platformOrgService.provision(
              ctx, env, body.getName(), body.getSlug(), body.getOwnerEmail());
      writeJson(resp, 201, AdminProvisionOrgResponse.from(result));
      return;
    }
    if (path.action() == null) {
      throw new ValidationException("POST requires an action (suspend|reactivate)");
    }
    SecurityContext ctx = AuthzHelper.requireAdmin(req);
    Environment env = (Environment) req.getAttribute(JwtAuthFilter.ENVIRONMENT_ATTR);

    Org updated;
    switch (path.action()) {
      case "suspend" -> {
        SuspendOrgRequest body = readBodyOrNull(req);
        String reason = body == null ? null : body.getReason();
        updated = platformOrgService.suspend(ctx, env, path.orgId(), reason);
      }
      case "reactivate" -> updated = platformOrgService.reactivate(ctx, env, path.orgId());
      default -> {
        writeError(resp, 404, "Unknown org action: " + path.action());
        return;
      }
    }
    writeJson(resp, 200, OrgResponse.from(updated));
  }

  /** {@code PATCH /api/admin/orgs/{orgId}} - edit name/policy on the admin plane (ADMIN, #3). */
  private void doPatch(HttpServletRequest req, HttpServletResponse resp, Path path)
      throws IOException {
    if (path.orgId() == null || path.action() != null) {
      throw new ValidationException("PATCH requires an org id and no trailing action");
    }
    SecurityContext ctx = AuthzHelper.requireAdmin(req);
    Environment env = (Environment) req.getAttribute(JwtAuthFilter.ENVIRONMENT_ATTR);
    AdminUpdateOrgRequest body = readBody(req, AdminUpdateOrgRequest.class);
    com.loai.inventory.service.OrgService.StorefrontBranding branding =
        new com.loai.inventory.service.OrgService.StorefrontBranding(
            body.getThemeColor(),
            body.getInstapayHandle(),
            body.getPaymentInstructions(),
            body.getDefaultLocale());
    com.loai.inventory.service.OrgService.SeoMetadata seo =
        new com.loai.inventory.service.OrgService.SeoMetadata(
            body.getMetaTitle(), body.getMetaDescription(), body.getOgImageObjectKey());
    Org updated =
        platformOrgService.updateOrg(
            ctx,
            env,
            path.orgId(),
            body.getName(),
            body.getRefundApprovalThreshold(),
            body.getOrderTtlMinutes(),
            branding,
            seo,
            new com.loai.inventory.service.OrgService.StoreConfig(
                body.getTaxRate(), body.getShippingFee()));
    writeJson(resp, 200, OrgResponse.from(updated));
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("malformed JSON body");
    }
  }

  private SuspendOrgRequest readBodyOrNull(HttpServletRequest req) throws IOException {
    if (req.getContentLength() <= 0) {
      return null;
    }
    try {
      return mapper.readValue(req.getInputStream(), SuspendOrgRequest.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("malformed JSON body");
    }
  }

  /**
   * {@code status} query param → the {@link OrgStatus} narrow ({@code null} = all). The three
   * statuses partition the table, so an unknown value names all three rather than silently
   * defaulting.
   */
  private OrgStatus parseStatus(String status) {
    try {
      return OrgStatus.fromWire(status);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("status must be 'active', 'pending' or 'suspended'");
    }
  }

  /**
   * {@code orgId} is null for the collection; {@code action} is the segment after the id, if any.
   */
  private record Path(UUID orgId, String action) {}

  private Path parsePath(String remaining) {
    if (remaining == null || remaining.isEmpty() || remaining.equals("/")) {
      return new Path(null, null);
    }
    String raw = remaining.startsWith("/") ? remaining.substring(1) : remaining;
    String[] seg = raw.split("/", 2);
    UUID orgId;
    try {
      orgId = UUID.fromString(seg[0]);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid org id format: " + seg[0]);
    }
    String action = seg.length > 1 && !seg[1].isEmpty() ? seg[1] : null;
    return new Path(orgId, action);
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

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
