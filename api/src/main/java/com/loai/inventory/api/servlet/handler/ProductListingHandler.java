package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.AttachImageRequest;
import com.loai.inventory.api.dto.CreateProductListingRequest;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PresignImageUploadRequest;
import com.loai.inventory.api.dto.PresignImageUploadResponse;
import com.loai.inventory.api.dto.ProductListingImageResponse;
import com.loai.inventory.api.dto.ProductListingResponse;
import com.loai.inventory.api.dto.ProductListingTranslationDto;
import com.loai.inventory.api.dto.SetCategoriesRequest;
import com.loai.inventory.api.dto.SetFeaturedListingsRequest;
import com.loai.inventory.api.dto.UpdateImageRequest;
import com.loai.inventory.api.dto.UpdateProductListingRequest;
import com.loai.inventory.api.dto.VariantSetRequest;
import com.loai.inventory.api.dto.VariantSetResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductListingService.ImageView;
import com.loai.inventory.service.ProductListingService.ListingView;
import com.loai.inventory.service.ProductListingService.PresignResult;
import com.loai.inventory.service.ProductVariantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes under {@code /api/orgs/{orgId}/product-listings}:
 *
 * <ul>
 *   <li>{@code GET|POST /} — list (optional {@code ?status=}) / create (201)
 *   <li>{@code GET|PUT|DELETE /{id}} — read / update / delete
 *   <li>{@code POST /{id}/publish|unpublish|archive} — lifecycle (STAFF)
 *   <li>{@code GET|PUT /{id}/categories} — read / replace category set
 *   <li>{@code GET|PUT /{id}/variants} — read / atomically replace the option axes + variant set
 *       (slice VG1); each variant is backed by a child product, stocked through the existing
 *       inventory screens
 *   <li>{@code GET /{id}/images}, {@code POST /{id}/images/presign}, {@code POST /{id}/images},
 *       {@code PATCH|DELETE /{id}/images/{imageId}} — attach / edit (alt + order) / remove
 * </ul>
 *
 * VIEWER read · STAFF write/lifecycle · MANAGER delete (system ADMIN bypasses).
 */
public class ProductListingHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(ProductListingHandler.class);

  private final ProductListingService service;
  private final ProductVariantService variantService;
  private final ObjectMapper mapper;

  public ProductListingHandler(
      ProductListingService service, ProductVariantService variantService, ObjectMapper mapper) {
    this.service = service;
    this.variantService = variantService;
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

      // Featured curation (slice C3): /product-listings/featured — a fixed segment, not a {id}.
      if (parts.length == 1 && "featured".equals(parts[0])) {
        switch (method) {
          case "GET" -> doGetFeatured(req, resp, orgId);
          case "PUT" -> doSetFeatured(req, resp, orgId);
          default -> writeError(resp, 405, "Method not allowed");
        }
        return;
      }

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

      if (parts.length == 2) {
        switch (parts[1]) {
          case "publish" -> doLifecycle(method, req, resp, orgId, id, "publish");
          case "unpublish" -> doLifecycle(method, req, resp, orgId, id, "unpublish");
          case "archive" -> doLifecycle(method, req, resp, orgId, id, "archive");
          case "categories" -> {
            if ("GET".equals(method)) doGetCategories(req, resp, orgId, id);
            else if ("PUT".equals(method)) doSetCategories(req, resp, orgId, id);
            else writeError(resp, 405, "Method not allowed");
          }
          case "images" -> {
            if ("GET".equals(method)) doListImages(req, resp, orgId, id);
            else if ("POST".equals(method)) doAttachImage(req, resp, orgId, id);
            else writeError(resp, 405, "Method not allowed");
          }
          case "variants" -> {
            if ("GET".equals(method)) doGetVariants(req, resp, orgId, id);
            else if ("PUT".equals(method)) doSetVariants(req, resp, orgId, id);
            else writeError(resp, 405, "Method not allowed");
          }
          default -> throw new ValidationException("Unknown route: /product-listings/" + tail);
        }
        return;
      }

      if (parts.length == 3 && "images".equals(parts[1])) {
        if ("presign".equals(parts[2])) {
          if ("POST".equals(method)) doPresignImage(req, resp, orgId, id);
          else writeError(resp, 405, "Method not allowed");
        } else {
          UUID imageId = parseImageId(parts[2]);
          if ("DELETE".equals(method)) doDeleteImage(req, resp, orgId, id, imageId);
          else if ("PATCH".equals(method)) doUpdateImage(req, resp, orgId, id, imageId);
          else writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      throw new ValidationException("Unknown route: /product-listings/" + tail);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/product-listings{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  // --- collection ---

  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    int page = intParam(req, "page", 0);
    int size = intParam(req, "size", 10);
    ListingStatus status = statusParam(req);
    List<ListingView> listings = service.getAll(orgId, status, page, size);
    long total = service.count(orgId, status);
    List<ProductListingResponse> data =
        listings.stream().map(ProductListingResponse::fromView).toList();
    writeJson(resp, 200, new PageResponse<>(data, total, page, size));
  }

  private void doCreate(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    CreateProductListingRequest body = readBody(req, CreateProductListingRequest.class);
    ProductListing created =
        service.create(
            orgId,
            body.getProductId(),
            body.getSlug(),
            body.getSalesPrice(),
            new ProductListingService.TranslatedContentInput(
                toDomainTranslations(body.getTranslations()),
                body.getTitle(),
                body.getMarketingCopy()));
    // Re-read as the full view so the response embeds every language.
    writeJson(resp, 201, ProductListingResponse.fromView(service.getById(orgId, created.getId())));
  }

  // --- featured curation (slice C3) ---

  private void doGetFeatured(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    List<ProductListingResponse> data =
        service.getFeatured(orgId).stream().map(ProductListingResponse::fromView).toList();
    writeJson(resp, 200, data);
  }

  private void doSetFeatured(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    SetFeaturedListingsRequest body = readBody(req, SetFeaturedListingsRequest.class);
    List<ProductListingResponse> data =
        service.setFeatured(orgId, body.getListingIds()).stream()
            .map(ProductListingResponse::fromView)
            .toList();
    writeJson(resp, 200, data);
  }

  // --- single ---

  private void doGetOne(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    ListingView view = service.getById(orgId, id);
    writeJson(resp, 200, ProductListingResponse.fromView(view));
  }

  private void doUpdate(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    UpdateProductListingRequest body = readBody(req, UpdateProductListingRequest.class);
    service.update(
        orgId,
        id,
        body.getSlug(),
        body.getSalesPrice(),
        new ProductListingService.TranslatedContentInput(
            toDomainTranslations(body.getTranslations()),
            body.getTitle(),
            body.getMarketingCopy()));
    writeJson(resp, 200, ProductListingResponse.fromView(service.getById(orgId, id)));
  }

  private static java.util.List<com.loai.inventory.domain.model.ProductListingTranslation>
      toDomainTranslations(java.util.List<ProductListingTranslationDto> dtos) {
    return dtos == null ? null : dtos.stream().map(ProductListingTranslationDto::toDomain).toList();
  }

  private void doDelete(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    service.delete(orgId, id);
    resp.setStatus(204);
  }

  // --- lifecycle ---

  private void doLifecycle(
      String method,
      HttpServletRequest req,
      HttpServletResponse resp,
      UUID orgId,
      UUID id,
      String action)
      throws IOException {
    if (!"POST".equals(method)) {
      writeError(resp, 405, "Method not allowed");
      return;
    }
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    ProductListing updated =
        switch (action) {
          case "publish" -> service.publish(orgId, id);
          case "unpublish" -> service.unpublish(orgId, id);
          case "archive" -> service.archive(orgId, id);
          default -> throw new ValidationException("Unknown lifecycle action: " + action);
        };
    writeJson(resp, 200, ProductListingResponse.from(updated));
  }

  // --- categories ---

  private void doGetCategories(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id) throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    ListingView view = service.getById(orgId, id);
    writeJson(resp, 200, view.categoryIds());
  }

  private void doSetCategories(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id) throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    SetCategoriesRequest body = readBody(req, SetCategoriesRequest.class);
    Set<UUID> ids =
        body.getCategoryIds() == null ? Set.of() : new LinkedHashSet<>(body.getCategoryIds());
    List<UUID> result = service.setCategories(orgId, id, ids);
    writeJson(resp, 200, result);
  }

  // --- variants (slice VG1) ---

  private void doGetVariants(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, VariantSetResponse.from(variantService.getVariants(orgId, id)));
  }

  private void doSetVariants(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    VariantSetRequest body = readBody(req, VariantSetRequest.class);
    writeJson(
        resp,
        200,
        VariantSetResponse.from(
            variantService.replaceVariants(
                orgId, id, body.toAttributeInputs(), body.toVariantInputs())));
  }

  // --- images ---

  private void doListImages(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    List<ImageView> images = service.listImages(orgId, id);
    writeJson(resp, 200, images.stream().map(ProductListingImageResponse::from).toList());
  }

  private void doPresignImage(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    PresignImageUploadRequest body = readBody(req, PresignImageUploadRequest.class);
    PresignResult result =
        service.presignImageUpload(orgId, id, body.getFilename(), body.getContentType());
    writeJson(resp, 200, PresignImageUploadResponse.from(result));
  }

  private void doAttachImage(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    AttachImageRequest body = readBody(req, AttachImageRequest.class);
    ImageView image =
        service.attachImage(orgId, id, body.getObjectKey(), body.getAltText(), body.getSortOrder());
    writeJson(resp, 201, ProductListingImageResponse.from(image));
  }

  private void doUpdateImage(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id, UUID imageId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    UpdateImageRequest body = readBody(req, UpdateImageRequest.class);
    ImageView image =
        service.updateImage(orgId, id, imageId, body.getAltText(), body.getSortOrder());
    writeJson(resp, 200, ProductListingImageResponse.from(image));
  }

  private void doDeleteImage(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id, UUID imageId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    service.removeImage(orgId, id, imageId);
    resp.setStatus(204);
  }

  // --- helpers ---

  private static String normalize(String remainingPath) {
    if (remainingPath == null) {
      return "";
    }
    String t = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid product listing id format: " + raw);
    }
  }

  private static UUID parseImageId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid image id format: " + raw);
    }
  }

  private ListingStatus statusParam(HttpServletRequest req) {
    String value = req.getParameter("status");
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return ListingStatus.valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(
          "status must be one of DRAFT, PUBLISHED, ARCHIVED (was '" + value + "')");
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

  private int intParam(HttpServletRequest req, String name, int defaultValue) {
    String value = req.getParameter(name);
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }
}
