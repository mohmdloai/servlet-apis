package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.servlet.handler.CategoryHandler;
import com.loai.inventory.api.servlet.handler.CreditNoteHandler;
import com.loai.inventory.api.servlet.handler.CustomerHandler;
import com.loai.inventory.api.servlet.handler.FulfillmentHandler;
import com.loai.inventory.api.servlet.handler.ImpersonationHandler;
import com.loai.inventory.api.servlet.handler.InventoryHandler;
import com.loai.inventory.api.servlet.handler.InvoiceHandler;
import com.loai.inventory.api.servlet.handler.OrgHandler;
import com.loai.inventory.api.servlet.handler.OrgResourceHandler;
import com.loai.inventory.api.servlet.handler.PaymentHandler;
import com.loai.inventory.api.servlet.handler.PaymentTransactionHandler;
import com.loai.inventory.api.servlet.handler.ProductHandler;
import com.loai.inventory.api.servlet.handler.ProductListingHandler;
import com.loai.inventory.api.servlet.handler.RefundHandler;
import com.loai.inventory.api.servlet.handler.SalesOrderHandler;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
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
 *
 * <p>To add a new org-scoped resource, implement {@link OrgResourceHandler} and register it in
 * {@link #init()}. The dispatcher does not need to change.
 */
public class OrgServlet extends HttpServlet {

  private OrgHandler orgHandler;
  private Map<String, OrgResourceHandler> subResources;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.mapper = config.objectMapper;
    this.orgHandler = new OrgHandler(config.orgService, mapper);
    this.subResources =
        Map.ofEntries(
            Map.entry("products", new ProductHandler(config.productService, mapper)),
            Map.entry("categories", new CategoryHandler(config.categoryService, mapper)),
            Map.entry(
                "product-listings",
                new ProductListingHandler(config.productListingService, mapper)),
            Map.entry("customers", new CustomerHandler(config.customerService, mapper)),
            Map.entry("inventory", new InventoryHandler(config.inventoryService, mapper)),
            Map.entry(
                "sales-orders",
                new SalesOrderHandler(
                    config.salesOrderService, config.orderCancellationService, mapper)),
            Map.entry(
                "payment-transactions",
                new PaymentTransactionHandler(config.paymentTransactionService, mapper)),
            Map.entry("payments", new PaymentHandler(config.paymentDisputeService, mapper)),
            Map.entry("fulfillments", new FulfillmentHandler(config.fulfillmentService, mapper)),
            Map.entry("credit-notes", new CreditNoteHandler(config.creditNoteService, mapper)),
            Map.entry("refunds", new RefundHandler(config.refundService, mapper)),
            Map.entry("invoices", new InvoiceHandler(config.invoiceAdminService, mapper)),
            Map.entry(
                "impersonate",
                new ImpersonationHandler(config.authService, mapper, config.secureCookies)));
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      Route route = parseRoute(req.getPathInfo());
      String method = req.getMethod();

      if (route.subResource() == null) {
        orgHandler.handle(method, req, resp, route.orgId());
        return;
      }

      OrgResourceHandler handler = subResources.get(route.subResource());
      if (handler == null) {
        throw new ValidationException("Unknown resource: " + route.subResource());
      }
      handler.handle(method, req, resp, route.orgId(), route.remaining());
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  /**
   * @param orgId null for collection-level requests ({@code GET /api/orgs}, {@code POST /api/orgs})
   * @param subResource null for org-itself routes; otherwise the segment after {@code /{orgId}/}
   * @param remaining path tail after the sub-resource segment, with leading slash or empty
   */
  private record Route(UUID orgId, String subResource, String remaining) {}

  private Route parseRoute(String pathInfo) {
    if (pathInfo == null || pathInfo.isEmpty() || pathInfo.equals("/")) {
      return new Route(null, null, "");
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
      return new Route(orgId, null, "");
    }

    String remaining = segments.length > 2 ? "/" + segments[2] : "";
    return new Route(orgId, segments[1], remaining);
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
