package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CATEGORY;
import static com.loai.inventory.repository.generated.Tables.CATEGORY_TRANSLATION;
import static com.loai.inventory.repository.generated.Tables.ORG;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.CategoryTranslation;
import com.loai.inventory.domain.repository.CategoryRepository;
import com.loai.inventory.repository.generated.tables.records.CategoryRecord;
import com.loai.inventory.repository.generated.tables.records.CategoryTranslationRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CategoryRepositoryImpl implements CategoryRepository {
  private static final Logger log = LoggerFactory.getLogger(CategoryRepositoryImpl.class);
  private final DSLContext dsl;

  public CategoryRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * The default-locale translation row, aliased for the base reads. Since L6 dropped the legacy
   * {@code category.name} column, the domain object's single {@code name} (the admin-plane display
   * value) is sourced here: the row whose {@code language} equals the org's {@code default_locale}
   * (NOT NULL since V52; a default-locale row is guaranteed by the write rule + L1 backfill).
   */
  private static final com.loai.inventory.repository.generated.tables.CategoryTranslation
      DEFAULT_CT = CATEGORY_TRANSLATION.as("default_ct");

  /**
   * {@code SELECT category.*, default_ct.name} joined to the org's default-locale translation row —
   * the base read shape behind every category fetch. {@link #toCategory(Record)} reads the resolved
   * scalar from it.
   */
  private SelectJoinStep<Record> selectCategory() {
    return dsl.select(CATEGORY.fields())
        .select(DEFAULT_CT.NAME)
        .from(CATEGORY)
        .join(ORG)
        .on(ORG.ID.eq(CATEGORY.ORG_ID))
        .leftJoin(DEFAULT_CT)
        .on(DEFAULT_CT.CATEGORY_ID.eq(CATEGORY.ID).and(DEFAULT_CT.LANGUAGE.eq(ORG.DEFAULT_LOCALE)));
  }

  @Override
  public Optional<Category> findById(UUID orgId, UUID id) {
    return selectCategory()
        .where(CATEGORY.ORG_ID.eq(orgId).and(CATEGORY.ID.eq(id)))
        .fetchOptional()
        .map(this::toCategory);
  }

  @Override
  public Optional<Category> findBySlug(UUID orgId, String slug) {
    return selectCategory()
        .where(CATEGORY.ORG_ID.eq(orgId).and(CATEGORY.SLUG.eq(slug)))
        .fetchOptional()
        .map(this::toCategory);
  }

  @Override
  public List<Category> findByIds(UUID orgId, java.util.Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return java.util.List.of();
    }
    return selectCategory()
        .where(CATEGORY.ORG_ID.eq(orgId).and(CATEGORY.ID.in(ids)))
        .fetch()
        .map(this::toCategory);
  }

  @Override
  public List<Category> findAll(UUID orgId, int offset, int limit) {
    return selectCategory()
        .where(CATEGORY.ORG_ID.eq(orgId))
        .orderBy(CATEGORY.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toCategory);
  }

  @Override
  public List<Category> findAllByOrg(UUID orgId) {
    return selectCategory()
        .where(CATEGORY.ORG_ID.eq(orgId))
        .orderBy(CATEGORY.CREATED_AT.desc())
        .fetch()
        .map(this::toCategory);
  }

  @Override
  public long count(UUID orgId) {
    return dsl.fetchCount(dsl.selectFrom(CATEGORY).where(CATEGORY.ORG_ID.eq(orgId)));
  }

  @Override
  public Category insert(Category category) {
    CategoryRecord record =
        dsl.insertInto(CATEGORY)
            .set(CATEGORY.ORG_ID, category.getOrgId())
            .set(CATEGORY.PARENT_CATEGORY_ID, category.getParentCategoryId())
            .set(CATEGORY.SLUG, category.getSlug())
            .set(CATEGORY.IMAGE_OBJECT_KEY, category.getImageObjectKey())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into category returned no record");
    }
    log.debug(
        "Inserted category id={} orgId={} slug={}",
        record.getId(),
        record.getOrgId(),
        record.getSlug());
    return toCategory(record);
  }

  @Override
  public Category update(Category category) {
    CategoryRecord record =
        dsl.update(CATEGORY)
            .set(CATEGORY.PARENT_CATEGORY_ID, category.getParentCategoryId())
            .set(CATEGORY.SLUG, category.getSlug())
            .set(CATEGORY.IMAGE_OBJECT_KEY, category.getImageObjectKey())
            .set(CATEGORY.UPDATED_AT, OffsetDateTime.now())
            .where(CATEGORY.ORG_ID.eq(category.getOrgId()).and(CATEGORY.ID.eq(category.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("Category", category.getId());
    }
    log.debug("Updated category id={}", record.getId());
    return toCategory(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(CATEGORY).where(CATEGORY.ORG_ID.eq(orgId).and(CATEGORY.ID.eq(id))).execute();
    if (deleted == 0) {
      throw new NotFoundException("Category", id);
    }
  }

  @Override
  public boolean existsById(UUID orgId, UUID id) {
    return dsl.fetchExists(
        dsl.selectOne().from(CATEGORY).where(CATEGORY.ORG_ID.eq(orgId).and(CATEGORY.ID.eq(id))));
  }

  @Override
  public boolean existsBySlug(UUID orgId, String slug) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(CATEGORY)
            .where(CATEGORY.ORG_ID.eq(orgId).and(CATEGORY.SLUG.eq(slug))));
  }

  @Override
  public boolean existsBySlugAndIdNot(UUID orgId, String slug, UUID excludeId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(CATEGORY)
            .where(
                CATEGORY
                    .ORG_ID
                    .eq(orgId)
                    .and(CATEGORY.SLUG.eq(slug))
                    .and(CATEGORY.ID.ne(excludeId))));
  }

  @Override
  public boolean hasChildren(UUID orgId, UUID id) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(CATEGORY)
            .where(CATEGORY.ORG_ID.eq(orgId).and(CATEGORY.PARENT_CATEGORY_ID.eq(id))));
  }

  // --- translations (content-localization slice L3) ---

  @Override
  public void replaceTranslations(UUID categoryId, List<CategoryTranslation> translations) {
    dsl.deleteFrom(CATEGORY_TRANSLATION)
        .where(CATEGORY_TRANSLATION.CATEGORY_ID.eq(categoryId))
        .execute();
    if (translations == null || translations.isEmpty()) {
      return;
    }
    List<CategoryTranslationRecord> rows = new ArrayList<>(translations.size());
    for (CategoryTranslation t : translations) {
      CategoryTranslationRecord r = dsl.newRecord(CATEGORY_TRANSLATION);
      r.setCategoryId(categoryId);
      r.setLanguage(t.language());
      r.setName(t.name());
      rows.add(r);
    }
    dsl.batchInsert(rows).execute();
  }

  @Override
  public List<CategoryTranslation> findTranslations(UUID categoryId) {
    return dsl.selectFrom(CATEGORY_TRANSLATION)
        .where(CATEGORY_TRANSLATION.CATEGORY_ID.eq(categoryId))
        .orderBy(CATEGORY_TRANSLATION.LANGUAGE.asc())
        .fetch()
        .map(CategoryRepositoryImpl::toTranslation);
  }

  @Override
  public Map<UUID, List<CategoryTranslation>> findTranslationsForCategories(
      Collection<UUID> categoryIds) {
    if (categoryIds == null || categoryIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<CategoryTranslation>> byCategory = new HashMap<>();
    dsl.selectFrom(CATEGORY_TRANSLATION)
        .where(CATEGORY_TRANSLATION.CATEGORY_ID.in(categoryIds))
        .orderBy(CATEGORY_TRANSLATION.CATEGORY_ID.asc(), CATEGORY_TRANSLATION.LANGUAGE.asc())
        .fetch()
        .forEach(
            r ->
                byCategory
                    .computeIfAbsent(r.getCategoryId(), k -> new ArrayList<>())
                    .add(toTranslation(r)));
    return byCategory;
  }

  private static CategoryTranslation toTranslation(CategoryTranslationRecord r) {
    return new CategoryTranslation(r.getLanguage(), r.getName());
  }

  /**
   * Map a category row. The single {@code name} is the org's default-locale translation, joined in
   * by {@link #selectCategory()}: a joined read carries it; a bare {@code category} record from an
   * INSERT/UPDATE {@code RETURNING} does not, so {@code field(...) == null} → name null (the
   * service fills those return values from the write's default-locale row — the column is gone
   * since L6).
   */
  private Category toCategory(Record r) {
    String name = r.field(DEFAULT_CT.NAME) == null ? null : r.get(DEFAULT_CT.NAME);
    Category c =
        new Category(
            r.get(CATEGORY.ID),
            r.get(CATEGORY.ORG_ID),
            r.get(CATEGORY.PARENT_CATEGORY_ID),
            name,
            r.get(CATEGORY.SLUG),
            r.get(CATEGORY.CREATED_AT),
            r.get(CATEGORY.UPDATED_AT));
    c.setImageObjectKey(r.get(CATEGORY.IMAGE_OBJECT_KEY));
    return c;
  }
}
