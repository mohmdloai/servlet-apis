package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.GoodsReceiptRow;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.SupplierRequest;
import com.loai.inventory.api.dto.SupplierResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.Supplier;
import com.loai.inventory.service.SupplierService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /api/orgs/{orgId}/suppliers} — the supplier directory ({@code
 * stories/supplier_goods_receipt.md}).
 *
 * <pre>
 * GET    /?q=&active=&page=&size=   the directory, name ASC          VIEWER
 * POST   /                          create                           STAFF
 * GET    /{id}                      one supplier                     VIEWER
 * PUT    /{id}                      merge update                     STAFF
 * DELETE /{id}                      204, or 409 when it has receipts MANAGER
 * GET    /{id}/receipts?page=&size= its deliveries, newest first     MANAGER
 * </pre>
 *
 * <p>The directory keeps the {@code /customers} gates because it carries no money; the receipts
 * subresource is MANAGER because it carries costs.
 */
public class SupplierHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(SupplierHandler.class);

  private final SupplierService supplierService;
  private final ObjectMapper mapper;

  public SupplierHandler(SupplierService supplierService, ObjectMapper mapper) {
    this.supplierService = supplierService;
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
      String rest = remainingPath == null ? "" : remainingPath;
      if (rest.startsWith("/")) rest = rest.substring(1);
      String[] segments = rest.isEmpty() ? new String[0] : rest.split("/", -1);
      if (segments.length > 2) {
        writeError(resp, 404, "Unknown supplier endpoint");
        return;
      }
      if (segments.length == 2) {
        if (!"receipts".equals(segments[1])) {
          writeError(resp, 404, "Unknown supplier endpoint");
          return;
        }
        doGetReceipts(method, req, resp, orgId, parseUuid(segments[0]));
        return;
      }

      UUID id = segments.length == 1 ? parseUuid(segments[0]) : null;
      switch (method) {
        case "GET" -> doGet(req, resp, orgId, id);
        case "POST" -> doPost(req, resp, orgId, id);
        case "PUT" -> doPut(req, resp, orgId, id);
        case "DELETE" -> doDelete(req, resp, orgId, id);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/suppliers{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    if (id != null) {
      writeJson(resp, 200, SupplierResponse.from(supplierService.getById(orgId, id)));
      return;
    }
    int page = intParam(req, "page", 0);
    int size = intParam(req, "size", SupplierService.DEFAULT_PAGE_SIZE);
    SupplierService.SupplierPage result =
        supplierService.list(orgId, req.getParameter("q"), activeParam(req), page, size);
    List<SupplierResponse> data = result.suppliers().stream().map(SupplierResponse::from).toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    if (id != null) {
      throw new ValidationException("POST does not accept a supplier id in the path");
    }
    Supplier created = supplierService.create(orgId, toEdit(readBody(req)));
    writeJson(resp, 201, SupplierResponse.from(created));
  }

  private void doPut(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    if (id == null) {
      throw new ValidationException("Supplier id is required for update");
    }
    Supplier updated = supplierService.update(orgId, id, toEdit(readBody(req)));
    writeJson(resp, 200, SupplierResponse.from(updated));
  }

  private void doDelete(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    if (id == null) {
      throw new ValidationException("Supplier id is required for delete");
    }
    supplierService.delete(orgId, id);
    resp.setStatus(204);
  }

  /** {@code GET /suppliers/{id}/receipts} — MANAGER: it carries costs, unlike its parent. */
  private void doGetReceipts(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID supplierId)
      throws IOException {
    if (!"GET".equals(method)) {
      writeError(resp, 405, "Method not allowed");
      return;
    }
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    int page = intParam(req, "page", 0);
    int size = intParam(req, "size", SupplierService.DEFAULT_PAGE_SIZE);
    SupplierService.SupplierReceipts result =
        supplierService.receipts(orgId, supplierId, page, size);
    List<GoodsReceiptRow> data =
        result.receipts().stream()
            .map(g -> GoodsReceiptRow.from(g, null, result.lineCounts().getOrDefault(g.getId(), 0)))
            .toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  private SupplierService.SupplierEdit toEdit(SupplierRequest body) {
    return new SupplierService.SupplierEdit(
        body.getName(),
        body.getPhone(),
        body.getEmail(),
        body.getAddress(),
        body.getNotes(),
        body.getActive());
  }

  /** {@code true|false} narrows; absent is both; anything else is a 400, never a silent default. */
  private static Boolean activeParam(HttpServletRequest req) {
    String raw = req.getParameter("active");
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String v = raw.trim();
    if ("true".equalsIgnoreCase(v)) return Boolean.TRUE;
    if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
    throw new ValidationException("'active' must be true or false");
  }

  private UUID parseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid supplier id: " + raw);
    }
  }

  private SupplierRequest readBody(HttpServletRequest req) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), SupplierRequest.class);
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
    ApiErrors.applyHeaders(resp, e);
    writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
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
