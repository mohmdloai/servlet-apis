package com.loai.inventory.api.servlet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.AppBootstrap;
import com.loai.inventory.api.config.AppConfig;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.SalesOrderResponse;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.MagicLinkService.ResolvedOrderView;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.Placed;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Anonymous order-view route at {@code /api/public/orders/{token}} — the customer side of the
 * notifications email channel (notifications-plan §7). Mounted outside the JWT filter (the {@code
 * /api/public/} bypass); the unguessable {@code VIEW_ORDER} magic token <em>is</em> the
 * authorization, scoped to exactly one order.
 *
 * <p>Every failure (unknown/expired token, or an order that vanished) answers an opaque {@code 404}
 * — never distinguishing them, so the endpoint is not an oracle for valid tokens.
 */
public class PublicOrderServlet extends HttpServlet {

  private MagicLinkService magicLinkService;
  private SalesOrderService salesOrderService;
  private ObjectMapper mapper;

  @Override
  public void init() {
    AppConfig config = (AppConfig) getServletContext().getAttribute(AppBootstrap.CONFIG_KEY);
    this.magicLinkService = config.magicLinkService;
    this.salesOrderService = config.salesOrderService;
    this.mapper = config.objectMapper;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      if (!"GET".equals(req.getMethod())) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      String token = extractToken(req.getPathInfo());
      if (token == null) {
        writeError(resp, 404, "Not found");
        return;
      }
      OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      Optional<ResolvedOrderView> resolved = magicLinkService.resolveOrderView(token, now);
      if (resolved.isEmpty()) {
        writeError(resp, 404, "Not found");
        return;
      }
      ResolvedOrderView view = resolved.get();
      Optional<Placed> placed = salesOrderService.findPlaced(view.orgId(), view.orderId());
      if (placed.isEmpty()) {
        writeError(resp, 404, "Not found");
        return;
      }
      Placed p = placed.get();
      // Customer view: withhold staff-facing fields (notes) from this anonymous route.
      writeJson(resp, 200, SalesOrderResponse.forCustomerView(p.order(), p.lines()));
    } catch (Exception e) {
      writeError(resp, 500, "Internal server error");
    }
  }

  /** {@code /{token}} → the token; anything else (missing/nested) → null. */
  private static String extractToken(String pathInfo) {
    if (pathInfo == null || pathInfo.length() < 2) {
      return null;
    }
    String raw = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
    if (raw.isBlank() || raw.contains("/")) {
      return null;
    }
    return raw;
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
