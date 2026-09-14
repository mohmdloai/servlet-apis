package com.loai.inventory.service;

import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.repository.OrgPaymobConfigRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentIntentRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A merchant connecting their own Paymob card account (epic slice 1, {@code
 * stories/paymob_connect.md}) — the tenant-facing half of the "each org connects its own merchant
 * account" decision. This slice takes no money: it stores credentials so slice 2 has something to
 * authenticate with.
 *
 * <p><b>The secret key and HMAC secret never come back out.</b> Both are sealed with {@link
 * SecretBox} on the way in and decrypted only at the moment of use (minting an intention, verifying
 * a webhook — slice 2). No read here returns either, in any form, to anybody — not the OWNER who
 * supplied them, not a platform ADMIN. {@code public_key} is the one credential meant to leave the
 * server: the browser needs it to open Unified Checkout.
 *
 * <p>Mirrors {@link OrgWhatsAppService} method-for-method, minus {@code setEnabled} — this resource
 * has no enable/disable sub-route; re-POSTing to replace credentials is the only transition besides
 * connect/disconnect.
 */
public class OrgPaymobService {

  private static final Logger log = LoggerFactory.getLogger(OrgPaymobService.class);

  /** V98's CHECK constraint permits exactly one value until an org actually onboards elsewhere. */
  private static final Set<String> SUPPORTED_REGIONS = Set.of("EGYPT");

  private final DSLContext rootDsl;
  private final OrgPaymobConfigRepositoryFactory repoFactory;
  private final PaymentIntentRepositoryFactory intentRepoFactory;
  private final SecretBox secretBox;

  public OrgPaymobService(
      DSLContext rootDsl,
      OrgPaymobConfigRepositoryFactory repoFactory,
      PaymentIntentRepositoryFactory intentRepoFactory,
      SecretBox secretBox) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.intentRepoFactory = intentRepoFactory;
    this.secretBox = secretBox;
  }

  /** What a settings screen may see: everything except the two secrets. */
  public record ConnectionStatus(
      boolean connected,
      OrgPaymobConfig.Status status,
      String publicKey,
      Integer cardIntegrationId,
      String region,
      /**
       * Whether an API key is on file, i.e. whether the poller can settle a payment whose webhook
       * never arrived. False only on a row connected before V100; reconnecting fixes it.
       */
      Boolean inquiryEnabled,
      java.time.OffsetDateTime connectedAt,
      java.time.OffsetDateTime updatedAt) {

    static ConnectionStatus of(OrgPaymobConfig c) {
      return new ConnectionStatus(
          true,
          c.status(),
          c.publicKey(),
          c.cardIntegrationId(),
          c.region(),
          c.canInquire(),
          c.connectedAt(),
          c.updatedAt());
    }

    static ConnectionStatus disconnected() {
      return new ConnectionStatus(false, null, null, null, null, null, null, null);
    }
  }

  public ConnectionStatus status(UUID orgId) {
    return repoFactory
        .create(rootDsl)
        .findByOrgId(orgId)
        .map(ConnectionStatus::of)
        .orElseGet(ConnectionStatus::disconnected);
  }

  /**
   * Store (or replace) the merchant's credentials. Re-connecting always lands ACTIVE. There is no
   * partial update — every credential is replaced together, because a secret key from one account
   * paired with an HMAC secret from another fails only at the worst possible moment: a real
   * payment, silently unverifiable.
   *
   * @throws ConflictException when no {@code PAYMOB_CREDENTIAL_KEY} is configured — a 409 rather
   *     than a 500 because the operator, not the merchant, has to fix it, and storing a card secret
   *     in the clear instead is not an option this method is willing to take.
   */
  public ConnectionStatus connect(
      UUID orgId,
      String publicKey,
      String secretKey,
      String hmacSecret,
      String apiKey,
      Integer cardIntegrationId,
      String region) {
    requireText(publicKey, "public_key");
    requireText(secretKey, "secret_key");
    requireText(hmacSecret, "hmac_secret");
    // Required since slice 3 (V100): without it a dropped webhook is a charged card and an unpaid
    // order nobody can settle — the exact failure the poller exists to prevent.
    requireText(apiKey, "api_key");
    if (cardIntegrationId == null || cardIntegrationId <= 0) {
      throw new ValidationException("card_integration_id must be a positive integer");
    }
    String normalizedRegion = region == null ? "EGYPT" : region.strip();
    if (!SUPPORTED_REGIONS.contains(normalizedRegion)) {
      throw new ValidationException(
          "unsupported region: " + region + " (supported: " + SUPPORTED_REGIONS + ")");
    }
    if (!secretBox.isConfigured()) {
      throw new ConflictException("cannot store payment credentials: no encryption key configured");
    }

    String sealedSecret = secretBox.encrypt(secretKey.strip());
    String sealedHmac = secretBox.encrypt(hmacSecret.strip());
    String sealedApiKey = secretBox.encrypt(apiKey.strip());
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    record Saved(OrgPaymobConfig config, int retiredIntents) {}
    Saved saved =
        rootDsl.transactionResult(
            cfg -> {
              DSLContext txDsl = DSL.using(cfg);
              OrgPaymobConfig config =
                  repoFactory
                      .create(txDsl)
                      .upsert(
                          orgId,
                          publicKey.strip(),
                          sealedSecret,
                          sealedHmac,
                          sealedApiKey,
                          cardIntegrationId,
                          normalizedRegion);
              // A live intent was minted against the credentials and integration this call just
              // replaced: its checkout secret is not valid for the new public key, and a callback
              // for it would name the old integration. Retire them in the same transaction so a
              // second tap on "pay" mints afresh (stories/paymob_card_checkout.md, Built notes).
              int retired = intentRepoFactory.create(txDsl).expireLiveForOrg(orgId, now);
              return new Saved(config, retired);
            });
    // Never log a secret, and never log enough of one to be useful.
    log.info(
        "Paymob connected for org {} (card_integration_id={} region={}; retired {} live intent(s))",
        orgId,
        saved.config().cardIntegrationId(),
        saved.config().region(),
        saved.retiredIntents());
    return ConnectionStatus.of(saved.config());
  }

  /**
   * Disconnect entirely — the credentials are deleted, not disabled. Idempotent: disconnecting an
   * org that was never connected is a no-op success, because the caller's intent ("stop offering
   * card") is already true. Existing {@code payment_transaction} rows are untouched — the ledger
   * records money that really moved.
   */
  public void disconnect(UUID orgId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    record Removed(boolean hadConfig, int retiredIntents) {}
    Removed removed =
        rootDsl.transactionResult(
            cfg -> {
              DSLContext txDsl = DSL.using(cfg);
              boolean had = repoFactory.create(txDsl).delete(orgId);
              // The org no longer offers card; a live intent must not be handed back by …/pay.
              // (A shopper already on Paymob's page can still complete — that callback is then a
              // 400 with no config, which slice 3's inquiry cannot recover either; it is the one
              // window a merchant opens by disconnecting mid-checkout, and it is logged loudly.)
              int retired = intentRepoFactory.create(txDsl).expireLiveForOrg(orgId, now);
              return new Removed(had, retired);
            });
    log.info(
        "Paymob disconnected for org {} (had a configuration: {}; retired {} live intent(s))",
        orgId,
        removed.hadConfig(),
        removed.retiredIntents());
  }

  /** The config a checkout/webhook needs, or empty — used by slice 2, never by a handler. */
  public Optional<OrgPaymobConfig> activeConfig(UUID orgId) {
    return repoFactory.create(rootDsl).findByOrgId(orgId).filter(OrgPaymobConfig::isActive);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ValidationException(field + " is required");
    }
  }
}
