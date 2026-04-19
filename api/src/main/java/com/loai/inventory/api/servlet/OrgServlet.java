package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.servlet.handler.CustomerHandler;
import com.loai.inventory.api.servlet.handler.InventoryHandler;
import com.loai.inventory.api.servlet.handler.OrgHandler;
import com.loai.inventory.api.servlet.handler.ProductHandler;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Single dispatcher servlet for all org-scoped resources at {@code /api/orgs/*}.
 *
 * <p>Tomcat servlet path mappings cannot include path variables, so one router parses {@code
 * pathInfo} and delegates to the right handler. Routes:
 *
 * <ul>
 *   <li>{@code /} → list orgs
 *   <li>{@code /{orgId}} → get/put/delete org
 *   <li>{@code /{orgId}/products[/{productId}]}
 *   <li>{@code /{orgId}/customers[/{customerId}]}
 *   <li>{@code /{orgId}/inventory[/{productId}[/{action}]]}
 * </ul>
 */
public class OrgServlet extends HttpServlet {

  private OrgHandler orgHandler;
  private ProductHandler productHandler;
  private CustomerHandler customerHandler;
  private InventoryHandler inventoryHandler;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.mapper = config.objectMapper;
    this.orgHandler = new OrgHandler(config.orgService, mapper);
    this.productHandler = new ProductHandler(config.productService, mapper);
    this.customerHandler = new CustomerHandler(config.customerService, mapper);
    this.inventoryHandler = new InventoryHandler(config.inventoryService, mapper);
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      String pathInfo = req.getPathInfo();
      Route route = parseRoute(pathInfo);
      String method = req.getMethod();

      switch (route.kind) {
        case ORG -> orgHandler.handle(method, req, resp, route.orgId);
        case PRODUCTS -> productHandler.handle(method, req, resp, route.orgId, route.remaining);
        case CUSTOMERS -> customerHandler.handle(method, req, resp, route.orgId, route.remaining);
        case INVENTORY -> inventoryHandler.handle(method, req, resp, route.orgId, route.remaining);
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  private enum Kind {
    ORG,
    PRODUCTS,
    CUSTOMERS,
    INVENTORY
  }

  private static final class Route {
    final Kind kind;
    final UUID orgId;
    final String remaining;

    Route(Kind kind, UUID orgId, String remaining) {
      this.kind = kind;
      this.orgId = orgId;
      this.remaining = remaining;
    }
  }

  private Route parseRoute(String pathInfo) {
    if (pathInfo == null || pathInfo.isEmpty() || pathInfo.equals("/")) {
      return new Route(Kind.ORG, null, "");
    }

    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    String[] segments = raw.split("/", 3);

    UUID orgId;
    try {
      orgId = UUID.fromString(segments[0]);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid org id format: " + segments[0]);
    }

    if (segments.length < 2 || segments[1].isEmpty()) {
      return new Route(Kind.ORG, orgId, "");
    }

    String resource = segments[1];
    String remaining = segments.length > 2 ? "/" + segments[2] : "";

    return switch (resource) {
      case "products" -> new Route(Kind.PRODUCTS, orgId, remaining);
      case "customers" -> new Route(Kind.CUSTOMERS, orgId, remaining);
      case "inventory" -> new Route(Kind.INVENTORY, orgId, remaining);
      default -> throw new ValidationException("Unknown resource: " + resource);
    };
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    resp.setStatus(e.getStatusCode());
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), ApiError.of(e.getStatusCode(), e.getMessage()));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), ApiError.of(status, message));
  }
}
