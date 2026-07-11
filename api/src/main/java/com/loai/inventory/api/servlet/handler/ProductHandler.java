package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CreateProductRequest;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.ProductResponse;
import com.loai.inventory.api.dto.UpdateProductRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.service.ProductService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles /api/orgs/{orgId}/products[/{productId}].
 *
 * <p>{@code remainingPath} is the path after {@code /api/orgs/{orgId}/products}, so it is empty
 * (list/create) or "/{productId}" (single resource).
 */
public class ProductHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(ProductHandler.class);

  private final ProductService productService;
  private final ObjectMapper mapper;

  public ProductHandler(ProductService productService, ObjectMapper mapper) {
    this.productService = productService;
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
      UUID productId = parseId(remainingPath);

      switch (method) {
        case "GET" -> doGet(req, resp, orgId, productId);
        case "POST" -> doPost(req, resp, orgId, productId);
        case "PUT" -> doPut(req, resp, orgId, productId);
        case "DELETE" -> doDelete(req, resp, orgId, productId);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/products{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID productId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);

    if (productId == null) {
      // ?barcode= — the scanner's exact-lookup seam (FLOW.md §3 step 1). Mirrors the
      // ?order_number= single-object convention on SalesOrderHandler: a non-blank param resolves to
      // one product (or 404); a bare GET returns the paged list.
      String barcode = req.getParameter("barcode");
      if (barcode != null && !barcode.isBlank()) {
        Product product = productService.getByBarcode(orgId, barcode);
        writeJson(resp, 200, ProductResponse.from(product));
        return;
      }
      // ?q= — optional case-insensitive name/SKU search (the POS "search to add" picker). Absent/
      // blank ⇒ the whole paged list, unchanged. Mirrors the /inventory overview's q convention.
      String q = req.getParameter("q");
      int page = intParam(req, "page", 0);
      int size = intParam(req, "size", 10);
      List<Product> products = productService.getAll(orgId, q, page, size);
      long total = productService.count(orgId, q);
      List<ProductResponse> data = products.stream().map(ProductResponse::from).toList();
      writeJson(resp, 200, new PageResponse<>(data, total, page, size));
    } else {
      Product product = productService.getById(orgId, productId);
      writeJson(resp, 200, ProductResponse.from(product));
    }
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID productId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    if (productId != null) {
      throw new ValidationException("POST does not accept a product id in the path");
    }

    CreateProductRequest body = readBody(req, CreateProductRequest.class);
    Product created =
        productService.create(
            orgId,
            body.getName(),
            body.getDescription(),
            body.getBasePrice(),
            body.getSku(),
            body.getBarcode());
    writeJson(resp, 201, ProductResponse.from(created));
  }

  private void doPut(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID productId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    if (productId == null) {
      throw new ValidationException("Product id is required for update");
    }

    UpdateProductRequest body = readBody(req, UpdateProductRequest.class);
    Product updated =
        productService.update(
            orgId,
            productId,
            body.getName(),
            body.getDescription(),
            body.getBasePrice(),
            body.getSku(),
            body.getBarcode());
    writeJson(resp, 200, ProductResponse.from(updated));
  }

  private void doDelete(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID productId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    if (productId == null) {
      throw new ValidationException("Product id is required for delete");
    }
    productService.delete(orgId, productId);
    resp.setStatus(204);
  }

  private UUID parseId(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || remainingPath.equals("/")) {
      return null;
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid product id format: " + raw);
    }
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
