package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.OrphanRefundResponse;
import com.loai.inventory.api.dto.PaymentTransactionResponse;
import com.loai.inventory.api.dto.RefundOrphanRequest;
import com.loai.inventory.api.dto.ResolveOrphanRequest;
import com.loai.inventory.api.dto.VerifyPaymentTransactionRequest;
import com.loai.inventory.api.mapper.PaymentTransactionMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.OrphanRefundResult;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin-facing payment-transaction routes (all require MANAGER in the org; system ADMIN bypasses):
 *
 * <ul>
 *   <li>{@code POST /api/orgs/{orgId}/payment-transactions} — record-and-verify a manual InstaPay
 *       claim. {@code 201 Created} when this call processed it, {@code 200 OK} on an idempotent
 *       replay of an already-verified transaction.
 *   <li>{@code POST /api/orgs/{orgId}/payment-transactions/{id}/resolve} — orphan resolution:
 *       attach an ORPHAN transaction's payment to an admin-chosen order. {@code 201 Created} when
 *       this call created the payment, {@code 200 OK} on an idempotent replay.
 *   <li>{@code POST /api/orgs/{orgId}/payment-transactions/{id}/refund} — the orphan queue's other
 *       exit: promote a VERIFIED ORPHAN transaction that matches no order into a standalone payment
 *       plus a PENDING direct refund (executed separately via {@code /refunds/{id}/execute}).
 *       {@code 201 Created} / {@code 200 OK} on replay.
 * </ul>
 */
public class PaymentTransactionHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(PaymentTransactionHandler.class);

  private final PaymentTransactionService service;
  private final ObjectMapper mapper;

  public PaymentTransactionHandler(PaymentTransactionService service, ObjectMapper mapper) {
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

      String[] parts = splitPath(remainingPath);
      if (parts.length == 0) {
        doPost(req, resp, orgId);
        return;
      }
      if (parts.length == 2 && "resolve".equals(parts[1])) {
        doResolve(req, resp, orgId, parseId(parts[0]));
        return;
      }
      if (parts.length == 2 && "refund".equals(parts[1])) {
        doRefund(req, resp, orgId, parseId(parts[0]));
        return;
      }
      throw new ValidationException(
          "Unknown payment-transactions route: "
              + method
              + " /payment-transactions"
              + remainingPath);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/payment-transactions{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);

    VerifyPaymentTransactionRequest body = readBody(req, VerifyPaymentTransactionRequest.class);

    VerifyResult result =
        service.verify(orgId, PaymentTransactionMapper.toCommand(body), sc.actorId());

    PaymentTransactionResponse out = PaymentTransactionMapper.toResponse(result);
    writeJson(resp, result.replay() ? 200 : 201, out);
  }

  /** {@code POST /{id}/resolve} — admin attaches an ORPHAN transaction's payment to an order. */
  private void doResolve(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID transactionId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);

    ResolveOrphanRequest body = readBody(req, ResolveOrphanRequest.class);
    OrderRef ref = PaymentTransactionMapper.toOrderRef(body);

    VerifyResult result = service.resolveOrphan(orgId, transactionId, ref, sc.actorId());

    PaymentTransactionResponse out = PaymentTransactionMapper.toResponse(result);
    // 201 when this call created the Payment, 200 on an idempotent replay of a prior resolution.
    writeJson(resp, result.replay() ? 200 : 201, out);
  }

  /** {@code POST /{id}/refund} — admin refunds an ORPHAN transaction that matches no order. */
  private void doRefund(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID transactionId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);

    RefundOrphanRequest body = readBodyOrNull(req, RefundOrphanRequest.class);

    OrphanRefundResult result =
        service.refundOrphan(
            orgId,
            transactionId,
            PaymentTransactionMapper.toRefundMethod(body),
            body == null ? null : body.getNotes(),
            sc.actorId(),
            isOwnerOrAdmin(sc, orgId));

    OrphanRefundResponse out = PaymentTransactionMapper.toResponse(result);
    // 201 when this call created the payment + PENDING refund, 200 on an idempotent replay.
    writeJson(resp, result.replay() ? 200 : 201, out);
  }

  /** Above-threshold direct refunds escalate to OWNER; system ADMIN bypasses (as everywhere). */
  private static boolean isOwnerOrAdmin(SecurityContext sc, UUID orgId) {
    if (sc.isSystemAdmin()) {
      return true;
    }
    Set<OrgRole> roles = sc.orgRoles() == null ? null : sc.orgRoles().get(orgId);
    return roles != null && roles.contains(OrgRole.OWNER);
  }

  /** Split {@code remainingPath} ("/{id}/resolve") into its non-empty segments. */
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
      throw new ValidationException("Invalid transaction id format: " + raw);
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    return mapper.readValue(req.getInputStream(), type);
  }

  /** Like {@link #readBody} but tolerates an absent/empty body (all-optional request DTOs). */
  private <T> T readBodyOrNull(HttpServletRequest req, Class<T> type) throws IOException {
    byte[] raw = req.getInputStream().readAllBytes();
    if (raw.length == 0) {
      return null;
    }
    return mapper.readValue(raw, type);
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
