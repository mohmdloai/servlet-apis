package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.ORG_WHATSAPP_CONFIG;

import com.loai.inventory.domain.model.OrgWhatsAppConfig;
import com.loai.inventory.domain.repository.OrgWhatsAppConfigRepository;
import com.loai.inventory.repository.generated.tables.records.OrgWhatsappConfigRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class OrgWhatsAppConfigRepositoryImpl implements OrgWhatsAppConfigRepository {

  private final DSLContext dsl;

  public OrgWhatsAppConfigRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<OrgWhatsAppConfig> findByOrgId(UUID orgId) {
    return dsl.selectFrom(ORG_WHATSAPP_CONFIG)
        .where(ORG_WHATSAPP_CONFIG.ORG_ID.eq(orgId))
        .fetchOptional()
        .map(OrgWhatsAppConfigRepositoryImpl::toModel);
  }

  /**
   * Re-connecting always lands ACTIVE: a merchant who has just re-authorised with Meta is telling
   * us to send, and leaving them DISABLED because of an older pause would be a silent no-op they
   * would have no way to diagnose.
   */
  @Override
  public OrgWhatsAppConfig upsert(
      UUID orgId,
      String wabaId,
      String phoneNumberId,
      String displayPhoneNumber,
      String accessTokenEncrypted) {
    OrgWhatsappConfigRecord record =
        dsl.insertInto(ORG_WHATSAPP_CONFIG)
            .set(ORG_WHATSAPP_CONFIG.ORG_ID, orgId)
            .set(ORG_WHATSAPP_CONFIG.WABA_ID, wabaId)
            .set(ORG_WHATSAPP_CONFIG.PHONE_NUMBER_ID, phoneNumberId)
            .set(ORG_WHATSAPP_CONFIG.DISPLAY_PHONE_NUMBER, displayPhoneNumber)
            .set(ORG_WHATSAPP_CONFIG.ACCESS_TOKEN_ENCRYPTED, accessTokenEncrypted)
            .set(ORG_WHATSAPP_CONFIG.STATUS, OrgWhatsAppConfig.Status.ACTIVE.name())
            .onConflict(ORG_WHATSAPP_CONFIG.ORG_ID)
            .doUpdate()
            .set(ORG_WHATSAPP_CONFIG.WABA_ID, wabaId)
            .set(ORG_WHATSAPP_CONFIG.PHONE_NUMBER_ID, phoneNumberId)
            .set(ORG_WHATSAPP_CONFIG.DISPLAY_PHONE_NUMBER, displayPhoneNumber)
            .set(ORG_WHATSAPP_CONFIG.ACCESS_TOKEN_ENCRYPTED, accessTokenEncrypted)
            .set(ORG_WHATSAPP_CONFIG.STATUS, OrgWhatsAppConfig.Status.ACTIVE.name())
            .set(ORG_WHATSAPP_CONFIG.UPDATED_AT, OffsetDateTime.now())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("upsert of org_whatsapp_config returned no row");
    }
    return toModel(record);
  }

  @Override
  public Optional<OrgWhatsAppConfig> setStatus(UUID orgId, OrgWhatsAppConfig.Status status) {
    return Optional.ofNullable(
            dsl.update(ORG_WHATSAPP_CONFIG)
                .set(ORG_WHATSAPP_CONFIG.STATUS, status.name())
                .set(ORG_WHATSAPP_CONFIG.UPDATED_AT, OffsetDateTime.now())
                .where(ORG_WHATSAPP_CONFIG.ORG_ID.eq(orgId))
                .returning()
                .fetchOne())
        .map(OrgWhatsAppConfigRepositoryImpl::toModel);
  }

  @Override
  public boolean delete(UUID orgId) {
    return dsl.deleteFrom(ORG_WHATSAPP_CONFIG).where(ORG_WHATSAPP_CONFIG.ORG_ID.eq(orgId)).execute()
        > 0;
  }

  private static OrgWhatsAppConfig toModel(OrgWhatsappConfigRecord r) {
    return new OrgWhatsAppConfig(
        r.getOrgId(),
        r.getWabaId(),
        r.getPhoneNumberId(),
        r.getDisplayPhoneNumber(),
        r.getAccessTokenEncrypted(),
        OrgWhatsAppConfig.Status.valueOf(r.getStatus()),
        r.getConnectedAt(),
        r.getUpdatedAt());
  }
}
