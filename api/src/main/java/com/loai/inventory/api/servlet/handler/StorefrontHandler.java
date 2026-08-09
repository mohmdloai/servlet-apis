package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatcher for the merchant storefront-admin namespace {@code /api/orgs/{orgId}/storefront/*}
 * (customization epic). It owns no routes of its own — it peeks the first path segment after {@code
 * storefront/} and delegates the <b>whole</b> remaining path to the matching sub-resource handler
 * (each re-parses and re-checks its own head segment). Registered under {@code storefront} in
 * {@link com.loai.inventory.api.servlet.OrgServlet}; adding a storefront sub-resource is one map
 * entry here.
 *
 * <ul>
 *   <li>{@code banners} → {@link BannerHandler} (slice C1)
 *   <li>{@code pages} → {@link StorefrontPageHandler} (slice C4)
 * </ul>
 */
public class StorefrontHandler implements OrgResourceHandler {

  private final Map<String, OrgResourceHandler> handlers;
  private final ObjectMapper mapper;

  public StorefrontHandler(Map<String, OrgResourceHandler> handlers, ObjectMapper mapper) {
    this.handlers = handlers;
    this.mapper = mapper;
  }

  @Override
  public void handle(
      String method, HttpServletRequest req, HttpServletResponse resp, UUID orgId, String remaining)
      throws IOException {
    try {
      String tail = remaining == null ? "" : remaining;
      String trimmed = tail.startsWith("/") ? tail.substring(1) : tail;
      int slash = trimmed.indexOf('/');
      String head = slash < 0 ? trimmed : trimmed.substring(0, slash);
      if (head.isEmpty()) {
        throw new ValidationException("Expected /api/orgs/{orgId}/storefront/{resource}");
      }
      OrgResourceHandler handler = handlers.get(head);
      if (handler == null) {
        throw new ValidationException("Unknown storefront resource: " + head);
      }
      handler.handle(method, req, resp, orgId, remaining);
    } catch (AppException e) {
      writeError(resp, e);
    }
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    ApiErrors.applyHeaders(resp, e);
    resp.setStatus(e.getStatusCode());
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), ApiErrors.body(e));
  }
}
