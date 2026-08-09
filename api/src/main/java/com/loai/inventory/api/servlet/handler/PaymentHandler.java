package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.DisputePaymentRequest;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PaymentResponse;
import com.loai.inventory.api.mapper.PaymentMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.PaymentDisputeService;
import com.loai.inventory.service.PaymentDisputeService.PaymentPage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code /api/orgs/{orgId}/payments} — the post-allocation dispute lifecycle
 * (sys-analysis/outbound/payment.md §Disputed):
 *
 * <ul>
 *   <li>{@code GET /payments/{id}} — read (VIEWER+), decorated with {@code allocations} — the
 *       invoices this payment funded — so the refund resolution knows which invoice to issue the
 *       DISPUTE_RESOLUTION CreditNote against ({@code stories/money_reads.md})
 *   <li>{@code POST /payments/{id}/dispute} — flag DISPUTED (MANAGER+); body {@code {reason?}}
 *   <li>{@code POST /payments/{id}/uphold} — resolve DISPUTED → ALLOCATED (MANAGER+)
 * </ul>
 *
 * <p>The refund resolution reuses the existing {@code /credit-notes} + {@code /refunds} endpoints
 * (issue a DISPUTE_RESOLUTION CreditNote against the payment's invoice, then refund it) — there is
 * no dispute-specific refund route here.
 */
public class PaymentHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(PaymentHandler.class);

  private final PaymentDisputeService service;
  private final ObjectMapper mapper;

  public PaymentHandler(PaymentDisputeService service, ObjectMapper mapper) {
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
      if (parts.length == 0) {
        if ("GET".equals(method)) {
          doList(req, resp, orgId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      UUID paymentId = parseId(parts[0]);
      if (parts.length == 1) {
        if ("GET".equals(method)) {
          doGet(req, resp, orgId, paymentId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      if (parts.length == 2) {
        String action = parts[1];
        if (!"dispute".equals(action) && !"uphold".equals(action)) {
          throw new ValidationException("Unknown payments route: " + remainingPath);
        }
        // Known action, wrong verb → 405 (not 400): the resource exists, the method doesn't.
        if (!"POST".equals(method)) {
          writeError(resp, 405, "Method not allowed");
          return;
        }
        switch (action) {
          case "dispute" -> doDispute(req, resp, orgId, paymentId);
          case "uphold" -> doUphold(req, resp, orgId, paymentId);
          default -> throw new ValidationException("Unknown payments route: " + remainingPath);
        }
        return;
      }
      throw new ValidationException("Unknown payments route: " + remainingPath);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/payments{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, PaymentMapper.toResponse(service.get(orgId, id)));
  }

  /**
   * {@code GET /payments?status=&unallocated=&page=&size=} — the org's payments worklist / ledger
   * ({@code stories/org_health_rollup.md}). {@code status=DISPUTED} is the disputes preview, {@code
   * unallocated=true} the unallocated preview; unknown {@code status} → 400. VIEWER+.
   */
  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", PaymentDisputeService.DEFAULT_PAGE_SIZE), 1),
            PaymentDisputeService.MAX_PAGE_SIZE);
    boolean unallocatedOnly = Boolean.parseBoolean(req.getParameter("unallocated"));
    PaymentPage result =
        service.list(
            orgId,
            PaymentMapper.toStatusFilter(req.getParameter("status")),
            unallocatedOnly,
            page,
            size);
    List<PaymentResponse> data = result.items().stream().map(PaymentMapper::toResponse).toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  private void doDispute(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    DisputePaymentRequest body = readBodyOrNull(req, DisputePaymentRequest.class);
    String reason = body == null ? null : body.getReason();
    Payment payment = service.dispute(orgId, id, reason, sc.actorId());
    writeJson(resp, 200, PaymentMapper.toResponse(payment));
  }

  private void doUphold(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    Payment payment = service.uphold(orgId, id, sc.actorId());
    writeJson(resp, 200, PaymentMapper.toResponse(payment));
  }

  private static String[] splitPath(String remainingPath) {
    if (remainingPath == null || remainingPath.isEmpty() || "/".equals(remainingPath)) {
      return new String[0];
    }
    String raw = remainingPath.startsWith("/") ? remainingPath.substring(1) : remainingPath;
    if (raw.isEmpty()) {
      return new String[0];
    }
    return raw.split("/");
  }

  private static UUID parseId(String s) {
    try {
      return UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid payment id: " + s);
    }
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

  /**
   * Read an optional JSON body. An absent or blank body yields {@code null} (no 400 — these routes
   * take an optional body). A present-but-unparseable body is a client error → {@link
   * ValidationException} (400), not a swallowed null and not a 500.
   */
  private <T> T readBodyOrNull(HttpServletRequest req, Class<T> type) throws IOException {
    byte[] bytes = req.getInputStream() == null ? new byte[0] : req.getInputStream().readAllBytes();
    if (bytes.length == 0 || new String(bytes, StandardCharsets.UTF_8).isBlank()) {
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
    ApiErrors.applyHeaders(resp, e);
    writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
