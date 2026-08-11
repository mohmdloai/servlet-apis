package com.loai.inventory.service;

import com.loai.inventory.common.text.Locales;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.CustomerMagicToken;
import com.loai.inventory.domain.model.MagicTokenPurpose;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.CustomerMagicTokenRepository;
import com.loai.inventory.domain.repository.CustomerMagicTokenRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.service.auth.RefreshTokenStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mints and resolves order-scoped magic links (notifications-plan §7). The raw token is a 256-bit
 * {@link SecureRandom} value returned only in the emailed URL; only its SHA-256 hash is stored
 * (reusing {@link RefreshTokenStore#hashToken}). Tokens carry a {@link MagicTokenPurpose} — {@code
 * VIEW_ORDER} (unlocks one order) or {@code UNSUBSCRIBE} (turns the customer's email off) — and are
 * multi-use until they expire; both apply idempotently, so {@code consumed_at} stays null. (It
 * remains available for a future one-shot purpose.)
 */
public class MagicLinkService {

  private static final Logger log = LoggerFactory.getLogger(MagicLinkService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final int TOKEN_BYTES = 32; // 256 bits

  private final DSLContext rootDsl;
  private final CustomerMagicTokenRepositoryFactory tokenRepoFactory;
  private final OrgRepositoryFactory orgRepositoryFactory;
  private final CustomerRepositoryFactory customerRepositoryFactory;
  private final String publicBaseUrl;
  private final Duration ttl;

  public MagicLinkService(
      DSLContext rootDsl,
      CustomerMagicTokenRepositoryFactory tokenRepoFactory,
      OrgRepositoryFactory orgRepositoryFactory,
      CustomerRepositoryFactory customerRepositoryFactory,
      String publicBaseUrl,
      Duration ttl) {
    this.rootDsl = rootDsl;
    this.tokenRepoFactory = tokenRepoFactory;
    this.orgRepositoryFactory = orgRepositoryFactory;
    this.customerRepositoryFactory = customerRepositoryFactory;
    this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    this.ttl = ttl;
  }

  /**
   * The locale segment for a link emailed to {@code customerId}: their own {@code locale} when we
   * have learned one, else the org's default (slice L).
   *
   * <p>Without this the email and the page it opens disagree — an Arabic message whose only button
   * lands the shopper on an English page, which is the half-translated experience this slice exists
   * to end. Total and non-throwing for the same reason the notification producer is: these links
   * are minted inside business transactions.
   */
  private String linkLocale(DSLContext txDsl, UUID orgId, UUID customerId, Org org) {
    String customerLocale = null;
    if (customerId != null) {
      try {
        customerLocale =
            customerRepositoryFactory
                .create(txDsl)
                .findById(orgId, customerId)
                .map(Customer::getLocale)
                .orElse(null);
      } catch (RuntimeException e) {
        log.warn(
            "Could not read the locale for customer {} — using the org default", customerId, e);
      }
    }
    return Locales.resolve(customerLocale, org.getDefaultLocale());
  }

  /**
   * A minted order-view link in both forms: the {@code absolute} URL emailed to the customer and
   * the {@code relative} path returned as the checkout {@code track_url}. Both point at the branded
   * storefront status page {@code /{locale}/{orgSlug}/orders/{token}} (not the raw JSON endpoint).
   */
  public record OrderViewLink(String absolute, String relative) {}

  /**
   * Where a resolved order view lives — the token's org, the order it unlocks, and the customer it
   * belongs to (the last lets an anonymous shopper's payment-claim stamp {@code
   * claimed_by_customer_id} without a login — roadmap item 2).
   */
  public record ResolvedOrderView(UUID orgId, UUID orderId, UUID customerId) {}

  /** The subject of a resolved unsubscribe token — the customer whose email to switch off. */
  public record ResolvedUnsubscribe(UUID orgId, UUID customerId) {}

  /**
   * Mint a VIEW_ORDER token for {@code (orgId, customerId, orderId)} inside the caller's txn and
   * return the storefront order-view link in {@link OrderViewLink both forms}. The path is {@code
   * /{locale}/{orgSlug}/orders/{token}} — the branded status page, not the raw JSON endpoint (which
   * the page fetches server-side). The org's {@code slug} + {@code default_locale} are read from
   * {@code txDsl} (the org row is already committed); a missing locale falls back to {@code en}.
   * Because it runs in {@code txDsl}, a rolled-back order leaves no token.
   */
  public OrderViewLink issueOrderViewLink(
      DSLContext txDsl, UUID orgId, UUID customerId, UUID orderId, OffsetDateTime now) {
    String rawToken = generateRawToken();
    String tokenHash = RefreshTokenStore.hashToken(rawToken);
    tokenRepoFactory
        .create(txDsl)
        .insert(
            CustomerMagicToken.forInsert(
                orgId,
                customerId,
                tokenHash,
                MagicTokenPurpose.VIEW_ORDER,
                orderId,
                now.plus(ttl)));
    log.debug(
        "Minted VIEW_ORDER magic token orgId={} customerId={} orderId={}",
        orgId,
        customerId,
        orderId);
    Org org =
        orgRepositoryFactory
            .create(txDsl)
            .findById(orgId)
            .orElseThrow(() -> new IllegalStateException("org not found for magic link: " + orgId));
    String locale = linkLocale(txDsl, orgId, customerId, org);
    String relative = "/" + locale + "/" + org.getSlug() + "/orders/" + rawToken;
    return new OrderViewLink(publicBaseUrl + relative, relative);
  }

  /**
   * Resolve a raw token to the order it unlocks: live (unconsumed, unexpired) and a VIEW_ORDER
   * token. Returns empty for anything else — the caller answers 404 without distinguishing cases.
   */
  public Optional<ResolvedOrderView> resolveOrderView(String rawToken, OffsetDateTime now) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }
    CustomerMagicTokenRepository repo = tokenRepoFactory.create(rootDsl);
    return repo.findActiveByHash(RefreshTokenStore.hashToken(rawToken), now)
        .filter(t -> t.purpose() == MagicTokenPurpose.VIEW_ORDER && t.resourceId() != null)
        .map(t -> new ResolvedOrderView(t.orgId(), t.resourceId(), t.customerId()));
  }

  /**
   * Mint an UNSUBSCRIBE token for {@code customerId} inside the caller's txn and return the
   * absolute public URL. Not order-scoped ({@code resource_id} null) — it turns the customer's
   * email off for the org. Minted in {@code txDsl} so a rolled-back producer leaves no token.
   *
   * <p>The link targets the storefront's confirmation page, not the API — the same {@code
   * /{locale}/{orgSlug}/…} shape {@link #issueOrderViewLink} builds, for the same reason (an
   * emailed link should land a human on a page that says what happened). It is also the fix for a
   * real defect: {@code PublicUnsubscribeServlet} applies on {@code GET}, so pointing the footer
   * straight at it let any mail client that prefetches links silently unsubscribe a customer who
   * never clicked. The page applies nothing on render; the shopper's button press is a {@code
   * POST}.
   *
   * <p>The servlet's {@code GET} is deliberately left working — links already sitting in inboxes
   * carry the old URL and must keep resolving. This changes the address we hand out, not the
   * addresses we honour.
   */
  public String issueUnsubscribeLink(
      DSLContext txDsl, UUID orgId, UUID customerId, OffsetDateTime now) {
    String rawToken = generateRawToken();
    String tokenHash = RefreshTokenStore.hashToken(rawToken);
    tokenRepoFactory
        .create(txDsl)
        .insert(
            CustomerMagicToken.forInsert(
                orgId, customerId, tokenHash, MagicTokenPurpose.UNSUBSCRIBE, null, now.plus(ttl)));
    Org org =
        orgRepositoryFactory
            .create(txDsl)
            .findById(orgId)
            .orElseThrow(() -> new IllegalStateException("org not found for magic link: " + orgId));
    String locale = linkLocale(txDsl, orgId, customerId, org);
    return publicBaseUrl + "/" + locale + "/" + org.getSlug() + "/unsubscribe/" + rawToken;
  }

  /**
   * Resolve a raw token to the customer whose email it unsubscribes: live and an UNSUBSCRIBE token.
   * Empty for anything else — the caller answers 404 without distinguishing cases.
   */
  public Optional<ResolvedUnsubscribe> resolveUnsubscribe(String rawToken, OffsetDateTime now) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }
    CustomerMagicTokenRepository repo = tokenRepoFactory.create(rootDsl);
    return repo.findActiveByHash(RefreshTokenStore.hashToken(rawToken), now)
        .filter(t -> t.purpose() == MagicTokenPurpose.UNSUBSCRIBE)
        .map(t -> new ResolvedUnsubscribe(t.orgId(), t.customerId()));
  }

  private static String generateRawToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return URL_ENCODER.encodeToString(bytes);
  }

  private static String stripTrailingSlash(String url) {
    return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
  }
}
