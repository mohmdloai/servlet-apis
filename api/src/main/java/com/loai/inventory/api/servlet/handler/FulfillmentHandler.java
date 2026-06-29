package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CreateFulfillmentRequest;
import com.loai.inventory.api.dto.FailFulfillmentRequest;
import com.loai.inventory.api.dto.RefundFulfillmentRequest;
import com.loai.inventory.api.dto.ReplaceFulfillmentRequest;
import com.loai.inventory.api.mapper.FulfillmentMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.DeliveredView;
import com.loai.inventory.service.FulfillmentService.FailedRefundResult;
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
 *   <li>{@code POST /fulfillments/{id}/deliver} — mark it DELIVERED: issue the SalesInvoice and
 *       auto-allocate prepayment (200)
 *   <li>{@code POST /fulfillments/{id}/fail} — mark a SHIPPED fulfillment FAILED (200); body {@code
 *       {reason?}}
 *   <li>{@code POST /fulfillments/{id}/refund} — direct-refund a FAILED fulfillment's prepayment
 *       (200); body {@code {refund_method?}}
 *   <li>{@code POST /fulfillments/{id}/return} — record a FAILED fulfillment's goods back in stock
 *       (200)
 *   <li>{@code POST /fulfillments/{id}/replace} — re-ship a FAILED fulfillment as a new PENDING
 *       fulfillment (201)
 * </ul>
 *
 * <p>Create/ship/deliver/fail require STAFF; the money-moving {@code refund} and the stock-moving
 * {@code return} / {@code replace} require MANAGER (system ADMIN bypasses).
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
      if (parts.length == 2) {
        UUID id = parseId(parts[0]);
        switch (parts[1]) {
          case "ship" -> doShip(req, resp, orgId, id);
          case "deliver" -> doDeliver(req, resp, orgId, id);
          case "fail" -> doFail(req, resp, orgId, id);
          case "refund" -> doRefund(req, resp, orgId, id);
          case "return" -> doReturn(req, resp, orgId, id);
          case "replace" -> doReplace(req, resp, orgId, id);
          default -> throw new ValidationException("Unknown route: POST /fulfillments/" + tail);
        }
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

  private void doDeliver(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);

    DeliveredView view = service.markDelivered(orgId, id, sc.toActorContext());

    writeJson(resp, 200, FulfillmentMapper.toDeliverResponse(view));
  }

  /** {@code POST /{id}/fail} — SHIPPED → FAILED. STAFF+; no money or stock moves. */
  private void doFail(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);
    FailFulfillmentRequest body = readBodyOrNull(req, FailFulfillmentRequest.class);
    String reason = body == null ? null : body.getReason();

    FulfillmentView view = service.markFailed(orgId, id, reason);

    writeJson(resp, 200, FulfillmentMapper.toResponse(view));
  }

  /**
   * {@code POST /{id}/refund} — direct-refund a FAILED fulfillment's prepayment. MANAGER+ (money
   * may move); an above-threshold refund escalates to OWNER. System ADMIN bypasses.
   */
  private void doRefund(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    RefundFulfillmentRequest body = readBodyOrNull(req, RefundFulfillmentRequest.class);
    PaymentProvider method =
        body == null ? null : FulfillmentMapper.toRefundMethod(body.getRefundMethod());

    FailedRefundResult result =
        service.refundFailed(orgId, id, method, sc.actorId(), isOwnerOrAdmin(sc, orgId));

    writeJson(resp, 200, FulfillmentMapper.toRefundResponse(result));
  }

  /**
   * {@code POST /{id}/replace} — re-ship a FAILED fulfillment as a new PENDING fulfillment, funded
   * by the surviving prepayment. MANAGER+. Returns 201 with the replacement.
   */
  private void doReplace(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    ReplaceFulfillmentRequest body = readBodyOrNull(req, ReplaceFulfillmentRequest.class);
    FulfillmentView view =
        service.replaceFailed(
            orgId,
            id,
            body == null ? null : body.getCarrier(),
            body == null ? null : body.getTrackingNumber(),
            body == null ? null : body.getNotes(),
            sc.toActorContext());
    writeJson(resp, 201, FulfillmentMapper.toResponse(view));
  }

  /** {@code POST /{id}/return} — record a FAILED fulfillment's goods back in stock. MANAGER+. */
  private void doReturn(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);

    FulfillmentView view = service.recordReturn(orgId, id, sc.toActorContext());

    writeJson(resp, 200, FulfillmentMapper.toResponse(view));
  }

  /** OWNER in the org (or system ADMIN) — gates an above-threshold failed-fulfillment refund. */
  private static boolean isOwnerOrAdmin(SecurityContext sc, UUID orgId) {
    if (sc.isSystemAdmin()) {
      return true;
    }
    var roles = sc.orgRoles() == null ? null : sc.orgRoles().get(orgId);
    return roles != null && roles.contains(OrgRole.OWNER);
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

  /** Read a required JSON body. A malformed body is a client error → 400, not a 500. */
  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    byte[] bytes = req.getInputStream() == null ? new byte[0] : req.getInputStream().readAllBytes();
    try {
      return mapper.readValue(bytes, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("malformed JSON body");
    }
  }

  /**
   * Like {@link #readBody} but tolerates an absent/blank body, returning {@code null}. A
   * present-but-unparseable body is a client error → {@link ValidationException} (400), not a 500.
   */
  private <T> T readBodyOrNull(HttpServletRequest req, Class<T> type) throws IOException {
    byte[] bytes = req.getInputStream() == null ? new byte[0] : req.getInputStream().readAllBytes();
    if (bytes.length == 0 || new String(bytes, java.nio.charset.StandardCharsets.UTF_8).isBlank()) {
      return null;
    }
    try {
      return mapper.readValue(bytes, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("malformed JSON body");
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    // A short-stock 409 (e.g. re-reservation on /replace) carries a structured per-product
    // shortage list — surface it like SalesOrderHandler does, not just the summary message.
    if (e instanceof InsufficientStockException ise) {
      var shortages =
          ise.getShortages().stream()
              .map(s -> new ApiError.Shortage(s.productId(), s.requested(), s.available()))
              .toList();
      writeJson(
          resp,
          e.getStatusCode(),
          ApiError.ofShortages(e.getStatusCode(), e.getMessage(), shortages));
      return;
    }
    writeJson(resp, e.getStatusCode(), ApiError.of(e.getStatusCode(), e.getMessage()));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
