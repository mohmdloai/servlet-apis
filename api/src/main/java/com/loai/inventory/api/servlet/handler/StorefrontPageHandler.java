package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.PageRequest;
import com.loai.inventory.api.dto.StorefrontPageResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.StorefrontPageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The merchant text-page editor under the storefront-admin namespace, owning the {@code pages}
 * sub-tree of {@code /api/orgs/{orgId}/storefront/*} (customization epic slice C4). Registered on
 * {@link com.loai.inventory.api.servlet.handler.StorefrontHandler} alongside {@code banners}:
 *
 * <ul>
 *   <li>{@code GET /storefront/pages} — all written pages, both bodies + updated_at (VIEWER)
 *   <li>{@code PUT /storefront/pages/{kind}} — idempotent upsert {body_ar?, body_en?} (STAFF)
 *   <li>{@code DELETE /storefront/pages/{kind}} — remove the page (STAFF)
 * </ul>
 *
 * VIEWER read · STAFF write (merchandising-adjacent, epic §9; system ADMIN bypasses). An unknown
 * {@code kind} value is a 400 (the closed set is the contract, the path is well-formed).
 */
public class StorefrontPageHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(StorefrontPageHandler.class);

  private final StorefrontPageService service;
  private final ObjectMapper mapper;

  public StorefrontPageHandler(StorefrontPageService service, ObjectMapper mapper) {
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
      if (parts.length == 0 || !"pages".equals(parts[0])) {
        throw new ValidationException("Expected /api/orgs/{orgId}/storefront/pages");
      }

      if (parts.length == 1) {
        if ("GET".equals(method)) {
          doList(req, resp, orgId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      if (parts.length == 2) {
        String kind = parts[1];
        switch (method) {
          case "PUT" -> doUpsert(req, resp, orgId, kind);
          case "DELETE" -> doDelete(req, resp, orgId, kind);
          default -> writeError(resp, 405, "Method not allowed");
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
    List<StorefrontPageResponse> data =
        service.list(orgId).stream().map(StorefrontPageResponse::from).toList();
    writeJson(resp, 200, data);
  }

  private void doUpsert(HttpServletRequest req, HttpServletResponse resp, UUID orgId, String kind)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    PageRequest body = readBody(req, PageRequest.class);
    StorefrontPageResponse saved =
        StorefrontPageResponse.from(service.upsert(orgId, kind, body.toInput()));
    writeJson(resp, 200, saved);
  }

  private void doDelete(HttpServletRequest req, HttpServletResponse resp, UUID orgId, String kind)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    service.delete(orgId, kind);
    resp.setStatus(204);
  }

  // --- helpers ---

  private static String normalize(String remaining) {
    if (remaining == null) {
      return "";
    }
    String t = remaining.startsWith("/") ? remaining.substring(1) : remaining;
    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
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
    ApiErrors.applyHeaders(resp, e);
    writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
