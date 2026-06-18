package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CreateFulfillmentRequest;
import com.loai.inventory.api.mapper.FulfillmentMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.FulfillmentView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles fulfillment routes under {@code /api/orgs/{orgId}/fulfillments}:
 *
 * <ul>
 *   <li>{@code POST /fulfillments} — create a PENDING fulfillment from a paid order's lines (201)
 *   <li>{@code POST /fulfillments/{id}/ship} — mark it SHIPPED, decrementing stock (200)
 * </ul>
 *
 * <p>Both require STAFF in the org (system ADMIN bypasses); shipping is the most consequential
 * action in the outbound flow.
 */
public class FulfillmentHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentHandler.class);

  private final FulfillmentService service;
  private final ObjectMapper mapper;

  public FulfillmentHandler(FulfillmentService service, ObjectMapper mapper) {
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

      String tail = normalize(remainingPath);
      if (tail.isEmpty()) {
        doCreate(req, resp, orgId);
        return;
      }

      String[] parts = tail.split("/");
      if (parts.length == 2 && "ship".equals(parts[1])) {
        doShip(req, resp, orgId, parseId(parts[0]));
        return;
      }
      throw new ValidationException("Unknown route: POST /fulfillments/" + tail);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/fulfillments{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doCreate(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);

    CreateFulfillmentRequest body = readBody(req, CreateFulfillmentRequest.class);
    if (body == null || body.getSalesOrderId() == null) {
      throw new ValidationException("sales_order_id is required");
    }

    FulfillmentView view =
        service.create(
            orgId,
            body.getSalesOrderId(),
            FulfillmentMapper.toLineInputs(body),
            body.getCarrier(),
            body.getTrackingNumber(),
            body.getNotes(),
            sc.toActorContext());

    writeJson(resp, 201, FulfillmentMapper.toResponse(view));
  }

  private void doShip(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);

    FulfillmentView view = service.ship(orgId, id, sc.toActorContext());

    writeJson(resp, 200, FulfillmentMapper.toResponse(view));
  }

  private static String normalize(String remainingPath) {
    if (remainingPath == null) {
      return "";
    }
    String t = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid fulfillment id format: " + raw);
    }
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
