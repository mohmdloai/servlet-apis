package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.OrphanRefundResponse;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PaymentTransactionResponse;
import com.loai.inventory.api.dto.RefundOrphanRequest;
import com.loai.inventory.api.dto.ResolveOrphanRequest;
import com.loai.inventory.api.dto.VerifyClaimRequest;
import com.loai.inventory.api.dto.VerifyPaymentTransactionRequest;
import com.loai.inventory.api.mapper.PaymentTransactionMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.repository.PaymentTransactionRepository.ListFilter;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.OrphanRefundResult;
import com.loai.inventory.service.PaymentTransactionService.TransactionDetail;
import com.loai.inventory.service.PaymentTransactionService.TransactionPage;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Payment-transaction routes. Reads require VIEWER in the org, mutations MANAGER (system ADMIN
 * bypasses both):
 *
 * <ul>
 *   <li>{@code GET /api/orgs/{orgId}/payment-transactions} — the reconciliation worklist / ledger
 *       ({@code stories/list_payment_transactions.md}). Optional ANDed filters {@code
 *       verification_status}, {@code reconciliation_status}, {@code has_payment}, {@code provider},
 *       {@code provider_ref} (exact after trimming, case-sensitive — the support lookup "did we
 *       record this reference?", {@code stories/lookup_transaction_by_reference.md}) plus {@code
 *       page}/{@code size}; the open orphan queue is {@code
 *       ?reconciliation_status=ORPHAN&has_payment=false}.
 *   <li>{@code GET /api/orgs/{orgId}/payment-transactions/{id}} — one transaction plus its
 *       disposition context (the 1:1 payment and that payment's order, when they exist).
 *   <li>{@code POST /api/orgs/{orgId}/payment-transactions} — record-and-verify a manual InstaPay
 *       claim. {@code 201 Created} when this call processed it, {@code 200 OK} on an idempotent
 *       replay of an already-verified transaction. {@code 409 CLAIM_PENDING} (with the claims) when
 *       the order has open shopper claims and the reference is none of them — unless every claim id
 *       is in {@code acknowledge_claim_ids} ({@code stories/payment_claim_verify.md}).
 *   <li>{@code POST /api/orgs/{orgId}/payment-transactions/{id}/verify} — "Found it": verify one
 *       shopper claim by id and reconcile it against the order the shopper named. Body optional
 *       ({@code {amount?, occurred_at?, verification_proof?}}). {@code 201} on the verify, {@code
 *       200} on a replay of an already-verified claim, {@code 409} on an ABANDONED one.
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
      String[] parts = splitPath(remainingPath);
      if ("GET".equals(method)) {
        if (parts.length == 0) {
          doList(req, resp, orgId);
          return;
        }
        if (parts.length == 1) {
          doGet(req, resp, orgId, parseId(parts[0]));
          return;
        }
      } else if ("POST".equals(method)) {
        if (parts.length == 0) {
          doPost(req, resp, orgId);
          return;
        }
        if (parts.length == 2 && "verify".equals(parts[1])) {
          doVerifyClaim(req, resp, orgId, parseId(parts[0]));
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
      } else {
        writeError(resp, 405, "Method not allowed");
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

  /** {@code GET /} — the reconciliation worklist (filtered queue views) / transaction ledger. */
  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);

    ListFilter filter =
        new ListFilter(
            enumParam(req, "verification_status", PaymentVerificationStatus.class),
            enumParam(req, "reconciliation_status", PaymentReconciliationStatus.class),
            boolParam(req, "has_payment"),
            PaymentTransactionMapper.toProviderFilter(req.getParameter("provider")),
            // ListFilter's canonical constructor trims (references arrive by copy-paste).
            req.getParameter("provider_ref"),
            // The claims filed against one order — the order page's claim card.
            uuidParam(req, "sales_order_id"));
    // Clamp here too so the envelope echoes the page/size actually served.
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", PaymentTransactionService.DEFAULT_PAGE_SIZE), 1),
            PaymentTransactionService.MAX_PAGE_SIZE);

    TransactionPage result = service.list(orgId, filter, page, size);
    // Rows carry their batch-loaded claim context (customer + claimed order) but never the
    // presigned proof URL and never the verifier's name — both are detail reads.
    List<PaymentTransactionResponse> data =
        result.items().stream()
            .map(
                txn ->
                    PaymentTransactionResponse.withContext(
                        txn,
                        null,
                        null,
                        txn.getClaimedByCustomerId() == null
                            ? null
                            : result.customers().get(txn.getClaimedByCustomerId()),
                        txn.getClaimedSalesOrderId() == null
                            ? null
                            : result.claimedOrders().get(txn.getClaimedSalesOrderId()),
                        null))
            .toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  /**
   * {@code GET /{id}} — one transaction plus its disposition payment/order when they exist, and a
   * presigned {@code proof_url} for the shopper's uploaded screenshot when they attached one. The
   * URL is short-lived, so this response is {@code no-store}: a cached copy would outlive the
   * credential and hand a stale link to whoever read the cache.
   */
  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);

    TransactionDetail detail = service.get(orgId, id);
    resp.setHeader("Cache-Control", "private, no-store");
    PaymentTransactionResponse out =
        PaymentTransactionResponse.withContext(
            detail.transaction(),
            detail.payment(),
            detail.order(),
            detail.customer(),
            detail.claimedOrder(),
            detail.verifiedByName());
    writeJson(resp, 200, withProof(out, detail.proofUrl()));
  }

  /** Attach the detail-only presigned proof URL to an already-built response. */
  private static PaymentTransactionResponse withProof(
      PaymentTransactionResponse out, String proofUrl) {
    return PaymentTransactionResponse.attachProof(out, proofUrl);
  }

  /**
   * {@code POST /{id}/verify} — "Found it" on one shopper claim (MANAGER). The body is optional:
   * the claim already carries the reference, the amount and the order the shopper named.
   */
  private void doVerifyClaim(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID transactionId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);

    VerifyClaimRequest body = readBodyOrNull(req, VerifyClaimRequest.class);

    VerifyResult result =
        service.verifyClaim(
            orgId, transactionId, PaymentTransactionMapper.toClaimCommand(body), sc.actorId());

    PaymentTransactionResponse out =
        PaymentTransactionMapper.toResponse(result, service.contextOf(orgId, result.transaction()));
    // 201 when this call verified the claim, 200 on an idempotent replay ("already verified by …").
    writeJson(resp, result.replay() ? 200 : 201, out);
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

  /** Case-insensitive enum query param; absent → null, unknown value → 400 (fail loudly). */
  private static <E extends Enum<E>> E enumParam(
      HttpServletRequest req, String name, Class<E> type) {
    String raw = req.getParameter(name);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown " + name + ": " + raw);
    }
  }

  /** UUID query param; absent → null (no filter), malformed → 400. */
  private static UUID uuidParam(HttpServletRequest req, String name) {
    String raw = req.getParameter(name);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Parameter '" + name + "' must be a UUID");
    }
  }

  /** Strict boolean query param; absent → null (no filter), anything but true/false → 400. */
  private static Boolean boolParam(HttpServletRequest req, String name) {
    String raw = req.getParameter(name);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String v = raw.trim();
    if ("true".equalsIgnoreCase(v)) {
      return Boolean.TRUE;
    }
    if ("false".equalsIgnoreCase(v)) {
      return Boolean.FALSE;
    }
    throw new ValidationException("Parameter '" + name + "' must be true or false");
  }

  private static int intParam(HttpServletRequest req, String name, int defaultValue) {
    String value = req.getParameter(name);
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      // Empty/truncated/malformed body — a 400, not an unhandled 500.
      throw new com.loai.inventory.common.exception.ValidationException(
          "request body is required and must be valid JSON");
    }
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
    ApiErrors.applyHeaders(resp, e);
    writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
