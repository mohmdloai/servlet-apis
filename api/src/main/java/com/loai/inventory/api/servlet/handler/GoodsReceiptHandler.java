package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.CreateGoodsReceiptRequest;
import com.loai.inventory.api.dto.GoodsReceiptResponse;
import com.loai.inventory.api.dto.GoodsReceiptRow;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.VoidGoodsReceiptRequest;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.GoodsReceiptListFilter;
import com.loai.inventory.domain.model.GoodsReceiptStatus;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.GoodsReceiptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /api/orgs/{orgId}/goods-receipts} — receiving ({@code stories/supplier_goods_receipt.md}).
 *
 * <pre>
 * GET  /?supplier_id=&status=&from=&to=&q=&page=&size=   received_at DESC always   MANAGER
 * POST /                                                 Idempotency-Key required MANAGER
 * GET  /{id}                                             the document             MANAGER
 * POST /{id}/void {reason}                                                        MANAGER
 * </pre>
 *
 * <p><b>MANAGER throughout</b>: a receipt <i>is</i> a cost document — every line carries a unit
 * cost and the total is what the shop owes a supplier — so it takes the plane V92 settled for cost
 * and {@code /ledger} applies whole. A read that blanked out its own money column would be a worse
 * version of that decision.
 */
public class GoodsReceiptHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(GoodsReceiptHandler.class);
  private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final GoodsReceiptService service;
  private final ObjectMapper mapper;

  public GoodsReceiptHandler(GoodsReceiptService service, ObjectMapper mapper) {
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
      String rest = remainingPath == null ? "" : remainingPath;
      if (rest.startsWith("/")) rest = rest.substring(1);
      String[] segments = rest.isEmpty() ? new String[0] : rest.split("/", -1);
      if (segments.length > 2) {
        writeError(resp, 404, "Unknown goods receipt endpoint");
        return;
      }
      if (segments.length == 2) {
        if (!"void".equals(segments[1])) {
          writeError(resp, 404, "Unknown goods receipt endpoint");
          return;
        }
        if (!"POST".equals(method)) {
          writeError(resp, 405, "Method not allowed");
          return;
        }
        doVoid(req, resp, orgId, parseUuid(segments[0]));
        return;
      }

      UUID id = segments.length == 1 ? parseUuid(segments[0]) : null;
      switch (method) {
        case "GET" -> doGet(req, resp, orgId, id);
        case "POST" -> doPost(req, resp, orgId, id);
        default -> writeError(resp, 405, "Method not allowed");
      }
    } catch (AppException e) {
      writeError(resp, e);
    } catch (Exception e) {
      log.error("Unexpected error in /api/orgs/{}/goods-receipts{}", orgId, remainingPath, e);
      writeError(resp, 500, "Internal server error");
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    if (id != null) {
      writeJson(resp, 200, GoodsReceiptResponse.from(service.getById(orgId, id)));
      return;
    }
    int page = intParam(req, "page", 0);
    int size = intParam(req, "size", GoodsReceiptService.DEFAULT_PAGE_SIZE);
    GoodsReceiptService.ReceiptPage result = service.list(orgId, filter(req), page, size);
    List<GoodsReceiptRow> data =
        result.receipts().stream()
            .map(
                g ->
                    GoodsReceiptRow.from(
                        g,
                        result.suppliers().get(g.getSupplierId()),
                        result.lineCounts().getOrDefault(g.getId(), 0)))
            .toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
  }

  private void doPost(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    if (id != null) {
      throw new ValidationException("POST does not accept a goods receipt id in the path");
    }
    String key = req.getHeader(IDEMPOTENCY_HEADER);
    if (key == null || key.isBlank()) {
      throw new ValidationException(IDEMPOTENCY_HEADER + " header is required");
    }
    CreateGoodsReceiptRequest body = readBody(req, CreateGoodsReceiptRequest.class);
    GoodsReceiptService.ReceiptCommand cmd =
        new GoodsReceiptService.ReceiptCommand(
            body.getSupplierId(),
            body.getReceivedAt(),
            body.getSupplierReference(),
            body.getNotes(),
            body.getLines() == null
                ? null
                : body.getLines().stream()
                    .map(
                        l ->
                            new GoodsReceiptService.LineCommand(
                                l.getProductId(), l.getQuantity(), l.getUnitCost()))
                    .toList());
    GoodsReceiptService.ReceiptView view =
        service.record(orgId, cmd, key, sc.toActorContext(), sc.actorId());
    writeJson(resp, view.replayed() ? 200 : 201, GoodsReceiptResponse.from(view));
  }

  private void doVoid(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID id)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    VoidGoodsReceiptRequest body = readBody(req, VoidGoodsReceiptRequest.class);
    GoodsReceiptService.ReceiptView view =
        service.voidReceipt(orgId, id, body.getReason(), sc.toActorContext(), sc.actorId());
    writeJson(resp, 200, GoodsReceiptResponse.from(view));
  }

  /**
   * {@code supplier_id} narrows a set, so an unknown one is an empty page — the opposite of the
   * subresource's 404, and both are right. {@code from}/{@code to} are the reports' half-open
   * ISO-8601 window; a bare date or {@code from >= to} is a 400.
   */
  private static GoodsReceiptListFilter filter(HttpServletRequest req) {
    UUID supplierId = null;
    String rawSupplier = req.getParameter("supplier_id");
    if (rawSupplier != null && !rawSupplier.isBlank()) {
      try {
        supplierId = UUID.fromString(rawSupplier.trim());
      } catch (IllegalArgumentException e) {
        throw new ValidationException("Invalid supplier_id: " + rawSupplier);
      }
    }

    GoodsReceiptStatus status = null;
    String rawStatus = req.getParameter("status");
    if (rawStatus != null && !rawStatus.isBlank()) {
      try {
        status = GoodsReceiptStatus.valueOf(rawStatus.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new ValidationException("'status' must be POSTED or VOIDED");
      }
    }

    OffsetDateTime from = parseTs("from", req.getParameter("from"));
    OffsetDateTime to = parseTs("to", req.getParameter("to"));
    if (from != null && to != null && !from.isBefore(to)) {
      throw new ValidationException("'from' must be strictly before 'to'");
    }

    String q = req.getParameter("q");
    return new GoodsReceiptListFilter(
        supplierId, status, from, to, (q == null || q.isBlank()) ? null : q.trim());
  }

  private static OffsetDateTime parseTs(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new ValidationException("'" + name + "' must be an ISO-8601 date-time");
    }
  }

  private UUID parseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Invalid goods receipt id: " + raw);
    }
  }

  private <T> T readBody(HttpServletRequest req, Class<T> type) throws IOException {
    try {
      return mapper.readValue(req.getInputStream(), type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new ValidationException("request body is required and must be valid JSON");
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

  private int intParam(HttpServletRequest req, String name, int defaultValue) {
    String value = req.getParameter(name);
    if (value == null) return defaultValue;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new ValidationException("Parameter '" + name + "' must be an integer");
    }
  }
}
