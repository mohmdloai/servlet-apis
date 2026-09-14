package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.ORG_PAYMOB_CONFIG;

import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.repository.OrgPaymobConfigRepository;
import com.loai.inventory.repository.generated.tables.records.OrgPaymobConfigRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class OrgPaymobConfigRepositoryImpl implements OrgPaymobConfigRepository {

  private final DSLContext dsl;

  public OrgPaymobConfigRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<OrgPaymobConfig> findByOrgId(UUID orgId) {
    return dsl.selectFrom(ORG_PAYMOB_CONFIG)
        .where(ORG_PAYMOB_CONFIG.ORG_ID.eq(orgId))
        .fetchOptional()
        .map(OrgPaymobConfigRepositoryImpl::toModel);
  }

  /**
   * Re-connecting always lands ACTIVE, and replaces every credential — there is no PATCH, so a
   * partial update is never possible at this layer either.
   */
  @Override
  public OrgPaymobConfig upsert(
      UUID orgId,
      String publicKey,
      String secretKeyEncrypted,
      String hmacSecretEncrypted,
      String apiKeyEncrypted,
      int cardIntegrationId,
      String region) {
    OrgPaymobConfigRecord record =
        dsl.insertInto(ORG_PAYMOB_CONFIG)
            .set(ORG_PAYMOB_CONFIG.ORG_ID, orgId)
            .set(ORG_PAYMOB_CONFIG.PUBLIC_KEY, publicKey)
            .set(ORG_PAYMOB_CONFIG.SECRET_KEY_ENCRYPTED, secretKeyEncrypted)
            .set(ORG_PAYMOB_CONFIG.HMAC_SECRET_ENCRYPTED, hmacSecretEncrypted)
            .set(ORG_PAYMOB_CONFIG.API_KEY_ENCRYPTED, apiKeyEncrypted)
            .set(ORG_PAYMOB_CONFIG.CARD_INTEGRATION_ID, cardIntegrationId)
            .set(ORG_PAYMOB_CONFIG.REGION, region)
            .set(ORG_PAYMOB_CONFIG.STATUS, OrgPaymobConfig.Status.ACTIVE.name())
            .onConflict(ORG_PAYMOB_CONFIG.ORG_ID)
            .doUpdate()
            .set(ORG_PAYMOB_CONFIG.PUBLIC_KEY, publicKey)
            .set(ORG_PAYMOB_CONFIG.SECRET_KEY_ENCRYPTED, secretKeyEncrypted)
            .set(ORG_PAYMOB_CONFIG.HMAC_SECRET_ENCRYPTED, hmacSecretEncrypted)
            .set(ORG_PAYMOB_CONFIG.API_KEY_ENCRYPTED, apiKeyEncrypted)
            .set(ORG_PAYMOB_CONFIG.CARD_INTEGRATION_ID, cardIntegrationId)
            .set(ORG_PAYMOB_CONFIG.REGION, region)
            .set(ORG_PAYMOB_CONFIG.STATUS, OrgPaymobConfig.Status.ACTIVE.name())
            .set(ORG_PAYMOB_CONFIG.UPDATED_AT, OffsetDateTime.now())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("upsert of org_paymob_config returned no row");
    }
    return toModel(record);
  }

  @Override
  public boolean delete(UUID orgId) {
    return dsl.deleteFrom(ORG_PAYMOB_CONFIG).where(ORG_PAYMOB_CONFIG.ORG_ID.eq(orgId)).execute()
        > 0;
  }

  private static OrgPaymobConfig toModel(OrgPaymobConfigRecord r) {
    return new OrgPaymobConfig(
        r.getOrgId(),
        r.getPublicKey(),
        r.getSecretKeyEncrypted(),
        r.getHmacSecretEncrypted(),
        r.getApiKeyEncrypted(),
        r.getCardIntegrationId(),
        r.getRegion(),
        OrgPaymobConfig.Status.valueOf(r.getStatus()),
        r.getConnectedAt(),
        r.getUpdatedAt());
  }
}
