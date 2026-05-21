package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PlaceOnlineOrderRequest;
import com.loai.inventory.api.dto.SalesOrderResponse;
import com.loai.inventory.api.mapper.SalesOrderMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.Placed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code POST /api/orgs/{orgId}/sales-orders}.
 *
 * <p>v1 scope: place an online order. Other methods (GET/PUT/DELETE) and the {@code
 * /sales-orders/{id}} sub-paths are not implemented yet — they land in later slices (TX-2…TX-5,
 * cancellation, queries).
 */
public class SalesOrderHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(SalesOrderHandler.class);
  private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final SalesOrderService service;
  private final ObjectMapper mapper;

  public SalesOrderHandler(SalesOrderService service, ObjectMapper mapper) {
    this.service = service;
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
      if (!"POST".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }
      if (remainingPath != null && !remainingPath.isEmpty() && !"/".equals(remainingPath)) {
        throw new ValidationException(
            "Not implemented in this slice: " + method + " /sales-orders" + remainingPath);
      }
      doPost(req, resp, orgId);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/sales-orders{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);

    PlaceOnlineOrderRequest body = readBody(req, PlaceOnlineOrderRequest.class);
    String idempotencyKey = req.getHeader(IDEMPOTENCY_HEADER);
    // Online orders require the header — the storefront must protect against double-submit, and
    // the domain SalesOrder enforces the same invariant on creation.
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ValidationException(IDEMPOTENCY_HEADER + " header is required");
    }

    Placed placed =
        service.placeOnlineOrder(
            orgId,
            SalesOrderMapper.toCustomerInput(body),
            SalesOrderMapper.toLineInputs(body),
            idempotencyKey,
            body.getNotes());

    SalesOrderResponse out = SalesOrderMapper.toResponse(placed);
    writeJson(resp, 201, out);
  }

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
