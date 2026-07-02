package com.loai.inventory.service;

import com.loai.inventory.domain.model.CustomerMagicToken;
import com.loai.inventory.domain.model.MagicTokenPurpose;
import com.loai.inventory.domain.repository.CustomerMagicTokenRepository;
import com.loai.inventory.domain.repository.CustomerMagicTokenRepositoryFactory;
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
  private final String publicBaseUrl;
  private final Duration ttl;

  public MagicLinkService(
      DSLContext rootDsl,
      CustomerMagicTokenRepositoryFactory tokenRepoFactory,
      String publicBaseUrl,
      Duration ttl) {
    this.rootDsl = rootDsl;
    this.tokenRepoFactory = tokenRepoFactory;
    this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    this.ttl = ttl;
  }

  /** Where a resolved order view lives — the token's org + the order it unlocks. */
  public record ResolvedOrderView(UUID orgId, UUID orderId) {}

  /** The subject of a resolved unsubscribe token — the customer whose email to switch off. */
  public record ResolvedUnsubscribe(UUID orgId, UUID customerId) {}

  /**
   * Mint a VIEW_ORDER token for {@code (orgId, customerId, orderId)} inside the caller's txn and
   * return the absolute public URL carrying the raw token. Because it runs in {@code txDsl}, a
   * rolled-back order leaves no token.
   */
  public String issueOrderViewLink(
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
    return publicBaseUrl + "/api/public/orders/" + rawToken;
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
        .map(t -> new ResolvedOrderView(t.orgId(), t.resourceId()));
  }

  /**
   * Mint an UNSUBSCRIBE token for {@code customerId} inside the caller's txn and return the
   * absolute public URL. Not order-scoped ({@code resource_id} null) — it turns the customer's
   * email off for the org. Minted in {@code txDsl} so a rolled-back producer leaves no token.
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
    return publicBaseUrl + "/api/public/unsubscribe/" + rawToken;
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
