package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A merchant's connected Paymob card account (V98) — the per-tenant half of the owner decision that
 * each org connects its own Paymob merchant account, so card revenue lands in the merchant's
 * pocket, not the platform's.
 *
 * <p>{@code secretKeyEncrypted} and {@code hmacSecretEncrypted} are ciphertext ({@code SecretBox},
 * AES-GCM under a platform key) and stay that way in this model: they are decrypted only at the
 * moment of use (minting an intention, verifying a webhook — slice 2), and <b>no read path returns
 * them</b> — not to the merchant who supplied them, not to a platform ADMIN. {@code publicKey} is
 * the one credential meant to leave the server, handed to the shopper's browser to open Unified
 * Checkout. Absence of a row means the org has no card channel at all.
 */
public record OrgPaymobConfig(
    UUID orgId,
    String publicKey,
    String secretKeyEncrypted,
    String hmacSecretEncrypted,
    /**
     * The account's legacy API key (V100, {@code stories/paymob_card_reliability.md}), sealed like
     * the other two. Paymob's transaction-inquiry endpoint authenticates only with the auth token
     * minted from it — the secret key is refused there — so it is what the poller uses to settle a
     * payment whose webhook never arrived. {@code null} on a row connected before V100: such an org
     * offers card but cannot be inquired about, and its stuck intents can only expire.
     */
    String apiKeyEncrypted,
    int cardIntegrationId,
    String region,
    Status status,
    OffsetDateTime connectedAt,
    OffsetDateTime updatedAt) {

  /**
   * {@code DISABLED} keeps the credentials but stops offering card — a merchant can pause the
   * channel without re-entering their Paymob credentials. Disconnecting entirely is the "delete"
   * verb.
   */
  public enum Status {
    ACTIVE,
    DISABLED
  }

  public boolean isActive() {
    return status == Status.ACTIVE;
  }

  /** Whether the poller can ask Paymob about this org's intents at all. */
  public boolean canInquire() {
    return apiKeyEncrypted != null && !apiKeyEncrypted.isBlank();
  }
}
