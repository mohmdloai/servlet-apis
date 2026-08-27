package com.loai.inventory.service;

import com.loai.inventory.common.exception.ApprovalRequiredException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Locales;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.DiscountMath;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentAllocation;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PlatformFunnelStage;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.service.email.EmailAddresses;
import com.loai.inventory.service.email.EmailGate;
import com.loai.inventory.service.platform.OrgMilestoneService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sales-order placement for both channels.
 *
 * <ul>
 *   <li>{@link #placeOnlineOrder} — outbound flow TX-1 ({@code stories/place_online_order.md}):
 *       creates a SalesOrder + lines DRAFT → PENDING_PAYMENT and reserves stock, in one txn.
 *       Payment and fulfillment are later, separate transactions.
 *   <li>{@link #placeInStoreSale} — outbound Flow 1 ({@code stories/in_store_sale.md}): the whole
 *       sale — order, DELIVERED fulfillment, payment, issued+paid invoice, CLOSED order — in a
 *       single checkout txn, by composing {@link FulfillmentService}, {@link PaymentService} and
 *       {@link InvoiceService}. No reservation phase.
 * </ul>
 *
 * <p>The shared order-building core ({@link #buildDraftOrder}) is channel-agnostic; everything that
 * differs between the channels is the temporal sequence of transitions the two methods drive.
 */
public class SalesOrderService {

  private static final Logger log = LoggerFactory.getLogger(SalesOrderService.class);
  private static final String CURRENCY_EGP = "EGP";

  /**
   * Postgres-named unique index behind {@code UNIQUE (org_id, idempotency_key)} on {@code
   * sales_order} (V17) — the backstop that catches the placement race the read-then-insert
   * short-circuit cannot. Narrowed by name so a different integrity violation still bubbles.
   */
  private static final String ORDER_IDEMPOTENCY_CONSTRAINT =
      "sales_order_org_id_idempotency_key_key";

  private final DSLContext rootDsl;
  private final SalesOrderRepositoryFactory repoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final ReservationService reservationService;
  private final FulfillmentService fulfillmentService;
  private final PaymentService paymentService;
  private final InvoiceService invoiceService;
  private final RefundService refundService;
  private final NotificationService notificationService;
  private final MagicLinkService magicLinkService;
  private final EmailGate emailGate;
  private final CouponService couponService;
  private final OrgMilestoneService milestoneService;

  public SalesOrderService(
      DSLContext rootDsl,
      SalesOrderRepositoryFactory repoFactory,
      OrgRepositoryFactory orgRepoFactory,
      ReservationService reservationService,
      FulfillmentService fulfillmentService,
      PaymentService paymentService,
      InvoiceService invoiceService,
      RefundService refundService,
      NotificationService notificationService,
      MagicLinkService magicLinkService,
      EmailGate emailGate,
      CouponService couponService,
      OrgMilestoneService milestoneService) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.reservationService = reservationService;
    this.fulfillmentService = fulfillmentService;
    this.paymentService = paymentService;
    this.invoiceService = invoiceService;
    this.refundService = refundService;
    this.notificationService = notificationService;
    this.magicLinkService = magicLinkService;
    this.emailGate = emailGate;
    this.couponService = couponService;
    this.milestoneService = milestoneService;
  }

  /** Input contact info; {@code name} required, others optional. */
  public record CustomerInput(String name, String email, String phone, String address) {}

  /** Input order line; {@code quantity > 0}, product belongs to {@code orgId}. */
  public record OrderLineInput(UUID productId, int quantity) {}

  /**
   * Storefront order line: like {@link OrderLineInput} but carries the {@code unitPrice} the line
   * is snapshotted at — the resolved {@code product_listing.sales_price}, not {@code
   * product.base_price} — and (slice L2b) the {@code description} to snapshot: the locale-resolved
   * {@code product_listing.title} the shopper saw, so a bilingual customer's invoice/receipt line
   * reads in their checkout language rather than the internal {@code product.name}. A null {@code
   * description} falls back to the product-name snapshot (the online/in-store default). See {@code
   * stories/public_checkout.md} §Placement variant and {@code
   * stories/content_localization_2b_orderline_title.md}.
   */
  public record StorefrontLineInput(
      UUID productId, int quantity, BigDecimal unitPrice, String description) {}

  /**
   * The result of an anonymous storefront placement: the placed order + lines + customer, the
   * relative {@code trackUrl} (the anonymous order-view magic link minted in-txn; null on
   * idempotent replay, which can't reconstruct the raw token), and {@code created} — true for a
   * fresh order (201), false when a duplicate {@code Idempotency-Key} replayed a prior order (200).
   */
  public record StorefrontPlaced(
      SalesOrder order,
      List<SalesOrderLine> lines,
      Customer customer,
      String trackUrl,
      boolean created) {}

  /**
   * Internal unified line — optional {@code unitPriceOverride} + {@code descriptionOverride}
   * (storefront) or null (online/in-store, which snapshot {@code product.base_price} / {@code
   * product.name}).
   */
  private record ResolvedLine(
      UUID productId, int quantity, BigDecimal unitPriceOverride, String descriptionOverride) {}

  /**
   * Where this parcel goes — slice P6, {@code stories/portal_checkout.md}. Frozen onto the
   * <b>order</b> at placement (V80, {@code sales_order.delivery_*}), never onto the customer: it is
   * per-order data, and the recipient may be someone other than the buyer.
   *
   * <p>The identity is never taken from here — the customer is the session. Until V80 this
   * <em>was</em> merged onto the CRM row, which meant shipping a gift rewrote the buyer's own name
   * and phone; see {@code resolveKnownCustomer}.
   */
  public record DeliveryInput(String recipient, String phone, String address) {}

  /** Resolves the order's customer inside the placement txn (anon email-upsert vs known id). */
  private interface CustomerResolver {
    Customer resolve(SalesOrderRepository repo);
  }

  /** Internal placement outcome carried out of the shared reservation txn. */
  private record PlacementResult(
      SalesOrder order,
      List<SalesOrderLine> lines,
      Customer customer,
      String trackUrl,
      boolean created) {}

  /**
   * Cashier tender for an in-store sale. {@code amount} is optional (defaults to the grand total).
   */
  public record PaymentInput(PaymentProvider provider, String providerRef, BigDecimal amount) {}

  /**
   * A counter discount keyed by a manager on an in-store sale ({@code
   * stories/counter_discount.md}): {@code type} and {@code value} carry the coupon's two meanings
   * (a rate in (0, 100] for {@code PERCENT}, an EGP amount for {@code FIXED}); {@code reason} is
   * optional free text. Absent entirely on the ordinary sale.
   */
  public record DiscountInput(CouponType type, BigDecimal value, String reason) {}

  /** Longest reason a manager may attach to a counter discount. */
  public static final int COUNTER_DISCOUNT_REASON_MAX = 200;

  /** Carries the placed order + its lines + the resolved customer for response mapping. */
  public record Placed(SalesOrder order, List<SalesOrderLine> lines, Customer customer) {}

  /**
   * The full result of an in-store sale: every aggregate created in the one checkout txn. {@code
   * changeRefund} is the EXECUTED cash refund of the tender's excess over the grand total — null
   * for an exact tender.
   */
  public record InStoreSale(
      SalesOrder order,
      List<SalesOrderLine> lines,
      Fulfillment fulfillment,
      SalesInvoice invoice,
      List<SalesInvoiceLine> invoiceLines,
      Payment payment,
      List<PaymentAllocation> allocations,
      Refund changeRefund) {}

  public Placed placeOnlineOrder(
      UUID orgId,
      CustomerInput customer,
      List<OrderLineInput> lines,
      String idempotencyKey,
      String notes,
      ActorContext actor) {

    validateOnlineInputs(customer, lines);
    List<ResolvedLine> resolved =
        lines.stream().map(l -> new ResolvedLine(l.productId(), l.quantity(), null, null)).toList();
    // Admin-placed phone orders take no coupon in v1 (the same call as the in-store POS: a
    // staff-granted discount is a different authority question — documented in the slice).
    PlacementResult r =
        placeReserved(orgId, customer, resolved, idempotencyKey, notes, null, null, actor);
    return new Placed(r.order(), r.lines(), r.customer());
  }

  /**
   * Anonymous storefront placement ({@code stories/public_checkout.md}, B5). Identical to {@link
   * #placeOnlineOrder} — same {@code ONLINE} channel, customer upsert, order-number claim, per-org
   * TTL {@code expires_at}, stock reservation, {@code ORDER_PLACED} notifications, and order-view
   * magic link — except each line's {@code unit_price} is snapshotted from the caller-supplied
   * {@code product_listing.sales_price} (what the shopper saw), never {@code product.base_price}.
   * Returns the {@code trackUrl} (the anonymous order-view link) and whether the order was freshly
   * created vs an idempotent replay.
   */
  public StorefrontPlaced placeStorefrontOrder(
      UUID orgId,
      CustomerInput customer,
      List<StorefrontLineInput> lines,
      String idempotencyKey,
      String notes,
      String couponCode,
      String locale,
      ActorContext actor) {

    validateOnlineInputs(customer, toOrderLineInputs(lines));
    List<ResolvedLine> resolved =
        lines.stream()
            .map(l -> new ResolvedLine(l.productId(), l.quantity(), l.unitPrice(), l.description()))
            .toList();
    PlacementResult r =
        placeReserved(orgId, customer, resolved, idempotencyKey, notes, couponCode, locale, actor);
    return new StorefrontPlaced(r.order(), r.lines(), r.customer(), r.trackUrl(), r.created());
  }

  /**
   * Known-customer storefront placement — the authenticated portal checkout's placement variant
   * (slice P6, {@code stories/portal_checkout.md}). Identical to {@link #placeStorefrontOrder}
   * (same reservation, per-org TTL, {@code ORDER_PLACED} notifications, order-view magic link,
   * idempotent replay) except the customer is loaded by the session's {@code (orgId, customerId)}
   * and never resolved from a body email — the order is theirs by construction. The {@code
   * delivery} contact is frozen onto the CRM row through the same coalesce upsert the anonymous
   * form uses, keyed by the loaded row's own email.
   *
   * <p>Runs inside the caller's transaction ({@code txDsl}) so the caller can compose same-txn
   * side-effects (the portal's optional save-to-address-book) that roll back with a failed
   * placement — the same composition pattern as {@link FulfillmentService#createDelivered}.
   */
  public StorefrontPlaced placeStorefrontOrderForCustomer(
      DSLContext txDsl,
      UUID orgId,
      UUID customerId,
      DeliveryInput delivery,
      List<StorefrontLineInput> lines,
      String idempotencyKey,
      String notes,
      String couponCode,
      ActorContext actor) {

    validateLines(toOrderLineInputs(lines));
    List<ResolvedLine> resolved =
        lines.stream()
            .map(l -> new ResolvedLine(l.productId(), l.quantity(), l.unitPrice(), l.description()))
            .toList();
    PlacementResult r =
        placeReservedInTx(
            txDsl,
            orgId,
            repo -> resolveKnownCustomer(repo, orgId, customerId),
            delivery,
            resolved,
            idempotencyKey,
            notes,
            couponCode,
            actor);
    return new StorefrontPlaced(r.order(), r.lines(), r.customer(), r.trackUrl(), r.created());
  }

  /**
   * The shared placement + reservation transaction behind both {@link #placeOnlineOrder} and {@link
   * #placeStorefrontOrder}: idempotency short-circuit, build DRAFT → PENDING_PAYMENT with the org's
   * TTL, insert, reserve stock, notify staff, and mint the customer's order-view magic link — all
   * in one txn, so a shortage or any failure rolls the whole thing back.
   */
  private PlacementResult placeReserved(
      UUID orgId,
      CustomerInput customer,
      List<ResolvedLine> lines,
      String idempotencyKey,
      String notes,
      String couponCode,
      String locale,
      ActorContext actor) {

    return rootDsl.transactionResult(
        cfg ->
            placeReservedInTx(
                DSL.using(cfg),
                orgId,
                repo -> resolveCustomer(repo, orgId, customer, true, locale),
                // On the anonymous form the single contact block IS both the buyer's identity and
                // the delivery contact — there is no separate address input — so the order's
                // snapshot is that same block. The portal path supplies a distinct one.
                customer == null
                    ? null
                    : new DeliveryInput(customer.name(), customer.phone(), customer.address()),
                lines,
                idempotencyKey,
                notes,
                couponCode,
                actor));
  }

  /** The body of {@link #placeReserved}, runnable inside a caller-owned transaction. */
  private PlacementResult placeReservedInTx(
      DSLContext txDsl,
      UUID orgId,
      CustomerResolver customerResolver,
      DeliveryInput delivery,
      List<ResolvedLine> lines,
      String idempotencyKey,
      String notes,
      String couponCode,
      ActorContext actor) {
    SalesOrderRepository repo = repoFactory.create(txDsl);

    // 1. Idempotency short-circuit — a duplicate key replays the prior order (200), no second
    // reservation, no raw token to reconstruct (trackUrl null).
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      var existing = repo.findByIdempotencyKey(orgId, idempotencyKey);
      if (existing.isPresent()) {
        return replayOf(repo, orgId, existing.get());
      }
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // The org row is read inside this txn and drives both the money config (tax rate + shipping
    // fee, V68) applied at build time and the payment-hold window (reservation.md §Default TTL,
    // "Configurable per-org" — V47, default 1440 min) stamped below. The TTL sweeper keys off
    // expires_at alone.
    Org org =
        orgRepoFactory
            .create(txDsl)
            .findById(orgId)
            .orElseThrow(() -> new NotFoundException("Org", orgId));

    // 2. Build + insert, behind a SAVEPOINT.
    //
    // The short-circuit above is a read, and reads don't serialize: two genuinely-concurrent
    // submits carrying the SAME Idempotency-Key both see "absent", and the loser meets the
    // (org_id, idempotency_key) UNIQUE on insert. That used to surface as an uncaught
    // DataAccessException — a 500 for a shopper whose double-tap the header exists to make safe.
    // The savepoint is what makes recovery possible at all: a constraint violation poisons the
    // whole Postgres transaction, so without one there is nothing left to read the winner's order
    // with (and the outer transaction may not even be ours — the portal checkout owns it).
    // Rolling back to it also un-claims the order number, so a race leaves no gap in the sequence.
    BuiltOrder built;
    try {
      built =
          txDsl.transactionResult(
              nested -> {
                BuiltOrder b =
                    buildDraftOrder(
                        txDsl,
                        repo,
                        org,
                        OrderChannel.ONLINE,
                        customerResolver.resolve(repo),
                        lines,
                        idempotencyKey,
                        notes,
                        couponCode,
                        now);
                OffsetDateTime expires = now.plus(Duration.ofMinutes(org.getOrderTtlMinutes()));
                b.order().markPendingPayment(now, expires);
                // V80: freeze WHERE THIS PARCEL GOES onto the order, before the insert. This used
                // to be merged onto the customer row and read back off it by InvoiceService, which
                // meant a gift order overwrote the buyer's own name and phone — and since V79 that
                // phone is the identity a notification channel dials.
                if (delivery != null) {
                  b.order()
                      .setDeliveryContact(
                          Text.normalizeText(delivery.recipient()),
                          Text.normalizeNumeric(delivery.phone()),
                          Text.normalizeText(delivery.address()));
                }
                repo.insert(b.order(), b.lines());
                return b;
              });
    } catch (DataAccessException e) {
      if (!NumberSequenceConflicts.isUniqueViolationOn(e, ORDER_IDEMPOTENCY_CONSTRAINT)) {
        throw e;
      }
      // The other submit won and has committed (that is *why* we saw the violation), so this read
      // — taken after the savepoint rollback, on a fresh statement snapshot — finds its order.
      // Both callers therefore resolve to the same order: one 201, one 200, never a 500.
      SalesOrder winner =
          repo.findByIdempotencyKey(orgId, idempotencyKey)
              .orElseThrow(
                  () ->
                      new ConflictException(
                          "duplicate order submission for idempotency key " + idempotencyKey));
      log.info(
          "Concurrent duplicate placement resolved as a replay: order id={} number={} key={}",
          winner.getId(),
          winner.getOrderNumber(),
          idempotencyKey);
      return replayOf(repo, orgId, winner);
    }
    SalesOrder order = built.order();
    List<SalesOrderLine> orderLines = built.lines();
    // FIRST_ORDER — the order row exists now, in this txn (online + storefront placement share
    // this method). A rolled-back placement (e.g. the shortage check below) leaves no stamp.
    milestoneService.reach(txDsl, orgId, PlatformFunnelStage.FIRST_ORDER, now);

    // Throws InsufficientStockException → rolls back the whole placement.
    reservationService.reserveForOrder(txDsl, orgId, order, orderLines, actor);

    // Notify org staff — inside the placement txn, so a rolled-back order sends nothing.
    notificationService.notifyOrgStaff(
        txDsl,
        orgId,
        NotificationType.ORDER_PLACED,
        Map.of("order_number", order.getOrderNumber()),
        "sales_order",
        order.getId(),
        "/orgs/" + orgId + "/sales-orders/" + order.getId());

    // Notify the customer by email, carrying an order-scoped magic link (view your order, no
    // login). Both the token and the notification are written in this txn, so a rolled-back
    // order leaves neither. Online/storefront placement always resolves a customer (email
    // required). The same raw link is returned as the response's track_url.
    Customer resolvedCustomer = built.customer();
    String trackUrl = null;
    if (resolvedCustomer != null) {
      MagicLinkService.OrderViewLink viewLink =
          magicLinkService.issueOrderViewLink(
              txDsl, orgId, resolvedCustomer.getId(), order.getId(), now);
      trackUrl = viewLink.relative();
      notificationService.notify(
          txDsl,
          orgId,
          com.loai.inventory.domain.model.NotificationRecipient.customer(resolvedCustomer.getId()),
          NotificationType.ORDER_PLACED,
          Map.of("order_number", order.getOrderNumber()),
          "sales_order",
          order.getId(),
          viewLink.absolute());
    }

    log.info(
        "Placed online order id={} orgId={} number={} customerId={} grandTotal={} lines={}",
        order.getId(),
        orgId,
        order.getOrderNumber(),
        built.customer() == null ? null : built.customer().getId(),
        order.getGrandTotal(),
        orderLines.size());

    return new PlacementResult(order, orderLines, built.customer(), trackUrl, true);
  }

  /**
   * The replay answer for an {@code Idempotency-Key} that already has an order: the prior order and
   * its lines, {@code created=false} (the servlet turns that into a 200), and no {@code trackUrl} —
   * the magic-link token was minted once and its raw value is not reconstructable.
   */
  private PlacementResult replayOf(SalesOrderRepository repo, UUID orgId, SalesOrder prior) {
    List<SalesOrderLine> priorLines = repo.findLinesByOrderId(prior.getId());
    Customer priorCustomer = loadCustomerOrThrow(repo, orgId, prior.getCustomerId());
    log.info(
        "Idempotent replay: returning existing order id={} number={}",
        prior.getId(),
        prior.getOrderNumber());
    return new PlacementResult(prior, priorLines, priorCustomer, null, false);
  }

  private static List<OrderLineInput> toOrderLineInputs(List<StorefrontLineInput> lines) {
    if (lines == null) {
      return null;
    }
    return lines.stream()
        .map(l -> l == null ? null : new OrderLineInput(l.productId(), l.quantity()))
        .toList();
  }

  /**
   * In-store sale (channel IN_STORE) — the whole transaction in one go (outbound Flow 1). The order
   * is driven DRAFT → PAID → CLOSED while, in causal order, the money is taken before the goods are
   * released: payment recorded → order PAID → DELIVERED fulfillment (stock decremented) → invoice
   * issued + auto-allocated → order CLOSED. (The invoice can only follow the fulfillment because
   * {@code sales_invoice.fulfillment_id} is NOT NULL.) Any failure (out of stock, bad tender) rolls
   * everything back.
   *
   * <p>Double-submit is blocked on two independent layers: the order's {@code (org_id,
   * idempotency_key)} UNIQUE (this method short-circuits a replay before doing any work) and the
   * payment transaction's {@code (provider, provider_ref)} UNIQUE (the cash ref is derived from the
   * same idempotency key — see {@link PaymentService#recordInStorePayment}).
   *
   * <p><b>Counter discount (V88).</b> An optional {@code discount} takes a PERCENT or FIXED amount
   * off the whole ticket through {@link DiscountMath} — the coupon's arithmetic, so a "10% off" at
   * the till and a {@code SAVE10} code produce the same piastres. It is accepted only when {@code
   * callerIsManagerOrAdmin}: the sale stays a STAFF action, the discount block alone raises the bar
   * — the codebase's one money-authority pattern ({@code refund_approval_threshold} → OWNER),
   * decided here in the service (not the handler) so no other caller can dodge it, and refused as
   * {@link ApprovalRequiredException} so the client can say "needs MANAGER" instead of "no
   * permission". {@code verifiedBy} — the calling user — is also recorded as the grantor.
   */
  public InStoreSale placeInStoreSale(
      UUID orgId,
      CustomerInput customer,
      List<OrderLineInput> lines,
      PaymentInput payment,
      DiscountInput discount,
      String notes,
      ActorContext actor,
      String idempotencyKey,
      UUID verifiedBy,
      boolean callerIsManagerOrAdmin) {

    validateInStoreInputs(lines, payment, discount);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          SalesOrderRepository repo = repoFactory.create(txDsl);

          // 0. Idempotency short-circuit (barrier 1). A retried checkout must not double-charge. We
          // don't reconstruct the full prior aggregate the way the online replay returns its order
          // —
          // reading fulfillment/invoice/payment/allocations back lands with the query slice — so a
          // retry gets a clean 409. The (org_id, idempotency_key) UNIQUE on insert is the backstop
          // behind this check.
          if (idempotencyKey != null
              && !idempotencyKey.isBlank()
              && repo.findByIdempotencyKey(orgId, idempotencyKey).isPresent()) {
            throw new ConflictException(
                "in-store sale already recorded for idempotency key " + idempotencyKey);
          }

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          // 1. Build + persist the DRAFT order (customer optional for walk-in). The idempotency key
          // rides on the order row so the UNIQUE above is what actually enforces barrier 1. The org
          // row supplies the money config (tax rate; shipping never applies in-store — V68).
          Org inStoreOrg =
              orgRepoFactory
                  .create(txDsl)
                  .findById(orgId)
                  .orElseThrow(() -> new NotFoundException("Org", orgId));
          BuiltOrder built =
              buildDraftOrder(
                  txDsl,
                  repo,
                  inStoreOrg,
                  OrderChannel.IN_STORE,
                  // In-store: the counter has no storefront locale, so a walk-in customer's
                  // language stays unlearned and their notifications follow the org default.
                  resolveCustomer(repo, orgId, customer, false, null),
                  lines.stream()
                      .map(l -> new ResolvedLine(l.productId(), l.quantity(), null, null))
                      .toList(),
                  idempotencyKey,
                  notes,
                  // The in-store POS takes no coupon in v1: a counter discount is a different
                  // product decision (who may grant it, and against which authority), and the code
                  // machinery here is built for a shopper typing their own.
                  null,
                  now);
          SalesOrder order = built.order();
          List<SalesOrderLine> orderLines = built.lines();
          // Walk-in contact (V87): a counter sale with NO email resolves no CRM row, so the name
          // and phone the cashier typed used to be dropped on the floor. Freeze them on the order —
          // not on delivery_* (nothing is delivered; V80's phone is what a courier dials) and not
          // on customer (identity stays email-keyed). Never written when the email path resolved a
          // Customer: that row is the source of truth and the snapshot stays null. Same
          // normalisation the CRM upsert applies, so an Arabic-keyboard phone stores the same row.
          if (built.customer() == null && customer != null) {
            order.setWalkInContact(
                Text.normalizeText(customer.name()), Text.normalizeNumeric(customer.phone()));
          }
          if (discount != null) {
            applyCounterDiscount(order, discount, callerIsManagerOrAdmin, verifiedBy, now);
          }
          repo.insert(order, orderLines);
          // FIRST_ORDER — same helper, same rule as online/storefront placement: the row exists
          // now, in this txn, so a rolled-back sale (bad tender, out of stock) leaves no stamp.
          milestoneService.reach(txDsl, orgId, PlatformFunnelStage.FIRST_ORDER, now);

          // Tender rules: overpaid is accepted (the customer hands a round amount, the excess is
          // returned as counter change — payment.md §Overpaid (in-store)); underpaid is rejected —
          // v1 releases goods only against full payment (salesOrder.md edge case "pay full or
          // cancel"; fulfillment.md: SalesOrder must be PAID before goods move). Partial-accept
          // (write off the shortfall) is a MANAGER CreditNote decision, not a STAFF checkout path.
          BigDecimal grandTotal = order.getGrandTotal();
          BigDecimal tender = payment.amount() == null ? grandTotal : payment.amount();
          if (tender.compareTo(grandTotal) < 0) {
            throw new ValidationException(
                "in-store tender "
                    + tender
                    + " is less than grand total "
                    + grandTotal
                    + " — underpaid sales are not accepted (v1: pay full or cancel)");
          }
          BigDecimal change = tender.subtract(grandTotal);

          // 2. Take the money: payment recorded for the FULL tender, VERIFIED + MATCHED, RECEIVED.
          Payment paymentRow =
              paymentService.recordInStorePayment(
                  txDsl,
                  orgId,
                  order,
                  payment.provider(),
                  payment.providerRef(),
                  tender,
                  verifiedBy,
                  now);

          // 3. Order DRAFT → PAID — paid before any goods move. prepaid_amount is the NET money
          // attached to the order (SUM(payments) − SUM(executed refunds)); the change refund
          // executes before this transaction commits, so the net is the grand total.
          order.markPaid(grandTotal, now);
          repo.updatePaymentState(order);

          // 4. Release the goods: fulfillment created DELIVERED, stock decremented now (no
          // reservation). Done after PAID so stock never leaves the shelf for an unpaid order.
          List<FulfillmentService.DeliveredLineInput> deliveredLines =
              new ArrayList<>(orderLines.size());
          for (SalesOrderLine l : orderLines) {
            deliveredLines.add(
                new FulfillmentService.DeliveredLineInput(
                    l.getId(), l.getProductId(), l.getQuantity()));
          }
          FulfillmentService.FulfillmentView fulfillment =
              fulfillmentService.createDelivered(
                  txDsl, orgId, order, deliveredLines, null, null, null, actor, now);

          // 5. Invoice ISSUED + auto-allocation consumes the just-created payment → invoice PAID.
          List<InvoiceService.LineSpec> specs = new ArrayList<>(orderLines.size());
          for (SalesOrderLine l : orderLines) {
            specs.add(
                new InvoiceService.LineSpec(
                    l.getProductId(),
                    l.getDescription(),
                    l.getQuantity(),
                    l.getUnitPrice(),
                    l.getTaxRate()));
          }
          InvoiceService.Issued issued =
              invoiceService.issueForFulfillment(
                  txDsl,
                  orgId,
                  order,
                  fulfillment.fulfillment().getId(),
                  built.customer(),
                  specs,
                  now);

          // 6. Order PAID → CLOSED.
          order.closeInStore(now);
          repo.updateFulfillmentState(order);

          // The FIFO allocator mutated its own copy of the payment; surface that rather than the
          // as-created RECEIVED instance. The sale has exactly one same-txn payment — guard the
          // invariant so a future multi-tender slice can't silently pick the wrong one.
          if (issued.consumedPayments().size() > 1) {
            throw new IllegalStateException(
                "in-store sale expected at most one consumed payment, got "
                    + issued.consumedPayments().size());
          }
          Payment finalPayment =
              issued.consumedPayments().isEmpty() ? paymentRow : issued.consumedPayments().get(0);

          // 7. Counter change: the allocator consumed the invoice total, leaving the excess as the
          // payment's unallocated balance — hand it straight back as an EXECUTED cash refund
          // (payment.md §Overpaid (in-store) steps 4–5). Payment ends ALLOCATED, unallocated 0.
          RefundService.Executed changeRefund = null;
          if (change.signum() > 0) {
            changeRefund =
                refundService.createExecutedChangeInTx(
                    txDsl, orgId, finalPayment, change, verifiedBy, now);
          }

          log.info(
              "In-store sale order id={} orgId={} number={} grandTotal={} tender={} change={} invoice={} payment={} status={}",
              order.getId(),
              orgId,
              order.getOrderNumber(),
              order.getGrandTotal(),
              tender,
              change,
              issued.invoice().getInvoiceNumber(),
              finalPayment.getStatus(),
              order.getStatus());

          return new InStoreSale(
              order,
              orderLines,
              fulfillment.fulfillment(),
              issued.invoice(),
              issued.lines(),
              finalPayment,
              issued.allocations(),
              changeRefund == null ? null : changeRefund.refund());
        });
  }

  /**
   * Look up an order by its human-readable number — the pre-flight for the manual money path
   * ({@code stories/lookup_order_by_number.md}): the admin previews status / outstanding balance
   * before recording or resolving a transaction against the number. Exact, case-sensitive match
   * after trimming (numbers arrive by copy-paste from transfer notes). Read-only on {@code
   * rootDsl}, no lock — the money endpoints re-read {@code FOR UPDATE} inside their own
   * transactions, so a stale preview can never corrupt a write.
   *
   * @throws ValidationException on a missing/blank number
   * @throws NotFoundException if no order with that number exists in {@code orgId}
   */
  public Placed getByNumber(UUID orgId, String orderNumber) {
    if (orderNumber == null || orderNumber.isBlank()) {
      throw new ValidationException("order_number is required");
    }
    String number = orderNumber.trim();
    SalesOrderRepository repo = repoFactory.create(rootDsl);
    SalesOrder order =
        repo.findByOrderNumber(orgId, number)
            .orElseThrow(() -> new NotFoundException("SalesOrder not found: " + number));
    return new Placed(order, repo.findLinesByOrderId(order.getId()), null);
  }

  /**
   * Look up an order by id — the detail read behind the worklist row ({@code
   * stories/fulfillment_reads.md}). Same response shape as {@link #getByNumber}, keyed by the
   * stable id instead of the human-readable number. Read-only on {@code rootDsl}, no lock.
   *
   * @throws NotFoundException if the order is not in {@code orgId}
   */
  public Placed getById(UUID orgId, UUID orderId) {
    if (orderId == null) {
      throw new ValidationException("order id is required");
    }
    return findPlaced(orgId, orderId)
        .orElseThrow(() -> new NotFoundException("SalesOrder", orderId));
  }

  /**
   * Load an order + its lines + customer for a read-only view (the anonymous magic-link route). The
   * caller has already proven access via the token, so this takes no {@link ActorContext}; it is
   * still org-scoped. Returns empty if the order does not exist in {@code orgId}.
   */
  public Optional<Placed> findPlaced(UUID orgId, UUID orderId) {
    SalesOrderRepository repo = repoFactory.create(rootDsl);
    return repo.findById(orgId, orderId)
        .map(
            order -> {
              List<SalesOrderLine> lines = repo.findLinesByOrderId(order.getId());
              Customer customer =
                  order.getCustomerId() == null
                      ? null
                      : repo.findCustomerById(orgId, order.getCustomerId()).orElse(null);
              return new Placed(order, lines, customer);
            });
  }

  /** Paging bounds for the order worklist — mirrors the other worklists. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  public static final int MAX_PAGE_SIZE = 100;

  /**
   * One page of the order worklist ({@link Placed} rows carry the order + its lines) + the total.
   */
  public record OrderListPage(List<Placed> items, long total) {}

  /**
   * The order worklist ({@code GET /sales-orders?status=&page=&size=}): filtered by {@code status}
   * it is a queue (oldest first); unfiltered it is the ledger (newest first). Lines are
   * batch-loaded (one query per page). Customer is not resolved — the list DTO doesn't carry it.
   * Read-only on {@code rootDsl}.
   */
  public OrderListPage list(UUID orgId, OrderStatus status, int page, int size) {
    return list(orgId, status, null, page, size);
  }

  /**
   * {@link #list} narrowed by {@code channel} too ({@code ?channel=IN_STORE&status=CLOSED} is the
   * day's counter sales — the third way a cashier finds a receipt, {@code
   * stories/counter_return.md}). Ordering stays keyed on {@code status}.
   */
  public OrderListPage list(
      UUID orgId, OrderStatus status, OrderChannel channel, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    SalesOrderRepository repo = repoFactory.create(rootDsl);
    List<SalesOrder> orders = repo.list(orgId, status, channel, p * s, s);
    long total = repo.count(orgId, status, channel);
    Map<UUID, List<SalesOrderLine>> linesByOrder =
        repo.findLinesByOrderIds(orders.stream().map(SalesOrder::getId).toList());
    List<Placed> items =
        orders.stream()
            .map(o -> new Placed(o, linesByOrder.getOrDefault(o.getId(), List.of()), null))
            .toList();
    return new OrderListPage(items, total);
  }

  /**
   * The worklist tabs' numbers ({@code GET /sales-orders/status-counts}, {@code
   * stories/order_status_counts.md}): every {@link OrderStatus} present — {@code 0} included, never
   * omitted ("nobody here" is data, the funnel's {@code reached: 0} rule) — plus {@code total}, the
   * unfiltered ledger count (== Σ counts by construction: the same rows partitioned by status).
   * Read-only on {@code rootDsl}.
   */
  public record OrderStatusCounts(Map<OrderStatus, Long> counts, long total) {}

  public OrderStatusCounts statusCounts(UUID orgId) {
    Map<OrderStatus, Long> raw = repoFactory.create(rootDsl).countByStatus(orgId);
    Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
    long total = 0;
    for (OrderStatus status : OrderStatus.values()) {
      long n = raw.getOrDefault(status, 0L);
      counts.put(status, n);
      total += n;
    }
    return new OrderStatusCounts(counts, total);
  }

  // Shared build

  private record BuiltOrder(SalesOrder order, List<SalesOrderLine> lines, Customer customer) {}

  /**
   * Build (but do not persist) a DRAFT {@link SalesOrder} + its lines: snapshot each product,
   * compute line + order totals and claim the per-org per-year order number. The caller resolves
   * the customer first ({@link #resolveCustomer} or {@link #resolveKnownCustomer}; null for an
   * in-store walk-in) and drives the channel-specific transitions and the insert.
   *
   * <p>Money config (V68, roadmap item 5) comes from the passed {@code org}: every line is taxed at
   * {@code org.tax_rate}, and non-IN_STORE orders carry {@code org.shipping_fee} as a scalar {@code
   * shipping_total} (never a synthetic order line — the reservation engine iterates lines, and a
   * product-less line cannot exist). An unconfigured org (both 0) reproduces the historic math.
   */
  private BuiltOrder buildDraftOrder(
      DSLContext txDsl,
      SalesOrderRepository repo,
      Org org,
      OrderChannel channel,
      Customer resolvedCustomer,
      List<ResolvedLine> lines,
      String idempotencyKey,
      String notes,
      String couponCode,
      OffsetDateTime now) {
    UUID orgId = org.getId();

    // Snapshot product data per line; fail if any product is missing for this org.
    List<UUID> productIds = lines.stream().map(ResolvedLine::productId).toList();
    Map<UUID, SalesOrderRepository.ProductSnapshot> snapshots =
        repo.fetchProductSnapshots(orgId, productIds);
    for (UUID pid : productIds) {
      if (!snapshots.containsKey(pid)) {
        throw new NotFoundException("Product", pid);
      }
    }

    UUID orderId = UUID.randomUUID();
    List<SalesOrderLine> orderLines = new ArrayList<>(lines.size());
    BigDecimal subtotal = BigDecimal.ZERO;
    BigDecimal taxTotal = BigDecimal.ZERO;
    for (ResolvedLine in : lines) {
      SalesOrderRepository.ProductSnapshot snap = snapshots.get(in.productId());
      // Storefront lines carry a unit-price override (the published sales_price the shopper saw);
      // online/in-store lines snapshot product.base_price.
      BigDecimal unitPrice =
          in.unitPriceOverride() != null ? in.unitPriceOverride() : snap.unitPrice();
      // Storefront lines also carry a description override — the locale-resolved listing title the
      // shopper saw (slice L2b); online/in-store lines snapshot the internal product.name.
      String description =
          in.descriptionOverride() != null ? in.descriptionOverride() : snap.description();
      SalesOrderLine line =
          SalesOrderLine.create(
              UUID.randomUUID(),
              orderId,
              snap.productId(),
              description,
              in.quantity(),
              unitPrice,
              org.getTaxRate());
      orderLines.add(line);
      subtotal = subtotal.add(line.getLineSubtotal());
      taxTotal = taxTotal.add(line.getLineTax());
    }
    // Shipping: a flat per-order delivery fee for orders that ship; an in-store sale walks out with
    // the goods, so it never carries one.
    BigDecimal shippingTotal =
        channel == OrderChannel.IN_STORE ? BigDecimal.ZERO : org.getShippingFee();
    // Coupons (V72, roadmap item 9): resolved HERE, once the goods subtotal exists, and inside the
    // caller's placement txn — the FOR UPDATE on the coupon row is what serializes the last-slot
    // race, so it has to share the transaction that inserts the order. A null/blank code is the
    // ordinary no-coupon case and resolves to null, leaving discountTotal at zero exactly as
    // before.
    CouponService.Applied coupon =
        couponService.resolveForOrder(txDsl, orgId, couponCode, subtotal, now);
    BigDecimal discountTotal = coupon == null ? BigDecimal.ZERO : coupon.discount();

    int year = now.getYear();
    long seqVal = repo.claimOrderNumber(orgId, year);
    String orderNumber = String.format("SO-%d-%05d", year, seqVal);

    SalesOrder order =
        SalesOrder.createDraft(
            orderId,
            orgId,
            resolvedCustomer == null ? null : resolvedCustomer.getId(),
            orderNumber,
            channel,
            CURRENCY_EGP,
            idempotencyKey,
            now);
    order.setTotals(subtotal, taxTotal, shippingTotal, discountTotal, now);
    if (coupon != null) {
      // A coupon that would zero the order is refused rather than placed: markPendingPayment and
      // markPaid both reject a non-positive grand total, and "free order" is a different product
      // (the whole payment machinery assumes money moves). Caught here so the message names the
      // cause instead of surfacing an opaque transition failure.
      if (order.getGrandTotal().signum() <= 0) {
        throw new ValidationException("This code exceeds the order total");
      }
      order.applyCoupon(coupon.couponId(), coupon.code(), now);
    }
    String normalizedNotes = Text.normalizeText(notes);
    if (normalizedNotes != null) {
      order.updateNotes(normalizedNotes, now);
    }
    return new BuiltOrder(order, orderLines, resolvedCustomer);
  }

  /**
   * Resolve the order's customer. With an email present, upsert on {@code (orgId, email)}. Without
   * an email: required (online) → 400; optional (in-store walk-in) → null (no CRM record, {@code
   * customer_id} stays null).
   */
  private Customer resolveCustomer(
      SalesOrderRepository repo,
      UUID orgId,
      CustomerInput customer,
      boolean required,
      String locale) {
    boolean hasEmail = customer != null && customer.email() != null && !customer.email().isBlank();
    if (!hasEmail) {
      if (required) {
        throw new ValidationException("customer.email is required");
      }
      return null;
    }
    // A single valid address only — a comma list or injected header would otherwise be stored and
    // later fan the emailed order link out to arbitrary recipients (see MagicLink/email channel).
    String normalizedEmail = Text.normalizeEmail(customer.email());
    if (!EmailAddresses.isSingleValid(normalizedEmail)) {
      throw new ValidationException("customer.email is not a valid single email address");
    }
    // Quality gate (story 87), lenient mode: a checkout is never blocked on email quality — a sale
    // with a throwaway email beats no sale. Flag it for ops (domain only, not the address) and
    // proceed; the only cost of a bad address is a dead order-view link.
    EmailGate.Verdict verdict = emailGate.check(normalizedEmail);
    if (verdict != EmailGate.Verdict.OK) {
      log.warn(
          "Checkout email flagged {} (domain {}) for org {} — accepted (lenient mode)",
          verdict,
          EmailGate.domainOf(normalizedEmail),
          orgId);
    }
    return repo.upsertCustomerByEmail(
        orgId,
        normalizedEmail,
        Text.normalizeText(customer.name()),
        Text.normalizeNumeric(customer.phone()),
        Text.normalizeText(customer.address()),
        // Fill-once on the repository side: this teaches us the shopper's language the first time,
        // and never overrides one they have since chosen in the portal (slice L).
        Locales.normalize(locale));
  }

  /**
   * Resolve a portal placement's customer by the session's {@code (orgId, customerId)} — identity
   * is fixed, no body email is ever read (slice P6). A pure read: nothing about the customer is
   * written here.
   *
   * <p><b>It used to freeze the delivery contact onto this row</b>, through the same coalesce
   * upsert the anonymous form uses, because {@code sales_order} had nowhere to put it and {@link
   * InvoiceService} read the invoice's contact block off the customer. The cost was that shipping
   * to anyone else rewrote who <em>you</em> are: a gift to your mother renamed your CRM record to
   * hers and replaced your phone with hers. Inert while that phone was a CRM field; since V79 it is
   * the identity a notification channel dials, so it silently redirected the buyer's own order
   * updates to a third party.
   *
   * <p>V80 gives the order its own {@code delivery_recipient/phone/address}, which is where a
   * per-order fact belongs — and it is written at placement, not here. The address book already
   * stores the reusable copy, so nothing is lost.
   */
  private Customer resolveKnownCustomer(SalesOrderRepository repo, UUID orgId, UUID customerId) {
    return repo.findCustomerById(orgId, customerId)
        .orElseThrow(() -> new NotFoundException("Customer", customerId));
  }

  // Validation

  private void validateOnlineInputs(CustomerInput customer, List<OrderLineInput> lines) {
    if (customer == null) {
      throw new ValidationException("customer is required");
    }
    if (customer.email() == null || customer.email().isBlank()) {
      throw new ValidationException("customer.email is required");
    }
    if (customer.name() == null || customer.name().isBlank()) {
      throw new ValidationException("customer.name is required");
    }
    validateLines(lines);
  }

  private void validateInStoreInputs(
      List<OrderLineInput> lines, PaymentInput payment, DiscountInput discount) {
    validateLines(lines);
    if (payment == null || payment.provider() == null) {
      throw new ValidationException("payment.provider is required");
    }
    if (payment.provider() == PaymentProvider.INSTAPAY_MANUAL) {
      throw new ValidationException(PaymentService.IN_STORE_PROVIDER_REJECT_MSG);
    }
    if (payment.amount() != null && payment.amount().signum() <= 0) {
      throw new ValidationException("payment.amount must be > 0");
    }
    if (discount != null) {
      validateDiscount(discount);
    }
  }

  /**
   * Shape checks on a counter discount — cause-naming 400s, before the authority question is even
   * asked (a malformed request from a manager is still malformed). The ranges are the coupon's:
   * PERCENT in (0, 100], FIXED strictly positive; whether the money then exceeds the ticket is
   * decided against the real subtotal inside the txn.
   */
  private static void validateDiscount(DiscountInput discount) {
    if (discount.type() == null) {
      throw new ValidationException("discount.type must be PERCENT or FIXED");
    }
    BigDecimal value = discount.value();
    if (value == null) {
      throw new ValidationException("discount.value is required");
    }
    if (discount.type() == CouponType.PERCENT) {
      if (value.signum() <= 0 || value.compareTo(new BigDecimal("100")) > 0) {
        throw new ValidationException("discount.value must be > 0 and <= 100 for PERCENT");
      }
    } else if (value.signum() <= 0) {
      throw new ValidationException("discount.value must be > 0 for FIXED");
    }
    if (discount.reason() != null && discount.reason().length() > COUNTER_DISCOUNT_REASON_MAX) {
      throw new ValidationException(
          "discount.reason must be at most " + COUNTER_DISCOUNT_REASON_MAX + " characters");
    }
  }

  /**
   * Take the counter discount off the DRAFT order inside the sale txn (V88): the money through
   * {@link DiscountMath} against the goods subtotal (pre-tax; tax stays on the undiscounted lines —
   * the coupon convention, {@code grand = subtotal + tax + shipping − discount}), the totals
   * re-run, then the provenance frozen on the order.
   *
   * <p>The authority check sits here, once the money is known, so the refusal can name the amount
   * asked for — and before the insert, so a STAFF request writes nothing (the txn rolls back the
   * claimed order number with it). No {@code threshold_amount} on the refusal on purpose: this
   * slice has no configurable STAFF allowance (that knob is the deferred threshold half of the
   * pattern), and a literal {@code 0.00} would make the client say "above the EGP 0.00 limit".
   */
  private static void applyCounterDiscount(
      SalesOrder order,
      DiscountInput discount,
      boolean callerIsManagerOrAdmin,
      UUID grantedBy,
      OffsetDateTime now) {
    BigDecimal money =
        DiscountMath.discountFor(discount.type(), discount.value(), order.getSubtotal());
    if (!callerIsManagerOrAdmin) {
      throw new ApprovalRequiredException(
          "a counter discount of "
              + money
              + " on order "
              + order.getOrderNumber()
              + " requires MANAGER",
          OrgRole.MANAGER.name(),
          null,
          money);
    }
    order.setTotals(order.getSubtotal(), order.getTaxTotal(), order.getShippingTotal(), money, now);
    // The coupon's rule, for the coupon's reason: the payment machinery assumes money moves, and a
    // free giveaway is an inventory adjustment, not a sale.
    if (order.getGrandTotal().signum() <= 0) {
      throw new ValidationException("This discount exceeds the order total");
    }
    order.applyCounterDiscount(
        discount.type(), discount.value(), Text.normalizeText(discount.reason()), grantedBy, now);
  }

  private void validateLines(List<OrderLineInput> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new ValidationException("lines must not be empty");
    }
    for (int i = 0; i < lines.size(); i++) {
      OrderLineInput l = lines.get(i);
      if (l == null) {
        throw new ValidationException("lines[" + i + "] is null");
      }
      if (l.productId() == null) {
        throw new ValidationException("lines[" + i + "].product_id is required");
      }
      if (l.quantity() <= 0) {
        throw new ValidationException("lines[" + i + "].quantity must be > 0");
      }
    }
  }

  private Customer loadCustomerOrThrow(SalesOrderRepository repo, UUID orgId, UUID customerId) {
    if (customerId == null) {
      throw new IllegalStateException("Idempotent replay: prior order has no customer_id");
    }
    return repo.findCustomerById(orgId, customerId)
        .orElseThrow(() -> new NotFoundException("Customer", customerId));
  }
}
