package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.InitInventoryRequest;
import com.loai.inventory.api.dto.InventoryQtyRequest;
import com.loai.inventory.api.dto.InventoryResponse;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.InventoryService;
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
  private final ObjectMapper mapper;

  public InventoryHandler(InventoryService inventoryService, ObjectMapper mapper) {
    this.inventoryService = inventoryService;
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
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    if (path.productId == null) {
      throw new ValidationException("GET requires /api/orgs/{orgId}/inventory/{productId}");
    }
    if (path.action != null) {
      throw new ValidationException("GET does not support actions");
    }
    Inventory inv = inventoryService.getByProductId(orgId, path.productId);
    writeJson(resp, 200, InventoryResponse.from(inv));
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

    InventoryQtyRequest body = readBody(req, InventoryQtyRequest.class);
    Inventory result =
        switch (path.action) {
          case "reserve" -> inventoryService.reserve(orgId, path.productId, body.getQty(), actor);
          case "release" -> inventoryService.release(orgId, path.productId, body.getQty(), actor);
          case "confirm-sale" ->
              inventoryService.confirmSale(orgId, path.productId, body.getQty(), actor);
          case "restock" -> inventoryService.restock(orgId, path.productId, body.getQty(), actor);
          case "adjust" -> inventoryService.adjust(orgId, path.productId, body.getQty(), actor);
          default -> throw new ValidationException("Unknown action: " + path.action);
        };

    writeJson(resp, 200, InventoryResponse.from(result));
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
    return mapper.readValue(req.getInputStream(), type);
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
