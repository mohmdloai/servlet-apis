package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.InitInventoryRequest;
import com.loai.inventory.api.dto.InventoryAdjustRequest;
import com.loai.inventory.api.dto.InventoryQtyRequest;
import com.loai.inventory.api.dto.InventoryResponse;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.mapper.InventoryMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryStockFilter;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InventoryService.LogPage;
import com.loai.inventory.service.InventoryService.OverviewPage;
import com.loai.inventory.service.ProductListingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles /api/orgs/{orgId}/inventory[/{productId}[/{action}]].
 *
 * <p>Actor for inventory_log is derived from SecurityContext, never from request headers.
 */
public class InventoryHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(InventoryHandler.class);

  private final InventoryService inventoryService;
  private final ProductListingService productListingService;
  private final ObjectMapper mapper;

  public InventoryHandler(
      InventoryService inventoryService,
      ProductListingService productListingService,
      ObjectMapper mapper) {
    this.inventoryService = inventoryService;
    this.productListingService = productListingService;
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
      PathParts path = parsePath(remainingPath);

      switch (method) {
        case "GET" -> doGet(req, resp, orgId, path);
        case "POST" -> doPost(req, resp, orgId, path);
        case "DELETE" -> doDelete(req, resp, orgId, path);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/inventory{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, PathParts path)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);

    // GET /inventory — the stock-overview list (product-driven; untracked products appear).
    if (path.productId == null) {
      doGetOverview(req, resp, orgId, AuthzHelper.hasManagerAuthority(sc, orgId));
      return;
    }

    // GET /inventory/{productId}[/{sub}] — single read, or the log / reservations sub-reads.
    if (path.action == null) {
      Inventory inv = inventoryService.getByProductId(orgId, path.productId);
      writeJson(resp, 200, InventoryResponse.from(inv));
      return;
    }
    switch (path.action) {
      case "log" -> doGetLog(req, resp, orgId, path.productId);
      case "reservations" -> doGetProductReservations(req, resp, orgId, path.productId);
      default -> throw new ValidationException("GET does not support action: " + path.action);
    }
  }

  /** {@code GET /inventory?page=&size=&q=&stock=&low_lte=} — the stock-overview list. */
  private void doGetOverview(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, boolean costVisible)
      throws IOException {
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", InventoryService.DEFAULT_PAGE_SIZE), 1),
            InventoryService.MAX_PAGE_SIZE);
    InventoryStockFilter stock = InventoryMapper.parseStockFilter(req.getParameter("stock"));
    Integer lowLte = lowLteParam(req);
    OverviewPage result =
        inventoryService.listOverview(orgId, req.getParameter("q"), stock, lowLte, page, size);
    // Batch-load each row's storefront listing thumbnail (product → listing → primary image),
    // presigned. One extra query per page; products without a listing image are simply omitted.
    var productIds = result.rows().stream().map(r -> r.productId()).toList();
    var imageUrls = productListingService.primaryImageUrlsByProductId(orgId, productIds);
    writeJson(
        resp,
        200,
        new PageResponse<>(
            InventoryMapper.toOverviewRows(result.rows(), imageUrls, costVisible),
            result.total(),
            page,
            size));
  }

  /** {@code GET /inventory/{productId}/log?page=&size=} — the movement ledger. */
  private void doGetLog(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID productId)
      throws IOException {
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", InventoryService.DEFAULT_PAGE_SIZE), 1),
            InventoryService.MAX_PAGE_SIZE);
    LogPage result = inventoryService.listLog(orgId, productId, page, size);
    writeJson(
        resp,
        200,
        new PageResponse<>(InventoryMapper.toLogRows(result), result.total(), page, size));
  }

  /**
   * {@code GET /inventory/{productId}/reservations?status=} — per-product holds with order context.
   */
  private void doGetProductReservations(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID productId)
      throws IOException {
    var status = InventoryMapper.parseReservationStatus(req.getParameter("status"));
    writeJson(
        resp,
        200,
        InventoryMapper.toProductReservationsResponse(
            inventoryService.listProductReservations(orgId, productId, status)));
  }

  /** Parse {@code low_lte} — absent ⇒ null; non-integer or negative ⇒ 400. */
  private static Integer lowLteParam(HttpServletRequest req) {
    String raw = req.getParameter("low_lte");
    if (raw == null || raw.isBlank()) {
      return null;
    }
    int value;
    try {
      value = Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new ValidationException("low_lte must be an integer");
    }
    if (value < 0) {
      throw new ValidationException("low_lte must be >= 0");
    }
    return value;
  }

  private static int intParam(HttpServletRequest req, String name, int defaultValue) {
    String value = req.getParameter(name);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId, PathParts path)
      throws IOException {
    SecurityContext ctx = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    ActorContext actor = ctx.toActorContext();

    if (path.productId == null) {
      InitInventoryRequest body = readBody(req, InitInventoryRequest.class);
      Inventory created =
          inventoryService.initialise(orgId, body.getProductId(), body.getStockQty(), actor);
      writeJson(resp, 201, InventoryResponse.from(created));
      return;
    }

    if (path.action == null) {
      throw new ValidationException(
          "POST /api/orgs/{orgId}/inventory/{productId} requires an action: "
              + "reserve, release, confirm-sale, restock, adjust");
    }

    // adjust has its own body (stories/stocktake_count.md): the shared qty shape plus an optional
    // reason, parsed here against the pair the service allows so a bad value never reaches it.
    if (path.action.equals("adjust")) {
      InventoryAdjustRequest body = readBody(req, InventoryAdjustRequest.class);
      Inventory result =
          inventoryService.adjust(
              orgId,
              path.productId,
              body.getQty(),
              parseAdjustReason(body.getReason()),
              actor,
              req.getHeader("Idempotency-Key"));
      writeJson(resp, 200, InventoryResponse.from(result));
      return;
    }

    InventoryQtyRequest body = readBody(req, InventoryQtyRequest.class);
    Inventory result =
        switch (path.action) {
          case "reserve" -> inventoryService.reserve(orgId, path.productId, body.getQty(), actor);
          case "release" -> inventoryService.release(orgId, path.productId, body.getQty(), actor);
          case "confirm-sale" ->
              inventoryService.confirmSale(orgId, path.productId, body.getQty(), actor);
          case "restock" ->
              inventoryService.restock(
                  orgId, path.productId, body.getQty(), actor, req.getHeader("Idempotency-Key"));
          default -> throw new ValidationException("Unknown action: " + path.action);
        };

    writeJson(resp, 200, InventoryResponse.from(result));
  }

  /**
   * Absent ⇒ {@code ADJUSTMENT}; otherwise exactly one of {@link InventoryService#ADJUST_REASONS}.
   */
  private static StockReason parseAdjustReason(String raw) {
    if (raw == null || raw.isBlank()) {
      return StockReason.ADJUSTMENT;
    }
    StockReason reason;
    try {
      reason = StockReason.valueOf(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("reason must be one of: ADJUSTMENT, STOCKTAKE");
    }
    if (!InventoryService.ADJUST_REASONS.contains(reason)) {
      throw new ValidationException("reason must be one of: ADJUSTMENT, STOCKTAKE");
    }
    return reason;
  }

  private void doDelete(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, PathParts path)
      throws IOException {
    SecurityContext ctx = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    if (path.productId == null) {
      throw new ValidationException("DELETE requires /api/orgs/{orgId}/inventory/{productId}");
    }
    inventoryService.delete(orgId, path.productId, ctx.toActorContext());
    resp.setStatus(204);
  }

  private PathParts parsePath(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || remainingPath.equals("/")) {
      return new PathParts(null, null);
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    String[] segments = raw.split("/", 2);
    UUID productId;
    try {
      productId = UUID.fromString(segments[0]);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid product id format: " + segments[0]);
    }
    String action = segments.length > 1 ? segments[1] : null;
    return new PathParts(productId, action);
  }

  private record PathParts(UUID productId, String action) {}

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
