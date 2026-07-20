package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.STOREFRONT_PAGE;
import static com.loai.inventory.repository.generated.Tables.STOREFRONT_PAGE_TRANSLATION;

import com.loai.inventory.domain.model.PageKind;
import com.loai.inventory.domain.model.StorefrontPage;
import com.loai.inventory.domain.model.StorefrontPageTranslation;
import com.loai.inventory.domain.repository.StorefrontPageRepository;
import com.loai.inventory.repository.generated.tables.records.StorefrontPageRecord;
import com.loai.inventory.repository.generated.tables.records.StorefrontPageTranslationRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class StorefrontPageRepositoryImpl implements StorefrontPageRepository {

  private final DSLContext dsl;

  public StorefrontPageRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<StorefrontPage> findAllByOrg(UUID orgId) {
    return dsl.selectFrom(STOREFRONT_PAGE)
        .where(STOREFRONT_PAGE.ORG_ID.eq(orgId))
        .orderBy(STOREFRONT_PAGE.KIND.asc())
        .fetch()
        .map(this::toPage);
  }

  @Override
  public Optional<StorefrontPage> findByKind(UUID orgId, PageKind kind) {
    return dsl.selectFrom(STOREFRONT_PAGE)
        .where(STOREFRONT_PAGE.ORG_ID.eq(orgId).and(STOREFRONT_PAGE.KIND.eq(kind.wire())))
        .fetchOptional()
        .map(this::toPage);
  }

  @Override
  public StorefrontPage upsert(StorefrontPage p) {
    // Idempotent create-or-replace on the UNIQUE (org_id, kind) key: both bodies whole, updated_at
    // refreshed. INSERT … ON CONFLICT is one round-trip and inherently atomic.
    StorefrontPageRecord record =
        dsl.insertInto(STOREFRONT_PAGE)
            .set(STOREFRONT_PAGE.ORG_ID, p.getOrgId())
            .set(STOREFRONT_PAGE.KIND, p.getKind().wire())
            .set(STOREFRONT_PAGE.BODY_AR, p.getBodyAr())
            .set(STOREFRONT_PAGE.BODY_EN, p.getBodyEn())
            .set(STOREFRONT_PAGE.UPDATED_AT, OffsetDateTime.now())
            .onConflict(STOREFRONT_PAGE.ORG_ID, STOREFRONT_PAGE.KIND)
            .doUpdate()
            .set(STOREFRONT_PAGE.BODY_AR, p.getBodyAr())
            .set(STOREFRONT_PAGE.BODY_EN, p.getBodyEn())
            .set(STOREFRONT_PAGE.UPDATED_AT, OffsetDateTime.now())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("UPSERT into storefront_page returned no record");
    }
    return toPage(record);
  }

  @Override
  public boolean deleteByKind(UUID orgId, PageKind kind) {
    int deleted =
        dsl.deleteFrom(STOREFRONT_PAGE)
            .where(STOREFRONT_PAGE.ORG_ID.eq(orgId).and(STOREFRONT_PAGE.KIND.eq(kind.wire())))
            .execute();
    return deleted > 0;
  }

  // --- translations (content-localization slice L4) ---

  @Override
  public void replaceTranslations(UUID pageId, List<StorefrontPageTranslation> translations) {
    dsl.deleteFrom(STOREFRONT_PAGE_TRANSLATION)
        .where(STOREFRONT_PAGE_TRANSLATION.PAGE_ID.eq(pageId))
        .execute();
    if (translations == null || translations.isEmpty()) {
      return;
    }
    List<StorefrontPageTranslationRecord> rows = new ArrayList<>(translations.size());
    for (StorefrontPageTranslation t : translations) {
      StorefrontPageTranslationRecord r = dsl.newRecord(STOREFRONT_PAGE_TRANSLATION);
      r.setPageId(pageId);
      r.setLanguage(t.language());
      r.setBody(t.body());
      rows.add(r);
    }
    dsl.batchInsert(rows).execute();
  }

  @Override
  public List<StorefrontPageTranslation> findTranslations(UUID pageId) {
    return dsl.selectFrom(STOREFRONT_PAGE_TRANSLATION)
        .where(STOREFRONT_PAGE_TRANSLATION.PAGE_ID.eq(pageId))
        .orderBy(STOREFRONT_PAGE_TRANSLATION.LANGUAGE.asc())
        .fetch()
        .map(r -> new StorefrontPageTranslation(r.getLanguage(), r.getBody()));
  }

  private StorefrontPage toPage(StorefrontPageRecord r) {
    StorefrontPage p = new StorefrontPage();
    p.setId(r.getId());
    p.setOrgId(r.getOrgId());
    p.setKind(PageKind.fromWire(r.getKind()));
    p.setBodyAr(r.getBodyAr());
    p.setBodyEn(r.getBodyEn());
    p.setUpdatedAt(r.getUpdatedAt());
    return p;
  }
}
