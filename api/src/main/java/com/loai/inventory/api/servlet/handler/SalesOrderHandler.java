package com.loai.inventory.api.servlet.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.dto.CancelOrderRequest;
import com.loai.inventory.api.dto.CounterReturnRequest;
import com.loai.inventory.api.dto.CounterReturnResponse;
import com.loai.inventory.api.dto.PageResponse;
import com.loai.inventory.api.dto.PlaceSalesOrderRequest;
import com.loai.inventory.api.dto.ReturnableResponse;
import com.loai.inventory.api.mapper.FulfillmentMapper;
import com.loai.inventory.api.mapper.InventoryMapper;
import com.loai.inventory.api.mapper.InvoiceMapper;
import com.loai.inventory.api.mapper.PaymentMapper;
import com.loai.inventory.api.mapper.SalesOrderMapper;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.CounterReturnService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.OrderCancellationService.CancelResult;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.Placed;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.DocumentRenderService.RenderedDocument;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code /api/orgs/{orgId}/sales-orders}:
 *
 * <ul>
 *   <li>{@code POST /} — place an order, dispatching by request {@code channel}: {@code IN_STORE}
 *       runs the whole sale in one txn ({@code stories/in_store_sale.md}); {@code ONLINE} / {@code
 *       PHONE} (the default) place a PENDING_PAYMENT order with reservations ({@code
 *       stories/place_online_order.md}). Both require STAFF (system ADMIN bypasses); an in-store
 *       {@code discount} block additionally needs MANAGER+, decided in the service (403 {@code
 *       APPROVAL_REQUIRED} with {@code required_role: MANAGER}).
 *   <li>{@code POST /{id}/cancel} — cancel an order (MANAGER).
 *   <li>{@code GET /{id}/returnable} · {@code POST /{id}/return} — the counter return ({@code
 *       stories/counter_return.md}): preview (VIEWER) and the one-transaction credit note + refund
 *       + restock (MANAGER, {@code Idempotency-Key}).
 *   <li>{@code GET /?order_number=} — exact-match lookup by human-readable number, the pre-flight
 *       for the manual money path ({@code stories/lookup_order_by_number.md}). VIEWER. A bare
 *       {@code GET} without the param returns the order worklist page ({@code
 *       ?status=&page=&size=}).
 *   <li>{@code GET /status-counts} — the worklist tabs' numbers: all eight statuses' live counts
 *       (zeros included) + the ledger total ({@code stories/order_status_counts.md}). VIEWER.
 *   <li>{@code GET /{id}} — one order + its lines by stable id ({@code
 *       stories/fulfillment_reads.md}). VIEWER.
 *   <li>{@code GET /{id}/payments} — the order's money story: every payment FIFO with its refunds
 *       ({@code stories/list_order_payments.md}). VIEWER.
 *   <li>{@code GET /{id}/fulfillments} — the order's shipment story: every fulfillment oldest-first
 *       with its lines ({@code stories/fulfillment_reads.md}). VIEWER.
 *   <li>{@code GET /{id}/invoices} — the order's billing story: every invoice oldest-first with its
 *       lines ({@code stories/money_reads.md}). VIEWER.
 * </ul>
 *
 * <p>Other methods and sub-paths are not implemented yet — later slices.
 */
public class SalesOrderHandler implements OrgResourceHandler {

  private static final Logger log = LoggerFactory.getLogger(SalesOrderHandler.class);
  private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

  private final SalesOrderService service;
  private final OrderCancellationService cancellationService;
  private final PaymentService paymentService;
  private final FulfillmentService fulfillmentService;
  private final InvoiceAdminService invoiceAdminService;
  private final InventoryService inventoryService;
  private final DocumentRenderService renderService;
  private final CounterReturnService counterReturnService;
  private final ObjectMapper mapper;

  public SalesOrderHandler(
      SalesOrderService service,
      OrderCancellationService cancellationService,
      PaymentService paymentService,
      FulfillmentService fulfillmentService,
      InvoiceAdminService invoiceAdminService,
      InventoryService inventoryService,
      DocumentRenderService renderService,
      CounterReturnService counterReturnService,
      ObjectMapper mapper) {
    this.service = service;
    this.cancellationService = cancellationService;
    this.paymentService = paymentService;
    this.fulfillmentService = fulfillmentService;
    this.invoiceAdminService = invoiceAdminService;
    this.inventoryService = inventoryService;
    this.renderService = renderService;
    this.counterReturnService = counterReturnService;
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
      // Fixed segment BEFORE the {id} UUID parse (the portal unread-count precedent) and before
      // the method split, so a non-GET here is a clean 405, not the POST fallthrough's 400.
      if (parts.length == 1 && "status-counts".equals(parts[0])) {
        if (!"GET".equals(method)) {
          writeError(resp, 405, "Method not allowed");
          return;
        }
        doStatusCounts(req, resp, orgId);
        return;
      }
      if ("GET".equals(method) && parts.length == 0) {
        doGetOrList(req, resp, orgId);
        return;
      }
      if ("GET".equals(method) && parts.length == 1) {
        doGetById(req, resp, orgId, parseId(parts[0]));
        return;
      }
      if ("GET".equals(method) && parts.length == 2 && "payments".equals(parts[1])) {
        doGetPayments(req, resp, orgId, parseId(parts[0]));
        return;
      }
      if ("GET".equals(method) && parts.length == 2 && "fulfillments".equals(parts[1])) {
        doGetFulfillments(req, resp, orgId, parseId(parts[0]));
        return;
      }
      if ("GET".equals(method) && parts.length == 2 && "invoices".equals(parts[1])) {
        doGetInvoices(req, resp, orgId, parseId(parts[0]));
        return;
      }
      if ("GET".equals(method) && parts.length == 2 && "reservations".equals(parts[1])) {
        doGetReservations(req, resp, orgId, parseId(parts[0]));
        return;
      }
      if ("GET".equals(method) && parts.length == 2 && "receipt.pdf".equals(parts[1])) {
        doReceiptPdf(req, resp, orgId, parseId(parts[0]));
        return;
      }
      if ("GET".equals(method) && parts.length == 2 && "returnable".equals(parts[1])) {
        doReturnable(req, resp, orgId, parseId(parts[0]));
        return;
      }
      if (!"POST".equals(method)) {
        writeError(resp, 405, "Method not allowed");
        return;
      }

      if (parts.length == 0) {
        doPost(req, resp, orgId);
        return;
      }
      if (parts.length == 2 && "cancel".equals(parts[1])) {
        doCancel(req, resp, orgId, parseId(parts[0]));
        return;
      }
      if (parts.length == 2 && "return".equals(parts[1])) {
        doReturn(req, resp, orgId, parseId(parts[0]));
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

    // A counter discount is an in-store gesture (stories/counter_discount.md): staff phone orders
    // take coupons, and a discount on an order that still has to be paid remotely is a different
    // authority again. Refused by shape here; the MANAGER gate itself lives in the service.
    SalesOrderService.DiscountInput discount = SalesOrderMapper.toDiscountInput(body);
    if (discount != null && channel != OrderChannel.IN_STORE) {
      throw new ValidationException("discount is only accepted on IN_STORE sales");
    }

    if (channel == OrderChannel.IN_STORE) {
      InStoreSale sale =
          service.placeInStoreSale(
              orgId,
              SalesOrderMapper.toCustomerInput(body),
              SalesOrderMapper.toLineInputs(body),
              SalesOrderMapper.toPaymentInput(body),
              discount,
              body.getNotes(),
              actor,
              idempotencyKey,
              sc.actorId(),
              isManagerOrAdmin(sc, orgId));
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

  /**
   * {@code GET /sales-orders/status-counts} — the worklist tabs' numbers ({@code
   * stories/order_status_counts.md}): every status's live count, all eight always present ({@code
   * 0} included), plus {@code total} = the unfiltered ledger count. VIEWER — a projection of the
   * worklist the caller can already page, no figure more sensitive than the list itself.
   */
  private void doStatusCounts(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, SalesOrderMapper.toStatusCountsResponse(service.statusCounts(orgId)));
  }

  /**
   * {@code GET /sales-orders} — two reads on one route (VIEWER):
   *
   * <ul>
   *   <li>{@code ?order_number=SO-…} — exact-match, case-sensitive lookup (trimmed: numbers arrive
   *       by copy-paste from transfer notes); a single {@code SalesOrderResponse}, not a page
   *       ({@code stories/lookup_order_by_number.md}).
   *   <li>otherwise — the order <b>worklist</b>: {@code ?status=&page=&size=}, a {@code
   *       PageResponse} of full order rows. Filtered by status = queue (oldest first); unfiltered =
   *       ledger (newest first). Unknown status ⇒ 400.
   * </ul>
   */
  private void doGetOrList(HttpServletRequest req, HttpServletResponse resp, UUID orgId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    String orderNumber = req.getParameter("order_number");
    if (orderNumber != null && !orderNumber.isBlank()) {
      writeJson(resp, 200, SalesOrderMapper.toResponse(service.getByNumber(orgId, orderNumber)));
      return;
    }
    int page = Math.max(intParam(req, "page", 0), 0);
    int size =
        Math.min(
            Math.max(intParam(req, "size", SalesOrderService.DEFAULT_PAGE_SIZE), 1),
            SalesOrderService.MAX_PAGE_SIZE);
    SalesOrderService.OrderListPage result =
        service.list(
            orgId,
            SalesOrderMapper.toOrderStatus(req.getParameter("status")),
            SalesOrderMapper.toOrderChannel(req.getParameter("channel")),
            page,
            size);
    var data = result.items().stream().map(SalesOrderMapper::toResponse).toList();
    writeJson(resp, 200, new PageResponse<>(data, result.total(), page, size));
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
   * {@code GET /{id}} — one order + its lines, by stable id ({@code stories/fulfillment_reads.md}):
   * the detail read behind the worklist row. Same {@code SalesOrderResponse} shape as the {@code
   * ?order_number=} lookup. VIEWER.
   */
  private void doGetById(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID orderId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, SalesOrderMapper.toResponse(service.getById(orgId, orderId)));
  }

  /**
   * {@code GET /{id}/fulfillments} — the order's shipment story: every fulfillment ever created for
   * it (any status) oldest-first, each with its lines, plus the order header — the shipment mirror
   * of {@code /{id}/payments}. VIEWER.
   */
  private void doGetFulfillments(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID orderId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(
        resp,
        200,
        FulfillmentMapper.toOrderFulfillmentsResponse(
            fulfillmentService.listForOrder(orgId, orderId)));
  }

  /**
   * {@code GET /{id}/invoices} — the order's billing story: every invoice ever issued against it
   * (VOID included) oldest-first, each with its lines, plus the order header — the billing mirror
   * of {@code /{id}/payments} and {@code /{id}/fulfillments}. VIEWER.
   */
  private void doGetInvoices(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID orderId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(
        resp,
        200,
        InvoiceMapper.toOrderInvoicesResponse(invoiceAdminService.listForOrder(orgId, orderId)));
  }

  /**
   * {@code GET /{id}/reservations} — the order's stock-holds story: every reservation ever created
   * for it (all statuses) oldest-first, each with its product name, plus the order header — the
   * inventory mirror of {@code /{id}/payments} ({@code stories/inventory_reads.md}). VIEWER.
   */
  private void doGetReservations(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID orderId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(
        resp,
        200,
        InventoryMapper.toOrderReservationsResponse(
            inventoryService.listOrderReservations(orgId, orderId)));
  }

  /**
   * {@code GET /{id}/payments} — every payment ever applied to the order, FIFO ({@code received_at
   * ASC, id ASC}), each with its refunds. VIEWER — the project-wide read bar.
   */
  private void doGetPayments(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID orderId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(
        resp,
        200,
        PaymentMapper.toOrderPaymentsResponse(paymentService.listForOrder(orgId, orderId)));
  }

  /**
   * {@code GET /{id}/receipt.pdf} — the 80mm thermal receipt for the sale (VIEWER): store header,
   * lines, totals, tender + change. 404 if the order has no issued invoice. See {@code
   * stories/document_pdf_rendering.md} (Part B).
   */
  private void doReceiptPdf(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID orderId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    RenderedDocument doc = renderService.renderReceipt(orgId, orderId);
    writePdf(resp, doc.bytes(), doc.filename());
  }

  /**
   * {@code GET /{id}/returnable} — what the receipt can still give back (VIEWER; {@code
   * stories/counter_return.md}): the live invoice's lines with billed / returned / returnable and a
   * display-only per-unit net refund, plus the tender and the refund mode. 409 unless the order is
   * a CLOSED IN_STORE sale.
   */
  private void doReturnable(
      HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID orderId)
      throws IOException {
    AuthzHelper.requireOrgAccess(req, orgId, OrgRole.VIEWER);
    writeJson(resp, 200, ReturnableResponse.from(counterReturnService.returnable(orgId, orderId)));
  }

  /**
   * {@code POST /{id}/return} — the counter return (MANAGER; {@code Idempotency-Key} required): a
   * RETURN credit note against the sale's invoice, the refund (EXECUTED for cash, PENDING for a
   * transfer) and the restock, in one transaction. 201 fresh, 200 on a replay of the same key.
   * Above the org threshold the issuance's own OWNER gate answers 403 {@code APPROVAL_REQUIRED}.
   */
  private void doReturn(HttpServletRequest req, HttpServletResponse resp, UUID orgId, UUID orderId)
      throws IOException {
    SecurityContext sc = AuthzHelper.requireOrgAccess(req, orgId, OrgRole.MANAGER);
    String idempotencyKey = req.getHeader(IDEMPOTENCY_HEADER);
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ValidationException(IDEMPOTENCY_HEADER + " header is required");
    }
    CounterReturnRequest body = readBody(req, CounterReturnRequest.class);
    CounterReturnService.Returned returned =
        counterReturnService.returnFromReceipt(
            orgId,
            orderId,
            SalesOrderMapper.toReturnCommand(body),
            idempotencyKey,
            sc.toActorContext(),
            sc.actorId(),
            isOwnerOrAdmin(sc, orgId));
    writeJson(resp, returned.replayed() ? 200 : 201, CounterReturnResponse.from(returned));
  }

  /** OWNER in the org (or system ADMIN) — gates an above-threshold cancellation refund. */
  private static boolean isOwnerOrAdmin(SecurityContext sc, UUID orgId) {
    if (sc.isSystemAdmin()) {
      return true;
    }
    var roles = sc.orgRoles() == null ? null : sc.orgRoles().get(orgId);
    return roles != null && roles.contains(OrgRole.OWNER);
  }

  /**
   * MANAGER or OWNER in the org (or system ADMIN) — gates a counter discount on an in-store sale.
   * Both roles are named explicitly: {@link #isOwnerOrAdmin} tests {@code contains(OWNER)} alone,
   * and an OWNER at the till must clear a MANAGER bar.
   */
  static boolean isManagerOrAdmin(SecurityContext sc, UUID orgId) {
    if (sc.isSystemAdmin()) {
      return true;
    }
    var roles = sc.orgRoles() == null ? null : sc.orgRoles().get(orgId);
    return roles != null && (roles.contains(OrgRole.MANAGER) || roles.contains(OrgRole.OWNER));
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
