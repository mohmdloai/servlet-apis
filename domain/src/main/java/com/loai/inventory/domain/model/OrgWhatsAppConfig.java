package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A merchant's connected WhatsApp Business Account (V82) — the per-tenant half of the owner's
 * "per-merchant WABA, not a platform sender" decision, so every message carries the merchant's own
 * number and brand.
 *
 * <p>{@code accessTokenEncrypted} is ciphertext ({@code SecretBox}, AES-GCM under a platform key)
 * and stays that way in this model: it is decrypted only at the moment of a send, and <b>no read
 * path returns it</b> — not to the merchant who supplied it, not to a platform ADMIN. Absence of a
 * row means the org has no WhatsApp channel at all, which is a suppressed channel rather than an
 * error.
 */
public record OrgWhatsAppConfig(
    UUID orgId,
    String wabaId,
    String phoneNumberId,
    String displayPhoneNumber,
    String accessTokenEncrypted,
    Status status,
    OffsetDateTime connectedAt,
    OffsetDateTime updatedAt) {

  /**
   * {@code DISABLED} keeps the credentials but stops sending — the merchant can pause the channel
   * (or an operator can, after repeated provider failures) without re-doing Meta's onboarding.
   * Removing the row entirely is the "disconnect" verb.
   */
  public enum Status {
    ACTIVE,
    DISABLED
  }

  public boolean isActive() {
    return status == Status.ACTIVE;
  }
}
