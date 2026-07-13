package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PresignLogoRequest;
import com.loai.inventory.api.dto.PresignLogoResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.OrgService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Storefront og-image upload under {@code /api/orgs/{orgId}/og-image/*} (slice C2). {@code POST
 * /og-image/presign} (STAFF) mirrors the logo presign flow: it hands out a presigned PUT URL + an
 * org-scoped {@code {orgId}/og/…} object key; the client uploads the bytes, then sets {@code
 * og_image_object_key} via {@code PUT /api/orgs/{orgId}} (which enforces the same prefix). The
 * image itself is served — to social crawlers, anonymously — by the stable {@code GET
 * /api/public/{orgSlug}/og-image} route, never a presigned URL. Reuses the logo presign DTOs (same
 * {@code {filename, content_type}} → {@code {upload_url, object_key, expires_in_seconds}} shape).
 * See {@code stories/storefront_seo_metadata.md}.
 */
public class OgImageHandler implements OrgResourceHandler {

  private final OrgService orgService;
  private final ObjectMapper mapper;

  public OgImageHandler(OrgService orgService, ObjectMapper mapper) {
    this.orgService = orgService;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID orgId, String remaining)
      throws IOException {
    try {
      String[] parts = split(remaining);
      if (parts.length == 1 && "presign".equals(parts[0])) {
        if (!"POST".equals(method)) {
          writeError(resp, 405, "Method not allowed");
          return;
        }
        AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
        PresignLogoRequest body = readBody(req, PresignLogoRequest.class);
        OrgService.LogoPresign result =
            orgService.presignOgImageUpload(orgId, body.getFilename(), body.getContentType());
        writeJson(resp, 200, PresignLogoResponse.from(result));
      } else {
        throw new ValidationException("Expected /api/orgs/{orgId}/og-image/presign");
      }
    } catch (AppException e) {
      writeError(resp, e);
    }
  }

  private static String[] split(String remaining) {
    if (remaining == null || remaining.isEmpty() || remaining.equals("/")) {
      return new String[0];
    }
    String raw = remaining.startsWith("/") ? remaining.substring(1) : remaining;
    if (raw.endsWith("/")) {
      raw = raw.substring(0, raw.length() - 1);
    }
    return raw.split("/");
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("request body is required and must be valid JSON");
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
