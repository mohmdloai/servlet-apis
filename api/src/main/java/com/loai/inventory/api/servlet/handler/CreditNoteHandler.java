package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.CreditNoteResponse;
import com.loai.inventory.api.dto.IssueCreditNoteRequest;
import com.loai.inventory.api.mapper.CreditNoteMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.CreditNoteService.Issued;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code /api/orgs/{orgId}/credit-notes}:
 *
 * <ul>
 *   <li>{@code GET /credit-notes?sales_invoice_id=&status=} — one invoice's crediting story with
 *       the already-credited sum, powering the cumulative-cap meter ({@code
 *       stories/money_reads.md}). {@code sales_invoice_id} is required — a bare {@code GET} is a
 *       400, the route stays reserved for a future unfiltered list. VIEWER+.
 *   <li>{@code POST /credit-notes} — issue a credit note (MANAGER+; OWNER above the org's refund
 *       approval threshold, enforced in the service against the committed org row)
 *   <li>{@code GET /credit-notes/{id}} — read (VIEWER+)
 *   <li>{@code POST /credit-notes/{id}/void} — void (OWNER)
 * </ul>
 */
public class CreditNoteHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(CreditNoteHandler.class);

  private final CreditNoteService service;
  private final ObjectMapper mapper;

  public CreditNoteHandler(CreditNoteService service, ObjectMapper mapper) {
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
          doIssue(req, resp, orgId);
        } else if ("GET".equals(method)) {
          doList(req, resp, orgId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      UUID creditNoteId = parseId(parts[0]);
      if (parts.length == 1) {
        if ("GET".equals(method)) {
          doGet(req, resp, orgId, creditNoteId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
        return;
      }

      if (parts.length == 2 && "void".equals(parts[1]) && "POST".equals(method)) {
        doVoid(req, resp, orgId, creditNoteId);
        return;
      }
      throw new ValidationException("Unknown credit-notes route: " + remainingPath);
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/credit-notes{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doIssue(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    IssueCreditNoteRequest body = readBody(req, IssueCreditNoteRequest.class);
    Issued issued =
        service.issue(orgId, CreditNoteMapper.toCommand(body), isOwnerOrAdmin(sc, orgId));
    writeJson(resp, 201, CreditNoteMapper.toResponse(issued));
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    Issued issued = service.get(orgId, id);
    writeJson(resp, 200, CreditNoteMapper.toResponse(issued));
  }

  /**
   * {@code GET /?sales_invoice_id=&status=} — the invoice's crediting story: header +
   * already-credited sum + notes oldest-first. {@code sales_invoice_id} is required; the bare list
   * is deliberately reserved (same convention as the bare {@code GET /sales-orders}).
   */
  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    String rawInvoiceId = req.getParameter("sales_invoice_id");
    if (rawInvoiceId == null || rawInvoiceId.isBlank()) {
      throw new ValidationException(
          "sales_invoice_id query parameter is required (the unfiltered list is not implemented)");
    }
    UUID salesInvoiceId;
    try {
      salesInvoiceId = UUID.fromString(rawInvoiceId.trim());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid sales_invoice_id: " + rawInvoiceId);
    }
    writeJson(
        resp,
        200,
        CreditNoteMapper.toInvoiceCreditNotesResponse(
            service.listForInvoice(
                orgId,
                salesInvoiceId,
                CreditNoteMapper.toStatusFilter(req.getParameter("status")))));
  }

  private void doVoid(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.OWNER);
    var note = service.voidNote(orgId, id);
    writeJson(resp, 200, CreditNoteResponse.from(note, service.get(orgId, id).lines()));
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
      throw new ValidationException("Invalid credit note id: " + s);
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
