package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.InitInventoryRequest;
import com.loai.inventory.api.dto.InventoryQtyRequest;
import com.loai.inventory.api.dto.InventoryResponse;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.service.InventoryService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Handles inventory endpoints:
 *
 * <p>GET /api/inventory/{productId} — get inventory for a product POST /api/inventory — initialise
 * inventory POST /api/inventory/{productId}/reserve — reserve stock POST
 * /api/inventory/{productId}/release — release reservation POST
 * /api/inventory/{productId}/confirm-sale — confirm sale POST /api/inventory/{productId}/restock —
 * restock POST /api/inventory/{productId}/adjust — manual adjustment DELETE
 * /api/inventory/{productId} — delete inventory
 */
@WebServlet("/api/inventory/*")
public class InventoryServlet extends HttpServlet {

  private InventoryService inventoryService;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.inventoryService = config.inventoryService;
    this.mapper = config.objectMapper;
  }

  // ── GET

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      PathInfo path = parsePath(req);
      if (path.productId == null) {
        throw new ValidationException("GET requires /api/inventory/{productId}");
      }
      if (path.action != null) {
        throw new ValidationException("GET does not support actions");
      }

      Inventory inventory = inventoryService.getByProductId(path.productId);
      writeJson(resp, 200, InventoryResponse.from(inventory));

    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  // ── POST ──────────────────────────────────────────────────────

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      PathInfo path = parsePath(req);

      if (path.productId == null) {
        // POST /api/inventory — initialise
        InitInventoryRequest body = readBody(req, InitInventoryRequest.class);
        Inventory created = inventoryService.initialise(body.getProductId(), body.getStockQty());
        writeJson(resp, 201, InventoryResponse.from(created));
        return;
      }

      if (path.action == null) {
        throw new ValidationException(
            "POST /api/inventory/{productId} requires an action: "
                + "reserve, release, confirm-sale, restock, adjust");
      }

      InventoryQtyRequest body = readBody(req, InventoryQtyRequest.class);
      Inventory result =
          switch (path.action) {
            case "reserve" -> inventoryService.reserve(path.productId, body.getQty());
            case "release" -> inventoryService.release(path.productId, body.getQty());
            case "confirm-sale" -> inventoryService.confirmSale(path.productId, body.getQty());
            case "restock" -> inventoryService.restock(path.productId, body.getQty());
            case "adjust" -> inventoryService.adjust(path.productId, body.getQty());
            default -> throw new ValidationException("Unknown action: " + path.action);
          };

      writeJson(resp, 200, InventoryResponse.from(result));

    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  // ── DELETE ────────────────────────────────────────────────────

  @Override
  protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      PathInfo path = parsePath(req);
      if (path.productId == null) {
        throw new ValidationException("DELETE requires /api/inventory/{productId}");
      }

      inventoryService.delete(path.productId);
      resp.setStatus(204);

    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  // ── Path parsing ──────────────────────────────────────────────

  /**
   * Parses pathInfo into productId and optional action.
   *
   * <p>Examples: null or "/" → productId=null, action=null "/{uuid}" → productId=uuid, action=null
   * "/{uuid}/reserve" → productId=uuid, action="reserve"
   */
  private PathInfo parsePath(HttpServletRequest req) {
    String pathInfo = req.getPathInfo();
    if (pathInfo == null || pathInfo.equals("/")) {
      return new PathInfo(null, null);
    }

    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    String[] segments = raw.split("/", 2);

    UUID productId;
    try {
      productId = UUID.fromString(segments[0]);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid product id format: " + segments[0]);
    }

    String action = segments.length > 1 ? segments[1] : null;
    return new PathInfo(productId, action);
  }

  private record PathInfo(UUID productId, String action) {}

  // ── Helpers

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
