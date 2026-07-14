package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The logged-in customer's self-service profile ({@code GET|PATCH /api/portal/me}). Every read and
 * write is scoped to the session's own {@code (orgId, customerId)} — supplied by the {@code
 * CustomerAuthFilter} from the token, never from the URL or body — so a customer can only ever see
 * or change their own record, and never reach another org (epic decision #4). Email is deliberately
 * <em>not</em> patchable here: changing it would need a fresh ownership proof (a later slice).
 *
 * <p>It also serves the customer's own order history ({@code GET
 * /api/portal/orders[/{orderNumber}]} — slice P2, {@code stories/portal_order_reads.md}): a thin
 * {@code (orgId, customerId)}-scoped read over the same {@code SalesOrder} aggregate the staff
 * worklist uses, mapped by the servlet to the customer-safe {@code PublicOrderResponse} the public
 * tracker already ships. A single-order lookup asserts ownership and otherwise answers the same
 * opaque 404 as an unknown number — never an ownership oracle. See {@code
 * stories/portal_auth_core.md}.
 *
 * <p>It also serves the customer's own invoices ({@code GET /api/portal/invoices[/{id}]} — slice
 * P3, {@code stories/portal_invoices.md}): the same {@code (orgId, customerId)}-scoped read over
 * the already-issued {@link SalesInvoice} aggregate the staff worklist uses, VOID excluded (the
 * customer only ever sees live documents). A single-invoice lookup asserts ownership and otherwise
 * answers the same opaque 404 as an unknown id. The PDF itself is streamed by the servlet through
 * the shared {@code DocumentRenderService} once this ownership gate has passed.
 */
public class CustomerPortalService {

  private static final int MAX_FIELD_LENGTH = 500;

  /** Paging bounds for the portal order history — mirrors the staff order worklist. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  public static final int MAX_PAGE_SIZE = 100;

  private final DSLContext rootDsl;
  private final CustomerRepositoryFactory customerRepositoryFactory;
  private final SalesOrderRepositoryFactory salesOrderRepositoryFactory;
  private final SalesInvoiceRepositoryFactory salesInvoiceRepositoryFactory;

  public CustomerPortalService(
      DSLContext rootDsl,
      CustomerRepositoryFactory customerRepositoryFactory,
      SalesOrderRepositoryFactory salesOrderRepositoryFactory,
      SalesInvoiceRepositoryFactory salesInvoiceRepositoryFactory) {
    this.rootDsl = rootDsl;
    this.customerRepositoryFactory = customerRepositoryFactory;
    this.salesOrderRepositoryFactory = salesOrderRepositoryFactory;
    this.salesInvoiceRepositoryFactory = salesInvoiceRepositoryFactory;
  }

  /** One order + its lines — the shape the servlet maps to {@code PublicOrderResponse}. */
  public record OrderView(SalesOrder order, List<SalesOrderLine> lines) {}

  /** One page of the customer's orders + the total (drives the pager). */
  public record OrderPage(List<OrderView> items, long total) {}

  /**
   * The customer's own orders, newest first, paged. Strictly {@code (orgId, customerId)}-scoped —
   * both come from the session token, never a request param. Lines are batch-loaded (one query per
   * page, no N+1). A customer with no orders → an empty page.
   */
  public OrderPage listOrders(UUID orgId, UUID customerId, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    SalesOrderRepository repo = salesOrderRepositoryFactory.create(rootDsl);
    List<SalesOrder> orders = repo.findByCustomerId(orgId, customerId, p * s, s);
    long total = repo.countByCustomerId(orgId, customerId);
    Map<UUID, List<SalesOrderLine>> linesByOrder =
        repo.findLinesByOrderIds(orders.stream().map(SalesOrder::getId).toList());
    List<OrderView> items =
        orders.stream()
            .map(o -> new OrderView(o, linesByOrder.getOrDefault(o.getId(), List.of())))
            .toList();
    return new OrderPage(items, total);
  }

  /**
   * One of the customer's orders by its human-readable {@code orderNumber}. Resolves within the
   * session's org, then asserts {@code order.customer_id == customerId} — a foreign or unknown
   * number is the <em>same</em> opaque 404, so the endpoint is never an ownership oracle.
   */
  public OrderView getOrder(UUID orgId, UUID customerId, String orderNumber) {
    SalesOrderRepository repo = salesOrderRepositoryFactory.create(rootDsl);
    SalesOrder order =
        repo.findByOrderNumber(orgId, orderNumber)
            .filter(o -> customerId.equals(o.getCustomerId()))
            .orElseThrow(() -> new NotFoundException("Order not found: " + orderNumber));
    return new OrderView(order, repo.findLinesByOrderId(order.getId()));
  }

  // ── invoices (slice P3) ─────────────────────────────────────────────────────────

  /** One invoice + its lines — the shape the servlet maps to the customer-safe response. */
  public record InvoiceDetail(SalesInvoice invoice, List<SalesInvoiceLine> lines) {}

  /** One page of the customer's invoices (lean headers) + the total (drives the pager). */
  public record InvoicePage(List<SalesInvoice> items, long total) {}

  /**
   * The customer's own <em>live</em> invoices, newest first, paged. Strictly {@code (orgId,
   * customerId)}-scoped — both come from the session token, never a request param. VOID invoices
   * and walk-in receipts (null customer) never appear. A customer with none → an empty page.
   */
  public InvoicePage listInvoices(UUID orgId, UUID customerId, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    SalesInvoiceRepository repo = salesInvoiceRepositoryFactory.create(rootDsl);
    List<SalesInvoice> items = repo.findByCustomerId(orgId, customerId, p * s, s);
    long total = repo.countByCustomerId(orgId, customerId);
    return new InvoicePage(items, total);
  }

  /**
   * One of the customer's invoices by its stable {@code id}, with its lines. Asserts {@code
   * invoice.customer_id == customerId} and that it is not VOID — a foreign, unknown, or voided id
   * is the <em>same</em> opaque 404, so the endpoint is never an ownership oracle. Also the
   * ownership gate the {@code /{id}/pdf} route runs before streaming the rendered document.
   */
  public InvoiceDetail getInvoice(UUID orgId, UUID customerId, UUID invoiceId) {
    SalesInvoiceRepository repo = salesInvoiceRepositoryFactory.create(rootDsl);
    SalesInvoice invoice =
        repo.findById(orgId, invoiceId)
            .filter(i -> customerId.equals(i.getCustomerId()))
            .filter(i -> !i.isVoid())
            .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));
    return new InvoiceDetail(invoice, repo.findLinesByInvoiceId(invoiceId));
  }

  /** The patch body — any {@code null} field is left unchanged (merge). Email is not accepted. */
  public record ProfileUpdate(String name, String phone, String address) {}

  public Customer me(UUID orgId, UUID customerId) {
    return customerRepositoryFactory
        .create(rootDsl)
        .findById(orgId, customerId)
        .orElseThrow(() -> new NotFoundException("Customer", customerId));
  }

  /** Merge-update the caller's own name/phone/address; returns the updated profile. */
  public Customer updateProfile(UUID orgId, UUID customerId, ProfileUpdate update) {
    validate(update);
    return rootDsl.transactionResult(
        cfg -> {
          CustomerRepository repo = customerRepositoryFactory.create(DSL.using(cfg));
          Customer existing =
              repo.findById(orgId, customerId)
                  .orElseThrow(() -> new NotFoundException("Customer", customerId));
          if (update.name() != null) {
            existing.setName(trimToNull(update.name()));
          }
          if (update.phone() != null) {
            existing.setPhone(trimToNull(update.phone()));
          }
          if (update.address() != null) {
            existing.setAddress(trimToNull(update.address()));
          }
          // update() rewrites email too — but we pass the existing email untouched, so it is a
          // no-op
          // for email (the endpoint cannot change it).
          return repo.update(existing);
        });
  }

  private static void validate(ProfileUpdate update) {
    checkLength("name", update.name());
    checkLength("phone", update.phone());
    checkLength("address", update.address());
  }

  private static void checkLength(String field, String value) {
    if (value != null && value.length() > MAX_FIELD_LENGTH) {
      throw new ValidationException(field + " must be at most " + MAX_FIELD_LENGTH + " characters");
    }
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
