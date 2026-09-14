package com.loai.inventory.service;

import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.text.Locales;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.model.PaymentIntent;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.OrgPaymobConfigRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentIntentRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.service.paymob.PaymobClient;
import com.loai.inventory.service.paymob.PaymobClient.Billing;
import com.loai.inventory.service.paymob.PaymobClient.IntentionRequest;
import com.loai.inventory.service.paymob.PaymobClient.IntentionResult;
import com.loai.inventory.service.paymob.PaymobClient.Item;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shopper's side of a card payment ({@code stories/paymob_card_checkout.md}, {@code POST
 * /api/public/orders/{token}/pay}): mint a Paymob intention for the order's outstanding amount,
 * remember it as a {@link PaymentIntent}, and hand back the Unified Checkout URL. No money moves
 * here — the webhook ({@link PaymobWebhookService}) is the only writer of money.
 *
 * <p>The intent row is written <b>after</b> Paymob answers, in a transaction of its own: if Paymob
 * answers and the insert then fails, the shopper could pay against an intention we have no record
 * of — and the webhook would record that money as an ORPHAN rather than lose it (its {@code
 * raw_payload} carries Paymob's echo of the reference for a human to follow). Losing money is not
 * on the table; the worst case is manual matching.
 */
public final class PaymentIntentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentIntentService.class);

  /** Paymob requires a phone on the billing block; an order placed without one still gets paid. */
  static final String PLACEHOLDER_PHONE = "+201000000000";

  private final DSLContext rootDsl;
  private final PaymentIntentRepositoryFactory intentRepoFactory;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final CustomerRepositoryFactory customerRepoFactory;
  private final OrgPaymobConfigRepositoryFactory paymobConfigRepoFactory;
  private final SecretBox secretBox;
  private final PaymobClient paymob;
  private final String publicApiUrl;
  private final String publicBaseUrl;
  private final Duration intentTtl;

  public PaymentIntentService(
      DSLContext rootDsl,
      PaymentIntentRepositoryFactory intentRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      CustomerRepositoryFactory customerRepoFactory,
      OrgPaymobConfigRepositoryFactory paymobConfigRepoFactory,
      SecretBox secretBox,
      PaymobClient paymob,
      String publicApiUrl,
      String publicBaseUrl,
      Duration intentTtl) {
    this.rootDsl = rootDsl;
    this.intentRepoFactory = intentRepoFactory;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.customerRepoFactory = customerRepoFactory;
    this.paymobConfigRepoFactory = paymobConfigRepoFactory;
    this.secretBox = secretBox;
    this.paymob = paymob;
    this.publicApiUrl = stripTrailingSlash(publicApiUrl);
    this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    this.intentTtl = intentTtl;
  }

  /** What the shopper gets: where to pay and until when. {@code reused} = a second tap. */
  public record PayResult(
      String checkoutUrl, OffsetDateTime expiresAt, PaymentIntent intent, boolean reused) {}

  /**
   * Mint (or reuse) the intent for {@code orderId}'s outstanding amount.
   *
   * @param customerId the order-view token's customer (may be null — a PHONE order's link)
   * @param orderViewToken the raw magic token the shopper holds; the return page is the branded
   *     status page at that token, which polls the order and never settles anything itself
   * @throws NotFoundException the order is not in the org (the servlet answers the opaque 404)
   * @throws ConflictException the order is not awaiting payment, nothing is left to pay, or the org
   *     offers no card channel — all 409, all states the storefront already knows and should not
   *     have offered the button for; the check is the server not trusting that
   */
  public PayResult pay(UUID orgId, UUID orderId, UUID customerId, String orderViewToken) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    SalesOrder order =
        salesOrderRepoFactory
            .create(rootDsl)
            .findById(orgId, orderId)
            .orElseThrow(() -> new NotFoundException("SalesOrder", orderId));
    if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
      throw new ConflictException(
          "order "
              + order.getOrderNumber()
              + " is "
              + order.getStatus()
              + ", not awaiting payment");
    }

    OrgPaymobConfig config =
        paymobConfigRepoFactory
            .create(rootDsl)
            .findByOrgId(orgId)
            .filter(OrgPaymobConfig::isActive)
            .orElseThrow(() -> new ConflictException("this shop does not accept card payments"));
    if (!secretBox.isConfigured()) {
      // The operator's problem, not the shopper's — same 409 shape as connect's refusal.
      throw new ConflictException("card payments are unavailable: no credential key configured");
    }

    BigDecimal amount =
        order
            .getGrandTotal()
            .subtract(order.getPrepaidAmount())
            .setScale(2, RoundingMode.HALF_EVEN);
    if (amount.signum() <= 0) {
      throw new ConflictException("order " + order.getOrderNumber() + " has nothing left to pay");
    }
    String currency = order.getCurrency();

    // A shopper who taps twice, or reloads the return page, gets the same checkout URL. A new
    // intent is minted when the amount changed or the old one expired / was spent.
    Optional<PaymentIntent> live =
        intentRepoFactory
            .create(rootDsl)
            .findLiveForOrder(orgId, orderId, amount, currency, now)
            .filter(i -> i.getClientSecret() != null);
    if (live.isPresent()) {
      PaymentIntent intent = live.get();
      log.debug(
          "Reusing live payment intent {} for order {} ({} {})",
          intent.getId(),
          order.getOrderNumber(),
          amount,
          currency);
      return new PayResult(
          PaymobClient.checkoutUrl(config, intent.getClientSecret()),
          intent.getExpiresAt(),
          intent,
          true);
    }

    Org org =
        orgRepoFactory
            .create(rootDsl)
            .findById(orgId)
            .orElseThrow(() -> new IllegalStateException("org not found: " + orgId));
    Customer customer =
        customerId == null
            ? null
            : customerRepoFactory.create(rootDsl).findById(orgId, customerId).orElse(null);

    PaymentIntent intent =
        PaymentIntent.create(
            UUID.randomUUID(),
            orgId,
            orderId,
            PaymentProvider.PAYMOB_CARD,
            amount,
            currency,
            now.plus(intentTtl),
            now);

    IntentionRequest request =
        new IntentionRequest(
            toPiastres(amount),
            currency,
            config.cardIntegrationId(),
            intent.getSpecialReference(),
            publicApiUrl + "/api/psp/paymob/" + orgId + "/webhook",
            returnPageUrl(org, customer, orderViewToken),
            intentTtl.toSeconds(),
            // One line for the whole outstanding amount: the order's lines do not sum to it (tax,
            // shipping, discount, a prior partial), and Paymob's page shows the total either way.
            List.of(
                new Item(
                    "Order " + order.getOrderNumber(),
                    toPiastres(amount),
                    1,
                    org.getName() + " — order " + order.getOrderNumber())),
            billingFor(order, customer),
            orgId.toString(),
            orderId.toString(),
            intent.getId().toString());

    // Decrypted at the moment of use; the client keeps it for one request and nothing else.
    String secretKey = secretBox.decrypt(config.secretKeyEncrypted());
    IntentionResult result = paymob.createIntention(config, secretKey, request);
    intent.attachIntention(result.intentionId(), result.paymobOrderId(), result.clientSecret());

    rootDsl.transaction(cfg -> intentRepoFactory.create(DSL.using(cfg)).insert(intent));
    log.info(
        "Minted payment intent {} for order {} ({} {}, intention {}, paymob order {})",
        intent.getId(),
        order.getOrderNumber(),
        amount,
        currency,
        result.intentionId(),
        result.paymobOrderId());
    return new PayResult(
        PaymobClient.checkoutUrl(config, result.clientSecret()),
        intent.getExpiresAt(),
        intent,
        false);
  }

  /** Paymob amounts are integer piastres; ours are NUMERIC(14,2). Exact, in one place. */
  static long toPiastres(BigDecimal egp) {
    return egp.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
  }

  /** The branded status page at the shopper's own token — the same shape the emails link to. */
  private String returnPageUrl(Org org, Customer customer, String orderViewToken) {
    String locale =
        Locales.resolve(customer == null ? null : customer.getLocale(), org.getDefaultLocale());
    return publicBaseUrl + "/" + locale + "/" + org.getSlug() + "/orders/" + orderViewToken;
  }

  /**
   * Paymob's required billing block. Phone: the order's delivery contact, then the customer's
   * record, then the walk-in contact, then a placeholder — a missing phone must not block a
   * payment.
   */
  private static Billing billingFor(SalesOrder order, Customer customer) {
    String name =
        firstNonBlank(
            order.getDeliveryRecipient(),
            customer == null ? null : customer.getName(),
            order.getCustomerName());
    String first = "Customer";
    String last = "-";
    if (name != null) {
      String[] parts = name.strip().split("\\s+", 2);
      first = parts[0];
      last = parts.length > 1 ? parts[1] : "-";
    }
    String phone =
        firstNonBlank(
            order.getDeliveryPhone(),
            customer == null ? null : customer.getPhone(),
            order.getCustomerPhone(),
            PLACEHOLDER_PHONE);
    return new Billing(first, last, phone, customer == null ? null : customer.getEmail());
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v.strip();
      }
    }
    return null;
  }

  private static String stripTrailingSlash(String s) {
    if (s == null) {
      return "";
    }
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
