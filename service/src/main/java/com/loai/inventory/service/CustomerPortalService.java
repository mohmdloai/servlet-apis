package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.CustomerAddress;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.repository.CustomerAddressRepository;
import com.loai.inventory.domain.repository.CustomerAddressRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepository.ReorderResolution;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
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
  private final CustomerAddressRepositoryFactory customerAddressRepositoryFactory;
  private final ProductListingRepositoryFactory productListingRepositoryFactory;

  public CustomerPortalService(
      DSLContext rootDsl,
      CustomerRepositoryFactory customerRepositoryFactory,
      SalesOrderRepositoryFactory salesOrderRepositoryFactory,
      SalesInvoiceRepositoryFactory salesInvoiceRepositoryFactory,
      CustomerAddressRepositoryFactory customerAddressRepositoryFactory,
      ProductListingRepositoryFactory productListingRepositoryFactory) {
    this.rootDsl = rootDsl;
    this.customerRepositoryFactory = customerRepositoryFactory;
    this.salesOrderRepositoryFactory = salesOrderRepositoryFactory;
    this.salesInvoiceRepositoryFactory = salesInvoiceRepositoryFactory;
    this.customerAddressRepositoryFactory = customerAddressRepositoryFactory;
    this.productListingRepositoryFactory = productListingRepositoryFactory;
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

  // invoices (slice P3)

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

  // saved addresses (slice P4)

  /**
   * The write body for an address create/update. {@code address} is required; the rest are optional
   * label/contact fields. {@code makeDefault} promotes this address to the customer's default
   * (clearing the previous); create also auto-defaults the customer's very first address.
   */
  public record AddressInput(
      String label, String recipient, String phone, String address, boolean makeDefault) {}

  /** The customer's saved addresses — default first, then newest. */
  public List<CustomerAddress> listAddresses(UUID orgId, UUID customerId) {
    return customerAddressRepositoryFactory.create(rootDsl).findByCustomerId(orgId, customerId);
  }

  /**
   * Add a saved address for the caller. The first address a customer saves is made their default
   * automatically; otherwise {@code makeDefault} decides. Promoting a default clears the previous
   * one in the same transaction so the one-default invariant always holds.
   */
  public CustomerAddress createAddress(UUID orgId, UUID customerId, AddressInput input) {
    validateAddress(input);
    return rootDsl.transactionResult(
        cfg -> {
          CustomerAddressRepository repo = customerAddressRepositoryFactory.create(DSL.using(cfg));
          boolean makeDefault =
              input.makeDefault() || repo.countByCustomerId(orgId, customerId) == 0;
          if (makeDefault) {
            repo.clearDefault(orgId, customerId);
          }
          CustomerAddress a = new CustomerAddress();
          a.setOrgId(orgId);
          a.setCustomerId(customerId);
          a.setLabel(trimToNull(input.label()));
          a.setRecipient(trimToNull(input.recipient()));
          a.setPhone(trimToNull(input.phone()));
          a.setAddress(trimToNull(input.address()));
          a.setDefault(makeDefault);
          return repo.insert(a);
        });
  }

  /**
   * Merge-update the caller's own address content (label/recipient/phone/address). {@code
   * makeDefault} may promote it to the default; it never demotes (delete or promoting another
   * address are the other levers). A foreign or unknown id is an opaque 404.
   */
  public CustomerAddress updateAddress(
      UUID orgId, UUID customerId, UUID addressId, AddressInput input) {
    validateAddress(input);
    return rootDsl.transactionResult(
        cfg -> {
          CustomerAddressRepository repo = customerAddressRepositoryFactory.create(DSL.using(cfg));
          CustomerAddress existing =
              repo.findById(orgId, customerId, addressId)
                  .orElseThrow(() -> new NotFoundException("Address not found: " + addressId));
          existing.setLabel(trimToNull(input.label()));
          existing.setRecipient(trimToNull(input.recipient()));
          existing.setPhone(trimToNull(input.phone()));
          existing.setAddress(trimToNull(input.address()));
          CustomerAddress saved = repo.update(existing);
          if (input.makeDefault() && !saved.isDefault()) {
            repo.clearDefault(orgId, customerId);
            repo.setDefault(orgId, customerId, addressId);
            saved.setDefault(true);
          }
          return saved;
        });
  }

  /** Remove one of the caller's own addresses. A foreign or unknown id is an opaque 404. */
  public void deleteAddress(UUID orgId, UUID customerId, UUID addressId) {
    customerAddressRepositoryFactory.create(rootDsl).deleteById(orgId, customerId, addressId);
  }

  /**
   * Promote one of the caller's addresses to their default, clearing the previous default in the
   * same transaction (the DB partial unique index is the backstop). A foreign or unknown id is an
   * opaque 404. Returns the now-default address.
   */
  public CustomerAddress setDefaultAddress(UUID orgId, UUID customerId, UUID addressId) {
    return rootDsl.transactionResult(
        cfg -> {
          CustomerAddressRepository repo = customerAddressRepositoryFactory.create(DSL.using(cfg));
          CustomerAddress existing =
              repo.findById(orgId, customerId, addressId)
                  .orElseThrow(() -> new NotFoundException("Address not found: " + addressId));
          if (!existing.isDefault()) {
            repo.clearDefault(orgId, customerId);
            repo.setDefault(orgId, customerId, addressId);
          }
          existing.setDefault(true);
          return existing;
        });
  }

  private static void validateAddress(AddressInput input) {
    if (input.address() == null || input.address().isBlank()) {
      throw new ValidationException("address is required");
    }
    checkLength("label", input.label());
    checkLength("recipient", input.recipient());
    checkLength("phone", input.phone());
    checkLength("address", input.address());
  }

  // reorder (slice P4)

  /** One resolved, currently-buyable cart item from a past order line. */
  public record ReorderItem(
      String slug, String title, BigDecimal unitPrice, boolean inStock, int qty) {}

  /** A past order line whose product is no longer purchasable — reported, never added. */
  public record UnavailableItem(String description, int qty) {}

  /** The reorder resolution: buyable items to prefill the cart + the ones that dropped out. */
  public record ReorderResult(List<ReorderItem> items, List<UnavailableItem> unavailable) {}

  /**
   * Resolve one of the caller's past orders into a currently-buyable cart. Asserts ownership
   * exactly like {@link #getOrder} (a foreign or unknown number is the same opaque 404), then maps
   * each line's product back to its current PUBLISHED listing: a line with a live listing becomes a
   * {@link ReorderItem} (public slug + current price + advisory {@code in_stock}, at the original
   * quantity); a line whose product is no longer published drops to {@link UnavailableItem}. No
   * stock is reserved and no {@code product_id} ever crosses the boundary — the shopper re-runs the
   * normal checkout.
   */
  public ReorderResult reorder(UUID orgId, UUID customerId, String orderNumber) {
    SalesOrderRepository orderRepo = salesOrderRepositoryFactory.create(rootDsl);
    SalesOrder order =
        orderRepo
            .findByOrderNumber(orgId, orderNumber)
            .filter(o -> customerId.equals(o.getCustomerId()))
            .orElseThrow(() -> new NotFoundException("Order not found: " + orderNumber));
    List<SalesOrderLine> lines = orderRepo.findLinesByOrderId(order.getId());

    ProductListingRepository listingRepo = productListingRepositoryFactory.create(rootDsl);
    Map<UUID, ReorderResolution> byProduct =
        listingRepo
            .resolveForReorder(
                orgId,
                lines.stream().map(SalesOrderLine::getProductId).toList(),
                ListingStatus.PUBLISHED)
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ReorderResolution::productId, r -> r, (a, b) -> a));

    List<ReorderItem> items = new ArrayList<>();
    List<UnavailableItem> unavailable = new ArrayList<>();
    for (SalesOrderLine line : lines) {
      ReorderResolution r = byProduct.get(line.getProductId());
      if (r == null) {
        unavailable.add(new UnavailableItem(line.getDescription(), line.getQuantity()));
      } else {
        items.add(
            new ReorderItem(
                r.slug(), r.title(), r.salesPrice(), r.available() > 0, line.getQuantity()));
      }
    }
    return new ReorderResult(items, unavailable);
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
