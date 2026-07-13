package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.BannerPresignResponse;
import com.loai.inventory.api.dto.BannerRequest;
import com.loai.inventory.api.dto.BannerResponse;
import com.loai.inventory.api.dto.PresignImageUploadRequest;
import com.loai.inventory.api.dto.ReorderBannersRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.StorefrontBannerService;
import com.loai.inventory.service.StorefrontBannerService.BannerView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The merchant banner editor under the storefront-admin namespace {@code
 * /api/orgs/{orgId}/storefront/*} (customization epic slice C1 — later slices add sibling resources
 * here). Registered on {@link com.loai.inventory.api.servlet.OrgServlet} under the {@code
 * storefront} sub-resource; this handler owns the {@code banners} sub-tree:
 *
 * <ul>
 *   <li>{@code GET /storefront/banners} — all rows, {@code sort_order ASC} (VIEWER)
 *   <li>{@code POST /storefront/banners} — create (STAFF, 201)
 *   <li>{@code PUT|DELETE /storefront/banners/{id}} — merge-edit / delete (STAFF)
 *   <li>{@code PUT /storefront/banners/order} — atomic reorder set-replace (STAFF)
 *   <li>{@code POST /storefront/banners/presign} — presigned image upload slot (STAFF)
 * </ul>
 *
 * VIEWER read · STAFF write (merchandising, epic §9; system ADMIN bypasses).
 */
public class BannerHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(BannerHandler.class);

  private final StorefrontBannerService service;
  private final ObjectMapper mapper;

  public BannerHandler(StorefrontBannerService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID orgId, String remaining)
      throws IOException {
    try {
      String tail = normalize(remaining);
      String[] parts = tail.isEmpty() ? new String[0] : tail.split("/");
      if (parts.length == 0 || !"banners".equals(parts[0])) {
        throw new ValidationException("Expected /api/orgs/{orgId}/storefront/banners");
      }

      if (parts.length == 1) {
        switch (method) {
          case "GET" -> doList(req, resp, orgId);
          case "POST" -> doCreate(req, resp, orgId);
          default -> writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      if (parts.length == 2) {
        switch (parts[1]) {
          case "order" -> {
            if ("PUT".equals(method)) doReorder(req, resp, orgId);
            else writeError(resp, 405, "Method not allowed");
          }
          case "presign" -> {
            if ("POST".equals(method)) doPresign(req, resp, orgId);
            else writeError(resp, 405, "Method not allowed");
          }
          default -> {
            UUID id = parseId(parts[1]);
            switch (method) {
              case "PUT" -> doUpdate(req, resp, orgId, id);
              case "DELETE" -> doDelete(req, resp, orgId, id);
              default -> writeError(resp, 405, "Method not allowed");
            }
          }
        }
        return;
      }

      throw new ValidationException("Unknown route: /storefront/" + tail);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/storefront{}", orgId, remaining, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    List<BannerResponse> data = service.list(orgId).stream().map(BannerResponse::from).toList();
    writeJson(resp, 200, data);
  }

  private void doCreate(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    BannerRequest body = readBody(req, BannerRequest.class);
    BannerView created = service.create(orgId, body.toInput());
    writeJson(resp, 201, BannerResponse.from(created));
  }

  private void doUpdate(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    BannerRequest body = readBody(req, BannerRequest.class);
    BannerView updated = service.update(orgId, id, body.toInput());
    writeJson(resp, 200, BannerResponse.from(updated));
  }

  private void doDelete(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    service.delete(orgId, id);
    resp.setStatus(204);
  }

  private void doReorder(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    ReorderBannersRequest body = readBody(req, ReorderBannersRequest.class);
    List<BannerResponse> data =
        service.reorder(orgId, body.getIds()).stream().map(BannerResponse::from).toList();
    writeJson(resp, 200, data);
  }

  private void doPresign(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    PresignImageUploadRequest body = readBody(req, PresignImageUploadRequest.class);
    StorefrontBannerService.PresignResult result =
        service.presignImageUpload(orgId, body.getFilename(), body.getContentType());
    writeJson(resp, 200, BannerPresignResponse.from(result));
  }

  // --- helpers ---

  private static String normalize(String remaining) {
    if (remaining == null) {
      return "";
    }
    String t = remaining.startsWith("/") ? remaining.substring(1) : remaining;
    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid banner id format: " + raw);
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    byte[] bytes = req.getInputStream() == null ? new byte[0] : req.getInputStream().readAllBytes();
    if (bytes.length == 0) {
      throw new ValidationException("request body is required");
    }
    try {
      return mapper.readValue(bytes, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("malformed JSON body");
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
