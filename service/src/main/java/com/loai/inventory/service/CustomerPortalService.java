package com.loai.inventory.service;

import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.CustomerAddress;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.repository.CustomerAddressRepository;
import com.loai.inventory.domain.repository.CustomerAddressRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.FulfillmentRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepository.CheckoutLineResolution;
import com.loai.inventory.domain.repository.ProductListingRepository.ReorderResolution;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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

  /** Attributes portal-checkout inventory_log rows to the customer portal. */
  private static final ActorContext PORTAL_ACTOR = ActorContext.system("portal");

  /** Storefront locale to fall back to when an org has no {@code default_locale} set. */
  private static final String FALLBACK_LOCALE = "en";

  private final DSLContext rootDsl;
  private final CustomerRepositoryFactory customerRepositoryFactory;
  private final SalesOrderRepositoryFactory salesOrderRepositoryFactory;
  private final SalesInvoiceRepositoryFactory salesInvoiceRepositoryFactory;
  private final CustomerAddressRepositoryFactory customerAddressRepositoryFactory;
  private final ProductListingRepositoryFactory productListingRepositoryFactory;
  private final OrgRepositoryFactory orgRepositoryFactory;
  private final FulfillmentRepositoryFactory fulfillmentRepositoryFactory;
  private final SalesOrderService salesOrderService;

  public CustomerPortalService(
      DSLContext rootDsl,
      CustomerRepositoryFactory customerRepositoryFactory,
      SalesOrderRepositoryFactory salesOrderRepositoryFactory,
      SalesInvoiceRepositoryFactory salesInvoiceRepositoryFactory,
      CustomerAddressRepositoryFactory customerAddressRepositoryFactory,
      ProductListingRepositoryFactory productListingRepositoryFactory,
      OrgRepositoryFactory orgRepositoryFactory,
      FulfillmentRepositoryFactory fulfillmentRepositoryFactory,
      SalesOrderService salesOrderService) {
    this.rootDsl = rootDsl;
    this.customerRepositoryFactory = customerRepositoryFactory;
    this.salesOrderRepositoryFactory = salesOrderRepositoryFactory;
    this.salesInvoiceRepositoryFactory = salesInvoiceRepositoryFactory;
    this.customerAddressRepositoryFactory = customerAddressRepositoryFactory;
    this.productListingRepositoryFactory = productListingRepositoryFactory;
    this.orgRepositoryFactory = orgRepositoryFactory;
    this.fulfillmentRepositoryFactory = fulfillmentRepositoryFactory;
    this.salesOrderService = salesOrderService;
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

  /**
   * The portal order detail (slice R1 rider): the owned order + lines, plus which lines have goods
   * in hand ({@code deliveredLineIds} — any DELIVERED fulfillment quantity) and each line's public
   * listing slug ({@code listingSlugByProduct}, any status — a delivered item stays reviewable
   * after unpublishing). Both are customer-safe: the slug is the listing's public identity, and
   * delivered is the customer's own order state. Drives the per-line "rate this item" entry
   * (frontend story 40).
   */
  public record OrderDetail(
      SalesOrder order,
      List<SalesOrderLine> lines,
      java.util.Set<UUID> deliveredLineIds,
      Map<UUID, String> listingSlugByProduct) {}

  public OrderDetail getOrderDetail(UUID orgId, UUID customerId, String orderNumber) {
    OrderView view = getOrder(orgId, customerId, orderNumber);
    Map<UUID, Integer> deliveredByLine =
        fulfillmentRepositoryFactory
            .create(rootDsl)
            .sumDeliveredQtyByOrderLine(view.order().getId());
    java.util.Set<UUID> deliveredLineIds =
        view.lines().stream()
            .map(SalesOrderLine::getId)
            .filter(id -> deliveredByLine.getOrDefault(id, 0) > 0)
            .collect(java.util.stream.Collectors.toSet());
    Map<UUID, String> slugByProduct =
        productListingRepositoryFactory
            .create(rootDsl)
            .findSlugsByProductIds(
                orgId, view.lines().stream().map(SalesOrderLine::getProductId).toList());
    return new OrderDetail(view.order(), view.lines(), deliveredLineIds, slugByProduct);
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
        cfg ->
            insertAddress(
                customerAddressRepositoryFactory.create(DSL.using(cfg)), orgId, customerId, input));
  }

  /** The in-txn address insert shared by {@link #createAddress} and {@link #checkout}. */
  private static CustomerAddress insertAddress(
      CustomerAddressRepository repo, UUID orgId, UUID customerId, AddressInput input) {
    boolean makeDefault = input.makeDefault() || repo.countByCustomerId(orgId, customerId) == 0;
    if (makeDefault) {
      repo.clearDefault(orgId, customerId);
    }
    CustomerAddress a = new CustomerAddress();
    a.setOrgId(orgId);
    a.setCustomerId(customerId);
    a.setLabel(Text.normalizeText(input.label()));
    a.setRecipient(Text.normalizeText(input.recipient()));
    a.setPhone(Text.normalizeNumeric(input.phone()));
    a.setAddress(Text.normalizeText(input.address()));
    a.setDefault(makeDefault);
    return repo.insert(a);
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
          existing.setLabel(Text.normalizeText(input.label()));
          existing.setRecipient(Text.normalizeText(input.recipient()));
          existing.setPhone(Text.normalizeNumeric(input.phone()));
          existing.setAddress(Text.normalizeText(input.address()));
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

  // checkout (slice P6)

  /** One cart line at the authenticated checkout: a public listing slug + quantity. */
  public record CheckoutLine(String listingSlug, int quantity) {}

  /**
   * The authenticated checkout body. The customer is the session — no identity fields here. Exactly
   * one of {@code addressId} (a saved P4 book row, ownership-checked) or {@code address} (typed)
   * supplies the delivery contact; {@code saveAddress} persists a typed one to the book in the same
   * transaction as the placement.
   */
  public record CheckoutInput(
      List<CheckoutLine> lines,
      String notes,
      UUID addressId,
      AddressInput address,
      boolean saveAddress) {}

  /**
   * Place an order as the logged-in customer ({@code POST /api/portal/checkout} — slice P6, {@code
   * stories/portal_checkout.md}). The order is bound to the session's {@code (orgId, customerId)} —
   * never a body email — via {@link SalesOrderService#placeStorefrontOrderForCustomer}; slug →
   * {@code product_id} + {@code sales_price} resolution, reservation, per-org TTL, {@code
   * ORDER_PLACED} notifications and the emailed order-view magic link are all inherited from the
   * anonymous checkout core unchanged. The delivery contact comes from an owned saved address
   * (foreign/unknown id → the same opaque 404 as P4) or a typed one ({@code saveAddress} adds it to
   * the book inside the placement txn, so a failed placement saves nothing; an idempotent replay
   * doesn't re-add it). Returns the same customer-safe shape as the anonymous checkout, with {@code
   * trackUrl} pointing at the portal order page (reconstructable even on replay — no magic token
   * needed, the session is the capability).
   *
   * @throws StorefrontService.StorefrontOutOfStockException on a reservation shortage (slug-keyed)
   */
  public StorefrontService.CheckoutResult checkout(
      UUID orgId, UUID customerId, CheckoutInput input, String idempotencyKey) {
    if (input == null || input.lines() == null || input.lines().isEmpty()) {
      throw new ValidationException("lines must not be empty");
    }
    boolean hasSaved = input.addressId() != null;
    boolean hasTyped = input.address() != null;
    if (hasSaved == hasTyped) {
      throw new ValidationException("exactly one of address_id or address is required");
    }
    if (input.saveAddress() && !hasTyped) {
      throw new ValidationException("save_address requires a typed address");
    }
    SalesOrderService.DeliveryInput delivery;
    if (hasSaved) {
      CustomerAddress saved =
          customerAddressRepositoryFactory
              .create(rootDsl)
              .findById(orgId, customerId, input.addressId())
              .orElseThrow(() -> new NotFoundException("Address not found: " + input.addressId()));
      delivery =
          new SalesOrderService.DeliveryInput(
              saved.getRecipient(), saved.getPhone(), saved.getAddress());
    } else {
      validateAddress(input.address());
      delivery =
          new SalesOrderService.DeliveryInput(
              input.address().recipient(), input.address().phone(), input.address().address());
    }

    Org org =
        orgRepositoryFactory
            .create(rootDsl)
            .findById(orgId)
            .orElseThrow(() -> new NotFoundException("Org", orgId));

    // Resolve every cart slug → product_id + published sales_price, PUBLISHED-only, one query —
    // the same server-side resolution as the anonymous checkout (an unknown or unpublished slug is
    // an opaque 404, never saying which).
    LinkedHashSet<String> requestedSlugs = new LinkedHashSet<>();
    for (CheckoutLine line : input.lines()) {
      requestedSlugs.add(line.listingSlug());
    }
    ProductListingRepository listings = productListingRepositoryFactory.create(rootDsl);
    Map<String, CheckoutLineResolution> bySlug = new HashMap<>();
    for (CheckoutLineResolution r :
        listings.resolveForCheckout(orgId, requestedSlugs, ListingStatus.PUBLISHED)) {
      bySlug.put(r.slug(), r);
    }
    List<SalesOrderService.StorefrontLineInput> orderLines = new ArrayList<>(input.lines().size());
    Map<UUID, String> titleByProduct = new HashMap<>();
    Map<UUID, String> slugByProduct = new HashMap<>();
    for (CheckoutLine line : input.lines()) {
      CheckoutLineResolution res = bySlug.get(line.listingSlug());
      if (res == null) {
        throw new NotFoundException("Listing not available");
      }
      orderLines.add(
          new SalesOrderService.StorefrontLineInput(
              res.productId(), line.quantity(), res.salesPrice()));
      titleByProduct.put(res.productId(), res.title());
      slugByProduct.put(res.productId(), res.slug());
    }

    try {
      SalesOrderService.StorefrontPlaced placed =
          rootDsl.transactionResult(
              cfg -> {
                DSLContext txDsl = DSL.using(cfg);
                SalesOrderService.StorefrontPlaced p =
                    salesOrderService.placeStorefrontOrderForCustomer(
                        txDsl,
                        orgId,
                        customerId,
                        delivery,
                        orderLines,
                        idempotencyKey,
                        input.notes(),
                        PORTAL_ACTOR);
                // Save-to-book rides the placement txn: a shortage rolls it back with the order,
                // and an idempotent replay (created=false) never re-adds the row.
                if (input.saveAddress() && p.created()) {
                  insertAddress(
                      customerAddressRepositoryFactory.create(txDsl),
                      orgId,
                      customerId,
                      input.address());
                }
                return p;
              });
      String locale =
          org.getDefaultLocale() == null || org.getDefaultLocale().isBlank()
              ? FALLBACK_LOCALE
              : org.getDefaultLocale();
      // The portal order page, not the anonymous magic link — the session is the capability, so
      // the URL is reconstructable even on an idempotent replay.
      String trackUrl =
          "/" + locale + "/" + org.getSlug() + "/account/orders/" + placed.order().getOrderNumber();
      return new StorefrontService.CheckoutResult(
          placed.order(),
          placed.lines(),
          org.getPaymentInstructions(),
          trackUrl,
          placed.created(),
          titleByProduct);
    } catch (InsufficientStockException e) {
      // Re-key each product_id shortage to its listing slug + title — no internal id leaves here.
      List<StorefrontService.StorefrontShortage> shortages =
          e.getShortages().stream()
              .map(
                  s ->
                      new StorefrontService.StorefrontShortage(
                          slugByProduct.get(s.productId()),
                          titleByProduct.get(s.productId()),
                          s.requested(),
                          s.available()))
              .toList();
      throw new StorefrontService.StorefrontOutOfStockException(shortages);
    }
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
            existing.setName(Text.normalizeText(update.name()));
          }
          if (update.phone() != null) {
            existing.setPhone(Text.normalizeNumeric(update.phone()));
          }
          if (update.address() != null) {
            existing.setAddress(Text.normalizeText(update.address()));
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
}
