package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.PaymentTransactionResponse;
import com.loai.inventory.api.dto.VerifyPaymentTransactionRequest;
import com.loai.inventory.api.mapper.PaymentTransactionMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code POST /api/orgs/{orgId}/payment-transactions} — the admin-facing record-and-verify
 * endpoint for manual InstaPay claims. Requires MANAGER in the org (system ADMIN bypasses).
 *
 * <p>Returns {@code 201 Created} when this call processed the transaction, {@code 200 OK} on an
 * idempotent replay of an already-verified transaction.
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
      if (remainingPath != null && !remainingPath.isEmpty() && !"/".equals(remainingPath)) {
        throw new ValidationException(
            "Not implemented in this slice: " + method + " /payment-transactions" + remainingPath);
      }
      doPost(req, resp, orgId);
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
