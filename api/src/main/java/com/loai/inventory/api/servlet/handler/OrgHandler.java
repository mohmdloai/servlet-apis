package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.CreateOrgRequest;
import com.loai.inventory.api.dto.OrgResponse;
import com.loai.inventory.api.dto.UpdateOrgRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.OrgService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles /api/orgs and /api/orgs/{orgId} (org CRUD only).
 *
 * <p>For nested resources like /api/orgs/{orgId}/products, the dispatcher routes elsewhere.
 */
public class OrgHandler {

  private static final Logger log = LoggerFactory.getLogger(OrgHandler.class);

  private final OrgService orgService;
  private final ObjectMapper mapper;

  public OrgHandler(OrgService orgService, ObjectMapper mapper) {
    this.orgService = orgService;
    this.mapper = mapper;
  }

  /** {@code orgId} is null for collection-level requests. */
  public void handle(String method, HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    try {
      switch (method) {
        case "GET" -> doGet(req, resp, orgId);
        case "POST" -> doPost(req, resp, orgId);
        case "PUT" -> doPut(req, resp, orgId);
        case "DELETE" -> doDelete(req, resp, orgId);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs", e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext ctx = AuthzHelper.requireAuth(req);

    if (orgId == null) {
      List<UUID> ids = ctx.orgRoles() == null ? List.of() : List.copyOf(ctx.orgRoles().keySet());
      List<Org> orgs = orgService.listForUser(ids);
      List<OrgResponse> data = orgs.stream().map(OrgResponse::from).toList();
      writeJson(resp, 200, data);
    } else {
      AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
      Org org = orgService.getById(orgId);
      writeJson(resp, 200, OrgResponse.from(org));
    }
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext ctx = AuthzHelper.requireAuth(req);
    if (orgId != null) {
      throw new ValidationException("POST does not accept an org id in the path");
    }

    CreateOrgRequest body = readBody(req, CreateOrgRequest.class);
    Org created = orgService.create(body.getName(), body.getSlug(), ctx.actorId());
    writeJson(resp, 201, OrgResponse.from(created));
  }

  private void doPut(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    if (orgId == null) {
      throw new ValidationException("Org id is required for update");
    }
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);

    UpdateOrgRequest body = readBody(req, UpdateOrgRequest.class);
    OrgService.BillingProfile profile =
        new OrgService.BillingProfile(
            body.getLegalName(),
            body.getTaxRegistrationNumber(),
            body.getAddressLine1(),
            body.getAddressLine2(),
            body.getCity(),
            body.getCountry(),
            body.getPhone(),
            body.getContactEmail(),
            body.getLogoObjectKey());
    OrgService.StorefrontBranding branding =
        new OrgService.StorefrontBranding(
            body.getThemeColor(),
            body.getInstapayHandle(),
            body.getPaymentInstructions(),
            body.getDefaultLocale());
    OrgService.SeoMetadata seo =
        new OrgService.SeoMetadata(
            body.getMetaTitle(),
            body.getMetaDescription(),
            body.getOgImageObjectKey(),
            body.getDiscoverable());
    OrgService.StoreConfig storeConfig =
        new OrgService.StoreConfig(body.getTaxRate(), body.getShippingFee());
    Org updated =
        orgService.update(
            orgId,
            body.getName(),
            body.getRefundApprovalThreshold(),
            body.getOrderTtlMinutes(),
            profile,
            branding,
            seo,
            storeConfig);
    writeJson(resp, 200, OrgResponse.from(updated));
  }

  private void doDelete(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    if (orgId == null) {
      throw new ValidationException("Org id is required for delete");
    }
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
    orgService.delete(orgId);
    resp.setStatus(204);
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Empty/truncated/malformed body — a 400, not an unhandled 500.
      throw new com.loai.inventory.common.exception.ValidationException(
          "request body is required and must be valid JSON");
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
