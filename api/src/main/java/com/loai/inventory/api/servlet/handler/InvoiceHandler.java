package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.InvoiceListResponse;
import com.loai.inventory.api.dto.InvoiceSummaryResponse;
import com.loai.inventory.api.dto.ReissueInvoiceRequest;
import com.loai.inventory.api.dto.VoidInvoiceRequest;
import com.loai.inventory.api.mapper.InvoiceMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.InvoiceListFilter;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceAdminService.InvoicePage;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.DocumentRenderService.RenderedDocument;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code /api/orgs/{orgId}/invoices}:
 *
 * <ul>
 *   <li>{@code GET /invoices?status=&q=&from=&to=&paid=&min=&max=&page=&size=} — the
 *       awaiting-payment worklist / invoice ledger ({@code stories/invoice_reads.md}, narrowed by
 *       {@code stories/invoice_filters.md}): filtered = queue oldest-first ({@code ?status=ISSUED}
 *       is the awaiting-payment queue), unfiltered = ledger newest-first (VOID included); lean rows
 *       carry their batch-loaded {@code sales_order_number}; the envelope carries the filtered
 *       set's money summary. VIEWER+.
 *   <li>{@code GET /invoices/status-counts} — the worklist tabs' numbers, one query. VIEWER+.
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
  private final DocumentRenderService renderService;
  private final ObjectMapper mapper;

  public InvoiceHandler(
      InvoiceAdminService service, DocumentRenderService renderService, ObjectMapper mapper) {
    this.service = service;
    this.renderService = renderService;
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

      if (parts.length == 1 && "status-counts".equals(parts[0])) {
        if ("GET".equals(method)) {
          doStatusCounts(req, resp, orgId);
        } else {
          writeError(resp, 405, "Method not allowed");
        }
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

      if (parts.length == 2 && "pdf".equals(parts[1])) {
        if ("GET".equals(method)) {
          doPdf(req, resp, orgId, invoiceId);
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

  /**
   * {@code GET /?status=&q=&from=&to=&paid=&min=&max=&page=&size=} — the awaiting-payment worklist
   * (with a status) / invoice ledger (without), narrowed by the other dimensions ({@code
   * stories/invoice_filters.md}). The envelope carries the filtered set's money summary.
   */
  private void doList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    // Clamp here too so the envelope echoes the page/size actually served.
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", InvoiceAdminService.DEFAULT_PAGE_SIZE), 1),
            InvoiceAdminService.MAX_PAGE_SIZE);
    InvoiceListFilter filter =
        InvoiceMapper.toListFilter(
            req.getParameter("status"),
            req.getParameter("q"),
            req.getParameter("from"),
            req.getParameter("to"),
            req.getParameter("paid"),
            req.getParameter("min"),
            req.getParameter("max"));
    InvoicePage result = service.list(orgId, filter, page, size);
    List<InvoiceSummaryResponse> data =
        result.items().stream().map(InvoiceMapper::toSummaryResponse).toList();
    writeJson(
        resp,
        200,
        new InvoiceListResponse(
            data, result.total(), page, size, InvoiceMapper.toSummaryResponse(result.stats())));
  }

  /** {@code GET /status-counts} — the worklist tabs' numbers (VIEWER), one query. */
  private void doStatusCounts(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, InvoiceMapper.toStatusCountsResponse(service.statusCounts(orgId)));
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, InvoiceMapper.toResponse(service.get(orgId, id)));
  }

  /** {@code GET /{id}/pdf} — the printable invoice (VIEWER), {@code application/pdf}. */
  private void doPdf(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    RenderedDocument doc = renderService.renderInvoice(orgId, id);
    writePdf(resp, doc.bytes(), doc.filename());
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

  private void writeJson(HttpServletResponse resp, int status, Object body) throws IOException {
    resp.setStatus(status);
    resp.setContentType("application/json");
    resp.setCharacterEncoding("UTF-8");
    mapper.writeValue(resp.getOutputStream(), body);
  }

  private void writePdf(HttpServletResponse resp, byte[] bytes, String filename)
      throws IOException {
    resp.setStatus(200);
    resp.setContentType("application/pdf");
    resp.setContentLength(bytes.length);
    resp.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"");
    resp.getOutputStream().write(bytes);
  }

  private void writeError(HttpServletResponse resp, AppException e) throws IOException {
    ApiErrors.applyHeaders(resp, e);
    writeJson(resp, e.getStatusCode(), ApiErrors.body(e));
  }

  private void writeError(HttpServletResponse resp, int status, String message) throws IOException {
    writeJson(resp, status, ApiError.of(status, message));
  }
}
