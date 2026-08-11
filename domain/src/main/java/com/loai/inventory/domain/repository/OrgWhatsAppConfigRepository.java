package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.OrgWhatsAppConfig;
import java.util.Optional;
import java.util.UUID;

/** Per-merchant WhatsApp Business Account credentials (V82). */
public interface OrgWhatsAppConfigRepository {

  /** The org's config, or empty when it has never connected — i.e. it has no WhatsApp channel. */
  Optional<OrgWhatsAppConfig> findByOrgId(UUID orgId);

  /** Connect or re-connect: upsert on the org, always landing ACTIVE. */
  OrgWhatsAppConfig upsert(
      UUID orgId,
      String wabaId,
      String phoneNumberId,
      String displayPhoneNumber,
      String accessTokenEncrypted);

  /** Pause or resume sending without discarding the credentials. Returns the updated row. */
  Optional<OrgWhatsAppConfig> setStatus(UUID orgId, OrgWhatsAppConfig.Status status);

  /** Disconnect entirely — the credentials are removed, not just disabled. */
  boolean delete(UUID orgId);
}
