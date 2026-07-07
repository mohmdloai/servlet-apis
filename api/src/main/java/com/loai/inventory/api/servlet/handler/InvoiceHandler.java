package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ReissueInvoiceRequest;
import com.loai.inventory.api.dto.VoidInvoiceRequest;
import com.loai.inventory.api.mapper.InvoiceMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.InvoiceAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code /api/orgs/{orgId}/invoices}:
 *
 * <ul>
 *   <li>{@code GET /invoices/{id}} — read an invoice + lines (VIEWER+)
 *   <li>{@code POST /invoices/{id}/void} — cancel an ISSUED, unpaid, uncredited invoice (MANAGER+)
 *   <li>{@code POST /invoices/{id}/reissue} — void + issue a corrected replacement (MANAGER+)
 * </ul>
 *
 * <p>There is no {@code POST /invoices} — invoices are issued only as a side-effect of delivery /
 * in-store sale, never directly.
 */
public class InvoiceHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(InvoiceHandler.class);

  private final InvoiceAdminService service;
  private final ObjectMapper mapper;

  public InvoiceHandler(InvoiceAdminService service, ObjectMapper mapper) {
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
        writeError(resp, 405, "Method not allowed");
        return;
      }

      UUID invoiceId = parseId(parts[0]);
      if (parts.length == 1) {
        if ("GET".equals(method)) {
          doGet(req, resp, orgId, invoiceId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      if (parts.length == 2 && "POST".equals(method)) {
        switch (parts[1]) {
          case "void" -> doVoid(req, resp, orgId, invoiceId);
          case "reissue" -> doReissue(req, resp, orgId, invoiceId);
          default -> throw new ValidationException("Unknown invoices route: " + remainingPath);
        }
        return;
      }
      throw new ValidationException("Unknown invoices route: " + remainingPath);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/invoices{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, InvoiceMapper.toResponse(service.get(orgId, id)));
  }

  private void doVoid(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    VoidInvoiceRequest body = readBody(req, VoidInvoiceRequest.class);
    String reason = body == null ? null : body.getReason();
    writeJson(resp, 200, InvoiceMapper.toResponse(service.voidInvoice(orgId, id, reason)));
  }

  private void doReissue(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    ReissueInvoiceRequest body = readBody(req, ReissueInvoiceRequest.class);
    String reason = body == null ? null : body.getReason();
    var issued = service.reissue(orgId, id, reason, InvoiceMapper.toReissueLines(body));
    writeJson(resp, 201, InvoiceMapper.toResponse(issued));
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
      throw new ValidationException("Invalid invoice id: " + s);
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
