package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.OrgPaymobConfig;
import java.util.Optional;
import java.util.UUID;

/** Per-merchant Paymob card credentials (V98). */
public interface OrgPaymobConfigRepository {

  /** The org's config, or empty when it has never connected — i.e. it has no card channel. */
  Optional<OrgPaymobConfig> findByOrgId(UUID orgId);

  /** Connect or re-connect: upsert on the org, always landing ACTIVE. */
  OrgPaymobConfig upsert(
      UUID orgId,
      String publicKey,
      String secretKeyEncrypted,
      String hmacSecretEncrypted,
      int cardIntegrationId,
      String region);

  /** Disconnect entirely — the credentials are removed, not just disabled. */
  boolean delete(UUID orgId);
}
