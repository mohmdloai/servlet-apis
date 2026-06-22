package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CancelRefundRequest;
import com.loai.inventory.api.dto.CreateRefundRequest;
import com.loai.inventory.api.dto.ExecuteRefundRequest;
import com.loai.inventory.api.mapper.RefundMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.RefundService.Executed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code /api/orgs/{orgId}/refunds}:
 *
 * <ul>
 *   <li>{@code POST /refunds} — create a PENDING refund (MANAGER+; OWNER above the org's refund
 *       approval threshold for the direct-from-Payment path, enforced in the service)
 *   <li>{@code GET /refunds/{id}} — read (VIEWER+)
 *   <li>{@code POST /refunds/{id}/execute} — execute (MANAGER+)
 *   <li>{@code POST /refunds/{id}/cancel} — cancel (MANAGER+)
 * </ul>
 */
public class RefundHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(RefundHandler.class);

  private final RefundService service;
  private final ObjectMapper mapper;

  public RefundHandler(RefundService service, ObjectMapper mapper) {
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
        if ("POST".equals(method)) {
          doCreate(req, resp, orgId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      UUID refundId = parseId(parts[0]);
      if (parts.length == 1) {
        if ("GET".equals(method)) {
          doGet(req, resp, orgId, refundId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      if (parts.length == 2 && "POST".equals(method)) {
        switch (parts[1]) {
          case "execute" -> doExecute(req, resp, orgId, refundId);
          case "cancel" -> doCancel(req, resp, orgId, refundId);
          default -> throw new ValidationException("Unknown refunds route: " + remainingPath);
        }
        return;
      }
      throw new ValidationException("Unknown refunds route: " + remainingPath);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/refunds{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doCreate(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    CreateRefundRequest body = readBody(req, CreateRefundRequest.class);
    Refund refund = service.create(orgId, RefundMapper.toCommand(body), isOwnerOrAdmin(sc, orgId));
    writeJson(resp, 201, RefundMapper.toResponse(refund));
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, RefundMapper.toResponse(service.get(orgId, id)));
  }

  private void doExecute(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    ExecuteRefundRequest body = readBodyOrNull(req, ExecuteRefundRequest.class);
    String providerRef = body == null ? null : body.getProviderRef();
    Executed executed = service.execute(orgId, id, providerRef, sc.actorId());
    writeJson(resp, 200, RefundMapper.toResponse(executed.refund()));
  }

  private void doCancel(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    CancelRefundRequest body = readBodyOrNull(req, CancelRefundRequest.class);
    String reason = body == null ? null : body.getReason();
    writeJson(resp, 200, RefundMapper.toResponse(service.cancel(orgId, id, reason)));
  }

  private static boolean isOwnerOrAdmin(SecurityContext sc, UUID orgId) {
    if (sc.isSystemAdmin()) {
      return true;
    }
    Set<OrgRole> roles = sc.orgRoles() == null ? null : sc.orgRoles().get(orgId);
    return roles != null && roles.contains(OrgRole.OWNER);
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
      throw new ValidationException("Invalid refund id: " + s);
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    return mapper.readValue(req.getInputStream(), type);
  }

  /** Read an optional JSON body — a missing/empty body yields {@code null} (no 400). */
  private <T> T readBodyOrNull(HttpServletRequest req, Class<T> type) throws IOException {
    if (req.getInputStream() == null || req.getContentLength() == 0) {
      return null;
    }
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.databind.exc.MismatchedInputException e) {
      return null;
    }
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
