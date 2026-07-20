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
    List<StorefrontPage> rows =
        dsl.selectFrom(STOREFRONT_PAGE)
            .where(STOREFRONT_PAGE.ORG_ID.eq(orgId))
            .orderBy(STOREFRONT_PAGE.KIND.asc())
            .fetch()
            .map(this::toPage);
    loadPairedBodies(rows);
    return rows;
  }

  @Override
  public Optional<StorefrontPage> findByKind(UUID orgId, PageKind kind) {
    Optional<StorefrontPage> found =
        dsl.selectFrom(STOREFRONT_PAGE)
            .where(STOREFRONT_PAGE.ORG_ID.eq(orgId).and(STOREFRONT_PAGE.KIND.eq(kind.wire())))
            .fetchOptional()
            .map(this::toPage);
    found.ifPresent(p -> loadPairedBodies(List.of(p)));
    return found;
  }

  /**
   * Fill each page's paired {@code bodyAr}/{@code bodyEn} from its per-language translation rows
   * (L6 — the legacy paired columns are gone, so the admin-plane view and the public resolver's
   * default-locale pick are sourced from {@code storefront_page_translation}). One batched query;
   * an org has at most a handful of pages.
   */
  private void loadPairedBodies(List<StorefrontPage> pages) {
    if (pages.isEmpty()) {
      return;
    }
    java.util.Map<UUID, StorefrontPage> byId = new java.util.HashMap<>();
    for (StorefrontPage p : pages) {
      byId.put(p.getId(), p);
    }
    dsl.selectFrom(STOREFRONT_PAGE_TRANSLATION)
        .where(STOREFRONT_PAGE_TRANSLATION.PAGE_ID.in(byId.keySet()))
        .fetch()
        .forEach(
            r -> {
              StorefrontPage p = byId.get(r.getPageId());
              if (p == null) {
                return;
              }
              if ("ar".equals(r.getLanguage())) {
                p.setBodyAr(r.getBody());
              } else if ("en".equals(r.getLanguage())) {
                p.setBodyEn(r.getBody());
              }
            });
  }

  @Override
  public StorefrontPage upsert(StorefrontPage p) {
    // Idempotent create-or-replace on the UNIQUE (org_id, kind) key: both bodies whole, updated_at
    // refreshed. INSERT … ON CONFLICT is one round-trip and inherently atomic.
    StorefrontPageRecord record =
        dsl.insertInto(STOREFRONT_PAGE)
            .set(STOREFRONT_PAGE.ORG_ID, p.getOrgId())
            .set(STOREFRONT_PAGE.KIND, p.getKind().wire())
            .set(STOREFRONT_PAGE.UPDATED_AT, OffsetDateTime.now())
            .onConflict(STOREFRONT_PAGE.ORG_ID, STOREFRONT_PAGE.KIND)
            .doUpdate()
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

  /**
   * Map a page shell. The paired {@code bodyAr}/{@code bodyEn} are NOT read here since L6 dropped
   * the legacy columns — {@link #loadPairedBodies} fills them from the translation table on the
   * read paths, and the write path carries them from the input (see {@code StorefrontPageService}).
   * A bare RETURNING record therefore maps with null bodies.
   */
  private StorefrontPage toPage(StorefrontPageRecord r) {
    StorefrontPage p = new StorefrontPage();
    p.setId(r.getId());
    p.setOrgId(r.getOrgId());
    p.setKind(PageKind.fromWire(r.getKind()));
    p.setUpdatedAt(r.getUpdatedAt());
    return p;
  }
}
