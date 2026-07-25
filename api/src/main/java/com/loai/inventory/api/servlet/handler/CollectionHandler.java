package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CollectionRequest;
import com.loai.inventory.api.dto.CollectionResponse;
import com.loai.inventory.api.dto.ProductListingResponse;
import com.loai.inventory.api.dto.SetCollectionListingsRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Collection;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.CollectionService;
import com.loai.inventory.service.CollectionService.CollectionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes under {@code /api/orgs/{orgId}/collections} (roadmap item 8, {@code
 * stories/storefront_collections.md}) — merchant curation of named listing collections.
 *
 * <ul>
 *   <li>{@code GET /} — every collection in rail order, each with both names + {@code
 *       listing_count}
 *   <li>{@code POST /} — create ({@code {slug, name_ar, name_en, sort_order?}})
 *   <li>{@code GET|PUT|DELETE /{id}} — read / edit names+slug+sort / delete (membership cascades,
 *       listings untouched)
 *   <li>{@code GET|PUT /{id}/listings} — the curated membership; PUT is an atomic set-replace
 * </ul>
 *
 * VIEWER read · STAFF write (the featured-curation gate — merchandising is a staff act, and nothing
 * here touches money) · MANAGER delete.
 */
public class CollectionHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(CollectionHandler.class);

  private final CollectionService service;
  private final ObjectMapper mapper;

  public CollectionHandler(CollectionService service, ObjectMapper mapper) {
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
      String tail = normalize(remainingPath);

      if (tail.isEmpty()) {
        switch (method) {
          case "GET" -> doList(req, resp, orgId);
          case "POST" -> doCreate(req, resp, orgId);
          default -> writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      String[] parts = tail.split("/");
      UUID id = parseId(parts[0]);

      if (parts.length == 1) {
        switch (method) {
          case "GET" -> doGetOne(req, resp, orgId, id);
          case "PUT" -> doUpdate(req, resp, orgId, id);
          case "DELETE" -> doDelete(req, resp, orgId, id);
          default -> writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      if (parts.length == 2 && "listings".equals(parts[1])) {
        switch (method) {
          case "GET" -> doGetListings(req, resp, orgId, id);
          case "PUT" -> doSetListings(req, resp, orgId, id);
          default -> writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      throw new ValidationException("Unknown route: /collections/" + tail);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/collections{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    // Unpaginated on purpose: the per-org cap is 30, so a page would be pure ceremony — and the
    // rail
    // order is only meaningful over the whole set.
    List<CollectionResponse> data =
        service.getAll(orgId).stream().map(CollectionResponse::from).toList();
    writeJson(resp, 200, data);
  }

  private void doCreate(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    CollectionRequest body = readBody(req, CollectionRequest.class);
    Collection created =
        service.create(
            orgId, body.getSlug(), body.getNameAr(), body.getNameEn(), body.getSortOrder());
    // Re-read for the full shape (both names + the count), mirroring the category handler.
    writeJson(resp, 201, CollectionResponse.from(service.getById(orgId, created.getId())));
  }

  private void doGetOne(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    CollectionView view = service.getById(orgId, id);
    writeJson(resp, 200, CollectionResponse.from(view));
  }

  private void doUpdate(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    CollectionRequest body = readBody(req, CollectionRequest.class);
    service.update(
        orgId, id, body.getSlug(), body.getNameAr(), body.getNameEn(), body.getSortOrder());
    writeJson(resp, 200, CollectionResponse.from(service.getById(orgId, id)));
  }

  private void doDelete(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    service.delete(orgId, id);
    resp.setStatus(204);
  }

  private void doGetListings(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    List<ProductListingResponse> data =
        service.getListings(orgId, id).stream().map(ProductListingResponse::fromView).toList();
    writeJson(resp, 200, data);
  }

  private void doSetListings(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    SetCollectionListingsRequest body = readBody(req, SetCollectionListingsRequest.class);
    List<ProductListingResponse> data =
        service.setListings(orgId, id, body.getListingIds()).stream()
            .map(ProductListingResponse::fromView)
            .toList();
    writeJson(resp, 200, data);
  }

  private static String normalize(String remainingPath) {
    if (remainingPath == null) {
      return "";
    }
    String t = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    while (t.endsWith("/")) {
      t = t.substring(0, t.length() - 1);
    }
    return t;
  }

  private UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid collection id format: " + raw);
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
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
