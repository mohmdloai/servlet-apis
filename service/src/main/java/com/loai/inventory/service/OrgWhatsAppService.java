package com.loai.inventory.service;

import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Phone;
import com.loai.inventory.domain.model.OrgWhatsAppConfig;
import com.loai.inventory.domain.repository.OrgWhatsAppConfigRepositoryFactory;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A merchant connecting their own WhatsApp Business Account (slice B) — the tenant-facing half of
 * the per-merchant WABA decision.
 *
 * <p><b>The access token never comes back out.</b> It is sealed with {@link SecretBox} on the way
 * in and decrypted only inside the sender, at the moment of a send. No read here returns it, in any
 * form, to anybody — not the OWNER who supplied it, not a platform ADMIN. The status read exists so
 * a merchant can see <em>that</em> they are connected and from which number, which is the whole
 * question a settings screen needs to answer.
 *
 * <p><b>What this deliberately does not do: talk to Meta.</b> Business verification, the embedded
 * signup handshake, and utility-template approval all happen in the merchant's WhatsApp Manager,
 * outside this system. This service stores the result of that work and refuses to pretend it
 * happened — {@code status} reflects what we were told, and the first real proof that the
 * credentials work is a delivery that either sends or fails with a terminal 4xx that names the
 * cause.
 */
public class OrgWhatsAppService {

  private static final Logger log = LoggerFactory.getLogger(OrgWhatsAppService.class);

  private final DSLContext rootDsl;
  private final OrgWhatsAppConfigRepositoryFactory repoFactory;
  private final SecretBox secretBox;

  public OrgWhatsAppService(
      DSLContext rootDsl, OrgWhatsAppConfigRepositoryFactory repoFactory, SecretBox secretBox) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.secretBox = secretBox;
  }

  /** What a settings screen may see: everything except the credential. */
  public record ConnectionStatus(
      boolean connected,
      String wabaId,
      String phoneNumberId,
      String displayPhoneNumber,
      OrgWhatsAppConfig.Status status) {

    static ConnectionStatus of(OrgWhatsAppConfig c) {
      return new ConnectionStatus(
          true, c.wabaId(), c.phoneNumberId(), c.displayPhoneNumber(), c.status());
    }

    static ConnectionStatus disconnected() {
      return new ConnectionStatus(false, null, null, null, null);
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
   * Store (or replace) the merchant's credentials. Re-connecting always lands ACTIVE.
   *
   * @throws ConflictException when no {@code WHATSAPP_TOKEN_KEY} is configured — a 409 rather than
   *     a 500 because the operator, not the merchant, has to fix it, and storing the token in the
   *     clear instead is not an option this method is willing to take.
   */
  public ConnectionStatus connect(
      UUID orgId,
      String wabaId,
      String phoneNumberId,
      String displayPhoneNumber,
      String accessToken) {
    requireText(wabaId, "waba_id");
    requireText(phoneNumberId, "phone_number_id");
    requireText(accessToken, "access_token");
    if (!secretBox.isConfigured()) {
      throw new ConflictException(
          "WhatsApp is not available on this deployment — no token encryption key is configured");
    }
    // The display number is what a shopper sees the message come from; normalize it the same way
    // every other number in the system is, so a merchant typing 010… and +2010… are one value.
    String normalizedDisplay = displayPhoneNumber == null ? null : Phone.toE164(displayPhoneNumber);

    String sealed = secretBox.encrypt(accessToken.strip());
    OrgWhatsAppConfig saved =
        rootDsl.transactionResult(
            cfg ->
                repoFactory
                    .create(DSL.using(cfg))
                    .upsert(
                        orgId, wabaId.strip(), phoneNumberId.strip(), normalizedDisplay, sealed));
    // Never log the token, and never log enough of it to be useful.
    log.info(
        "WhatsApp connected for org {} (waba={} phone_number_id={})",
        orgId,
        saved.wabaId(),
        saved.phoneNumberId());
    return ConnectionStatus.of(saved);
  }

  /** Pause or resume sending without discarding the credentials. */
  public ConnectionStatus setEnabled(UUID orgId, boolean enabled) {
    OrgWhatsAppConfig.Status target =
        enabled ? OrgWhatsAppConfig.Status.ACTIVE : OrgWhatsAppConfig.Status.DISABLED;
    return rootDsl
        .transactionResult(cfg -> repoFactory.create(DSL.using(cfg)).setStatus(orgId, target))
        .map(ConnectionStatus::of)
        .orElseThrow(() -> new NotFoundException("WhatsApp configuration", orgId));
  }

  /**
   * Disconnect entirely — the credentials are deleted, not disabled. Idempotent: disconnecting an
   * org that was never connected is a no-op success, because the caller's intent ("we should not be
   * sending as this merchant") is already true.
   */
  public void disconnect(UUID orgId) {
    boolean removed =
        rootDsl.transactionResult(cfg -> repoFactory.create(DSL.using(cfg)).delete(orgId));
    log.info("WhatsApp disconnected for org {} (had a configuration: {})", orgId, removed);
  }

  /**
   * The config a sender needs, or empty — used by the notification pipeline, never by a handler.
   */
  public Optional<OrgWhatsAppConfig> activeConfig(UUID orgId) {
    return repoFactory.create(rootDsl).findByOrgId(orgId).filter(OrgWhatsAppConfig::isActive);
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ValidationException(field + " is required");
    }
  }
}
