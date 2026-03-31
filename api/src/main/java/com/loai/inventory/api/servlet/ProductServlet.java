package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CreateProductRequest;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.ProductResponse;
import com.loai.inventory.api.dto.UpdateProductRequest;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.service.ProductService;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles all product endpoints:
 *
 * <p>GET /api/products → list (paginated) GET /api/products/{id} → get by id POST /api/products →
 * create PUT /api/products/{id} → update DELETE /api/products/{id} → delete
 *
 * <p>Routing is done manually on pathInfo — no framework. This class knows nothing about
 * repositories or jOOQ. It only speaks: HTTP in → service call → HTTP out.
 */
@WebServlet("/api/products/*")
public class ProductServlet extends HttpServlet {

  private static final Logger log = LoggerFactory.getLogger(ProductServlet.class);

  private ProductService productService;
  private ObjectMapper objectMapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.productService = config.productService;
    this.objectMapper = config.objectMapper;
  }

  // ── GET ───────────────────────────────────────────────────────

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    try {
      UUID id = extractId(req);

      if (id == null) {
        // GET /api/products?page=0&size=10
        int page = intParam(req, "page", 0);
        int size = intParam(req, "size", 10);

        List<Product> products = productService.getAll(page, size);
        long total = productService.count();

        List<ProductResponse> data =
            products.stream().map(ProductResponse::from).collect(Collectors.toList());

        writeJson(res, 200, new PageResponse<>(data, total, page, size));

      } else {
        // GET /api/products/{id}
        Product product = productService.getById(id);
        writeJson(res, 200, ProductResponse.from(product));
      }

    } catch (AppException e) {
      writeError(res, e);
    } catch (Exception e) {
      log.error("Unexpected error in GET /api/products", e);
      writeError(res, 500, "Internal server error");
    }
  }

  // ── POST ──────────────────────────────────────────────────────

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    try {
      CreateProductRequest body = readBody(req, CreateProductRequest.class);

      Product created =
          productService.create(
              body.getName(), body.getDescription(), body.getBasePrice(), body.getSku());

      writeJson(res, 201, ProductResponse.from(created));

    } catch (AppException e) {
      writeError(res, e);
    } catch (Exception e) {
      log.error("Unexpected error in POST /api/products", e);
      writeError(res, 500, "Internal server error");
    }
  }

  // ── PUT ───────────────────────────────────────────────────────

  @Override
  protected void doPut(HttpServletRequest req, HttpServletResponse res) throws IOException {
    try {
      UUID id = extractId(req);
      if (id == null) {
        writeError(res, 400, "Product id is required for update");
        return;
      }

      UpdateProductRequest body = readBody(req, UpdateProductRequest.class);

      Product updated =
          productService.update(
              id, body.getName(), body.getDescription(), body.getBasePrice(), body.getSku());

      writeJson(res, 200, ProductResponse.from(updated));

    } catch (AppException e) {
      writeError(res, e);
    } catch (Exception e) {
      log.error("Unexpected error in PUT /api/products/{id}", e);
      writeError(res, 500, "Internal server error");
    }
  }

  // ── DELETE ────────────────────────────────────────────────────

  @Override
  protected void doDelete(HttpServletRequest req, HttpServletResponse res) throws IOException {
    try {
      UUID id = extractId(req);
      if (id == null) {
        writeError(res, 400, "Product id is required for delete");
        return;
      }

      productService.delete(id);
      res.setStatus(204); // No Content

    } catch (AppException e) {
      writeError(res, e);
    } catch (Exception e) {
      log.error("Unexpected error in DELETE /api/products/{id}", e);
      writeError(res, 500, "Internal server error");
    }
  }

  // ── Helpers ───────────────────────────────────────────────────

  /**
   * Extracts the UUID from the path: /api/products/{id} Returns null when the path is just
   * /api/products (list endpoint).
   */
  private UUID extractId(HttpServletRequest req) {
    String pathInfo = req.getPathInfo(); // e.g. "/3fa85f64-..." or null
    if (pathInfo == null || pathInfo.equals("/")) {
      return null;
    }
    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new com.loai.inventory.common.exception.ValidationException(
          "Invalid product id format: " + raw);
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    return objectMapper.readValue(req.getInputStream(), type);
  }

  private void writeJson(HttpServletResponse res, int status, Object body) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    objectMapper.writeValue(res.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse res, AppException e) throws IOException {
    writeJson(res, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
  }

  private void writeError(HttpServletResponse res, int status, String message) throws IOException {
    writeJson(res, status, ApiError.of(status, message));
  }

  private int intParam(HttpServletRequest req, String name, int defaultValue) {
    String value = req.getParameter(name);
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new com.loai.inventory.common.exception.ValidationException(
          "Parameter '" + name + "' must be an integer");
    }
  }
}
