package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.ORG;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.repository.generated.tables.records.OrgRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OrgRepositoryImpl implements OrgRepository {
  private static final Logger log = LoggerFactory.getLogger(OrgRepositoryImpl.class);
  private final DSLContext dsl;

  public OrgRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<Org> findById(UUID id) {
    return dsl.selectFrom(ORG).where(ORG.ID.eq(id)).fetchOptional().map(this::toOrg);
  }

  @Override
  public Optional<Org> findBySlug(String slug) {
    return dsl.selectFrom(ORG).where(ORG.SLUG.eq(slug)).fetchOptional().map(this::toOrg);
  }

  @Override
  public List<Org> findAll(int offset, int limit) {
    return dsl.selectFrom(ORG)
        .orderBy(ORG.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toOrg);
  }

  @Override
  public List<Org> findAll(int offset, int limit, Boolean active) {
    Condition condition = active == null ? DSL.noCondition() : ORG.ACTIVE.eq(active);
    return dsl.selectFrom(ORG)
        .where(condition)
        .orderBy(ORG.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toOrg);
  }

  @Override
  public List<Org> findAllByIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) return List.of();
    return dsl.selectFrom(ORG).where(ORG.ID.in(ids)).fetch().map(this::toOrg);
  }

  @Override
  public long count() {
    return dsl.fetchCount(ORG);
  }

  @Override
  public long count(Boolean active) {
    Condition condition = active == null ? DSL.noCondition() : ORG.ACTIVE.eq(active);
    return dsl.fetchCount(ORG, condition);
  }

  @Override
  public Org insert(Org org) {
    OrgRecord record =
        dsl.insertInto(ORG)
            .set(ORG.NAME, org.getName())
            .set(ORG.SLUG, org.getSlug())
            .set(ORG.ACTIVE, org.isActive())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into org returned no record");
    }
    log.debug("Inserted org id={} slug={}", record.getId(), record.getSlug());
    return toOrg(record);
  }

  @Override
  public Org update(Org org) {
    OrgRecord record =
        dsl.update(ORG)
            .set(ORG.NAME, org.getName())
            .set(ORG.ACTIVE, org.isActive())
            .set(ORG.REFUND_APPROVAL_THRESHOLD, org.getRefundApprovalThreshold())
            .set(ORG.ORDER_TTL_MINUTES, org.getOrderTtlMinutes())
            .set(ORG.LEGAL_NAME, org.getLegalName())
            .set(ORG.TAX_REGISTRATION_NUMBER, org.getTaxRegistrationNumber())
            .set(ORG.ADDRESS_LINE1, org.getAddressLine1())
            .set(ORG.ADDRESS_LINE2, org.getAddressLine2())
            .set(ORG.CITY, org.getCity())
            .set(ORG.COUNTRY, org.getCountry())
            .set(ORG.PHONE, org.getPhone())
            .set(ORG.CONTACT_EMAIL, org.getContactEmail())
            .set(ORG.LOGO_OBJECT_KEY, org.getLogoObjectKey())
            .set(ORG.THEME_COLOR, org.getThemeColor())
            .set(ORG.INSTAPAY_HANDLE, org.getInstapayHandle())
            .set(ORG.PAYMENT_INSTRUCTIONS, org.getPaymentInstructions())
            .set(ORG.DEFAULT_LOCALE, org.getDefaultLocale())
            .set(ORG.UPDATED_AT, OffsetDateTime.now())
            .where(ORG.ID.eq(org.getId()))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("Org", org.getId());
    }
    log.debug("Updated org id={}", record.getId());
    return toOrg(record);
  }

  @Override
  public Org setSuspension(UUID orgId, boolean suspended, String reason) {
    OrgRecord record =
        dsl.update(ORG)
            .set(ORG.ACTIVE, !suspended)
            .set(ORG.SUSPENDED_AT, suspended ? OffsetDateTime.now() : null)
            .set(ORG.SUSPENDED_REASON, suspended ? reason : null)
            .set(ORG.UPDATED_AT, OffsetDateTime.now())
            .where(ORG.ID.eq(orgId))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("Org", orgId);
    }
    log.debug("Org id={} suspension set to {}", orgId, suspended);
    return toOrg(record);
  }

  @Override
  public void deleteById(UUID id) {
    int deleted = dsl.deleteFrom(ORG).where(ORG.ID.eq(id)).execute();
    if (deleted == 0) {
      throw new NotFoundException("Org", id);
    }
  }

  @Override
  public boolean existsBySlug(String slug) {
    return dsl.fetchExists(dsl.selectOne().from(ORG).where(ORG.SLUG.eq(slug)));
  }

  private Org toOrg(OrgRecord r) {
    Org org =
        new Org(
            r.getId(),
            r.getName(),
            r.getSlug(),
            r.getActive(),
            r.getRefundApprovalThreshold(),
            r.getOrderTtlMinutes(),
            r.getCreatedAt(),
            r.getUpdatedAt());
    org.setLegalName(r.getLegalName());
    org.setTaxRegistrationNumber(r.getTaxRegistrationNumber());
    org.setAddressLine1(r.getAddressLine1());
    org.setAddressLine2(r.getAddressLine2());
    org.setCity(r.getCity());
    org.setCountry(r.getCountry());
    org.setPhone(r.getPhone());
    org.setContactEmail(r.getContactEmail());
    org.setLogoObjectKey(r.getLogoObjectKey());
    org.setThemeColor(r.getThemeColor());
    org.setInstapayHandle(r.getInstapayHandle());
    org.setPaymentInstructions(r.getPaymentInstructions());
    org.setDefaultLocale(r.getDefaultLocale());
    return org;
  }
}
