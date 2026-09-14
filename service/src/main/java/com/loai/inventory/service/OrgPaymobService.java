package com.loai.inventory.service;

import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.repository.OrgPaymobConfigRepositoryFactory;
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
  private final SecretBox secretBox;

  public OrgPaymobService(
      DSLContext rootDsl, OrgPaymobConfigRepositoryFactory repoFactory, SecretBox secretBox) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.secretBox = secretBox;
  }

  /** What a settings screen may see: everything except the two secrets. */
  public record ConnectionStatus(
      boolean connected,
      OrgPaymobConfig.Status status,
      String publicKey,
      Integer cardIntegrationId,
      String region,
      java.time.OffsetDateTime connectedAt,
      java.time.OffsetDateTime updatedAt) {

    static ConnectionStatus of(OrgPaymobConfig c) {
      return new ConnectionStatus(
          true,
          c.status(),
          c.publicKey(),
          c.cardIntegrationId(),
          c.region(),
          c.connectedAt(),
          c.updatedAt());
    }

    static ConnectionStatus disconnected() {
      return new ConnectionStatus(false, null, null, null, null, null, null);
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
      Integer cardIntegrationId,
      String region) {
    requireText(publicKey, "public_key");
    requireText(secretKey, "secret_key");
    requireText(hmacSecret, "hmac_secret");
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
    OrgPaymobConfig saved =
        rootDsl.transactionResult(
            cfg ->
                repoFactory
                    .create(DSL.using(cfg))
                    .upsert(
                        orgId,
                        publicKey.strip(),
                        sealedSecret,
                        sealedHmac,
                        cardIntegrationId,
                        normalizedRegion));
    // Never log a secret, and never log enough of one to be useful.
    log.info(
        "Paymob connected for org {} (card_integration_id={} region={})",
        orgId,
        saved.cardIntegrationId(),
        saved.region());
    return ConnectionStatus.of(saved);
  }

  /**
   * Disconnect entirely — the credentials are deleted, not disabled. Idempotent: disconnecting an
   * org that was never connected is a no-op success, because the caller's intent ("stop offering
   * card") is already true. Existing {@code payment_transaction} rows are untouched — the ledger
   * records money that really moved.
   */
  public void disconnect(UUID orgId) {
    boolean removed =
        rootDsl.transactionResult(cfg -> repoFactory.create(DSL.using(cfg)).delete(orgId));
    log.info("Paymob disconnected for org {} (had a configuration: {})", orgId, removed);
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
