package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CancelOrderRequest;
import com.loai.inventory.api.dto.PlaceSalesOrderRequest;
import com.loai.inventory.api.mapper.SalesOrderMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.OrderCancellationService.CancelResult;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.Placed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code POST /api/orgs/{orgId}/sales-orders}, dispatching by request {@code channel}:
 * {@code IN_STORE} runs the whole sale in one txn ({@code stories/in_store_sale.md}); {@code
 * ONLINE} / {@code PHONE} (the default) place a PENDING_PAYMENT order with reservations ({@code
 * stories/place_online_order.md}). Both require STAFF (system ADMIN bypasses).
 *
 * <p>Other methods (GET/PUT/DELETE) and the {@code /sales-orders/{id}} sub-paths are not
 * implemented yet — later slices.
 */
public class SalesOrderHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(SalesOrderHandler.class);
  private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final SalesOrderService service;
  private final OrderCancellationService cancellationService;
  private final ObjectMapper mapper;

  public SalesOrderHandler(
      SalesOrderService service,
      OrderCancellationService cancellationService,
      ObjectMapper mapper) {
    this.service = service;
    this.cancellationService = cancellationService;
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

      String[] parts = splitPath(remainingPath);
      if (parts.length == 0) {
        doPost(req, resp, orgId);
        return;
      }
      if (parts.length == 2 && "cancel".equals(parts[1])) {
        doCancel(req, resp, orgId, parseId(parts[0]));
        return;
      }
      throw new ValidationException(
          "Not implemented in this slice: " + method + " /sales-orders" + remainingPath);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/sales-orders{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.STAFF);

    PlaceSalesOrderRequest body = readBody(req, PlaceSalesOrderRequest.class);
    OrderChannel channel = body.getChannel() == null ? OrderChannel.ONLINE : body.getChannel();
    ActorContext actor = sc.toActorContext();

    // Both channels require the header: the storefront (online) and the POS (in-store) must each
    // protect against double-submit, and the service enforces the same invariant on the order +
    // payment rows.
    String idempotencyKey = req.getHeader(IDEMPOTENCY_HEADER);
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ValidationException(IDEMPOTENCY_HEADER + " header is required");
    }

    if (channel == OrderChannel.IN_STORE) {
      InStoreSale sale =
          service.placeInStoreSale(
              orgId,
              SalesOrderMapper.toCustomerInput(body),
              SalesOrderMapper.toLineInputs(body),
              SalesOrderMapper.toPaymentInput(body),
              body.getNotes(),
              actor,
              idempotencyKey,
              sc.actorId());
      writeJson(resp, 201, SalesOrderMapper.toInStoreResponse(sale));
      return;
    }

    Placed placed =
        service.placeOnlineOrder(
            orgId,
            SalesOrderMapper.toCustomerInput(body),
            SalesOrderMapper.toLineInputs(body),
            idempotencyKey,
            body.getNotes(),
            actor);

    writeJson(resp, 201, SalesOrderMapper.toResponse(placed));
  }

  /**
   * {@code POST /{id}/cancel} — cancel an order: release reservations and direct-refund any
   * prepayment. MANAGER+ (money may move). System ADMIN bypasses.
   */
  private void doCancel(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID orderId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    CancelOrderRequest body = readBodyOrNull(req, CancelOrderRequest.class);
    String reason = body == null ? null : body.getReason();
    var refundMethod =
        body == null ? null : SalesOrderMapper.toRefundMethod(body.getRefundMethod());

    CancelResult result =
        cancellationService.cancel(
            orgId, orderId, reason, refundMethod, sc.actorId(), isOwnerOrAdmin(sc, orgId));

    writeJson(resp, 200, SalesOrderMapper.toCancelResponse(result));
  }

  /** OWNER in the org (or system ADMIN) — gates an above-threshold cancellation refund. */
  private static boolean isOwnerOrAdmin(SecurityContext sc, UUID orgId) {
    if (sc.isSystemAdmin()) {
      return true;
    }
    var roles = sc.orgRoles() == null ? null : sc.orgRoles().get(orgId);
    return roles != null && roles.contains(OrgRole.OWNER);
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    return mapper.readValue(req.getInputStream(), type);
  }

  /** Like {@link #readBody} but tolerates an empty body, returning {@code null}. */
  private <T> T readBodyOrNull(HttpServletRequest req, Class<T> type) throws IOException {
    if (req.getInputStream() == null) {
      return null;
    }
    byte[] bytes = req.getInputStream().readAllBytes();
    if (bytes.length == 0) {
      return null;
    }
    return mapper.readValue(bytes, type);
  }

  private static String[] splitPath(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || "/".equals(remainingPath)) {
      return new String[0];
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    return raw.split("/");
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid order id format: " + raw);
    }
  }

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
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
