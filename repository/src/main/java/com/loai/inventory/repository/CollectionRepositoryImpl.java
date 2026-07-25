package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.COLLECTION;
import static com.loai.inventory.repository.generated.Tables.COLLECTION_LISTING;
import static com.loai.inventory.repository.generated.Tables.COLLECTION_TRANSLATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT_LISTING;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Collection;
import com.loai.inventory.domain.model.CollectionTranslation;
import com.loai.inventory.domain.repository.CollectionRepository;
import com.loai.inventory.repository.generated.tables.records.CollectionRecord;
import com.loai.inventory.repository.generated.tables.records.CollectionTranslationRecord;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

/** Named collections (roadmap item 8) — curation writes, the admin reads, and the public rail. */
public final class CollectionRepositoryImpl implements CollectionRepository {
  private static final Logger log = LoggerFactory.getLogger(CollectionRepositoryImpl.class);
  private final DSLContext dsl;

  public CollectionRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * The default-locale name row, aliased for the admin reads — the {@code CategoryRepositoryImpl}
   * pattern verbatim: the collection carries no name column, so the domain object's single {@code
   * name} is sourced from the translation row whose {@code language} equals the org's {@code
   * default_locale} (NOT NULL since V52).
   */
  private static final com.loai.inventory.repository.generated.tables.CollectionTranslation
      DEFAULT_CT = COLLECTION_TRANSLATION.as("default_colt");

  private SelectJoinStep<Record> selectCollection() {
    return dsl.select(COLLECTION.fields())
        .select(DEFAULT_CT.NAME)
        .from(COLLECTION)
        .join(ORG)
        .on(ORG.ID.eq(COLLECTION.ORG_ID))
        .leftJoin(DEFAULT_CT)
        .on(
            DEFAULT_CT
                .COLLECTION_ID
                .eq(COLLECTION.ID)
                .and(DEFAULT_CT.LANGUAGE.eq(ORG.DEFAULT_LOCALE)));
  }

  @Override
  public List<Collection> findAll(UUID orgId) {
    return selectCollection()
        .where(COLLECTION.ORG_ID.eq(orgId))
        .orderBy(COLLECTION.SORT_ORDER.asc(), COLLECTION.SLUG.asc())
        .fetch()
        .map(this::toCollection);
  }

  @Override
  public Optional<Collection> findById(UUID orgId, UUID id) {
    return selectCollection()
        .where(COLLECTION.ORG_ID.eq(orgId).and(COLLECTION.ID.eq(id)))
        .fetchOptional()
        .map(this::toCollection);
  }

  @Override
  public Optional<Collection> findBySlug(UUID orgId, String slug) {
    return selectCollection()
        .where(COLLECTION.ORG_ID.eq(orgId).and(COLLECTION.SLUG.eq(slug)))
        .fetchOptional()
        .map(this::toCollection);
  }

  @Override
  public long count(UUID orgId) {
    return dsl.fetchCount(dsl.selectFrom(COLLECTION).where(COLLECTION.ORG_ID.eq(orgId)));
  }

  @Override
  public boolean existsBySlug(UUID orgId, String slug) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(COLLECTION)
            .where(COLLECTION.ORG_ID.eq(orgId).and(COLLECTION.SLUG.eq(slug))));
  }

  @Override
  public boolean existsBySlugAndIdNot(UUID orgId, String slug, UUID excludeId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(COLLECTION)
            .where(
                COLLECTION
                    .ORG_ID
                    .eq(orgId)
                    .and(COLLECTION.SLUG.eq(slug))
                    .and(COLLECTION.ID.ne(excludeId))));
  }

  @Override
  public Collection insert(Collection collection) {
    CollectionRecord record =
        dsl.insertInto(COLLECTION)
            .set(COLLECTION.ORG_ID, collection.getOrgId())
            .set(COLLECTION.SLUG, collection.getSlug())
            .set(COLLECTION.SORT_ORDER, collection.getSortOrder())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into collection returned no record");
    }
    log.debug(
        "Inserted collection id={} orgId={} slug={}",
        record.getId(),
        record.getOrgId(),
        record.getSlug());
    return toCollection(record);
  }

  @Override
  public Collection update(Collection collection) {
    CollectionRecord record =
        dsl.update(COLLECTION)
            .set(COLLECTION.SLUG, collection.getSlug())
            .set(COLLECTION.SORT_ORDER, collection.getSortOrder())
            .set(COLLECTION.UPDATED_AT, OffsetDateTime.now())
            .where(
                COLLECTION
                    .ORG_ID
                    .eq(collection.getOrgId())
                    .and(COLLECTION.ID.eq(collection.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("Collection", collection.getId());
    }
    log.debug("Updated collection id={}", record.getId());
    return toCollection(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(COLLECTION)
            .where(COLLECTION.ORG_ID.eq(orgId).and(COLLECTION.ID.eq(id)))
            .execute();
    if (deleted == 0) {
      throw new NotFoundException("Collection", id);
    }
  }

  // --- translations ---

  @Override
  public void replaceTranslations(UUID collectionId, List<CollectionTranslation> translations) {
    dsl.deleteFrom(COLLECTION_TRANSLATION)
        .where(COLLECTION_TRANSLATION.COLLECTION_ID.eq(collectionId))
        .execute();
    if (translations == null || translations.isEmpty()) {
      return;
    }
    List<CollectionTranslationRecord> rows = new ArrayList<>(translations.size());
    for (CollectionTranslation t : translations) {
      CollectionTranslationRecord r = dsl.newRecord(COLLECTION_TRANSLATION);
      r.setCollectionId(collectionId);
      r.setLanguage(t.language());
      r.setName(t.name());
      rows.add(r);
    }
    dsl.batchInsert(rows).execute();
  }

  @Override
  public List<CollectionTranslation> findTranslations(UUID collectionId) {
    return dsl.selectFrom(COLLECTION_TRANSLATION)
        .where(COLLECTION_TRANSLATION.COLLECTION_ID.eq(collectionId))
        .orderBy(COLLECTION_TRANSLATION.LANGUAGE.asc())
        .fetch()
        .map(CollectionRepositoryImpl::toTranslation);
  }

  @Override
  public Map<UUID, List<CollectionTranslation>> findTranslationsForCollections(
      java.util.Collection<UUID> collectionIds) {
    if (collectionIds == null || collectionIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<CollectionTranslation>> byCollection = new HashMap<>();
    dsl.selectFrom(COLLECTION_TRANSLATION)
        .where(COLLECTION_TRANSLATION.COLLECTION_ID.in(collectionIds))
        .orderBy(COLLECTION_TRANSLATION.COLLECTION_ID.asc(), COLLECTION_TRANSLATION.LANGUAGE.asc())
        .fetch()
        .forEach(
            r ->
                byCollection
                    .computeIfAbsent(r.getCollectionId(), k -> new ArrayList<>())
                    .add(toTranslation(r)));
    return byCollection;
  }

  // --- membership ---

  @Override
  public List<UUID> findListingIds(UUID collectionId) {
    return dsl.select(COLLECTION_LISTING.PRODUCT_LISTING_ID)
        .from(COLLECTION_LISTING)
        .where(COLLECTION_LISTING.COLLECTION_ID.eq(collectionId))
        .orderBy(COLLECTION_LISTING.SORT.asc())
        .fetch(COLLECTION_LISTING.PRODUCT_LISTING_ID);
  }

  @Override
  public void setListings(UUID collectionId, List<UUID> orderedIds) {
    // Clear the whole membership first, then insert sort = index on the kept ids. Both statements
    // run
    // in the caller's transaction, so the set-replace is atomic and idempotent (the featured
    // set-replace pattern, scoped to one collection instead of the whole org).
    dsl.deleteFrom(COLLECTION_LISTING)
        .where(COLLECTION_LISTING.COLLECTION_ID.eq(collectionId))
        .execute();
    if (orderedIds == null || orderedIds.isEmpty()) {
      return;
    }
    for (int i = 0; i < orderedIds.size(); i++) {
      dsl.insertInto(COLLECTION_LISTING)
          .set(COLLECTION_LISTING.COLLECTION_ID, collectionId)
          .set(COLLECTION_LISTING.PRODUCT_LISTING_ID, orderedIds.get(i))
          .set(COLLECTION_LISTING.SORT, i)
          .execute();
    }
  }

  @Override
  public Map<UUID, Long> listingCounts(java.util.Collection<UUID> collectionIds) {
    if (collectionIds == null || collectionIds.isEmpty()) {
      return Map.of();
    }
    org.jooq.Field<Integer> total = org.jooq.impl.DSL.count();
    Map<UUID, Long> out = new HashMap<>();
    dsl.select(COLLECTION_LISTING.COLLECTION_ID, total)
        .from(COLLECTION_LISTING)
        .where(COLLECTION_LISTING.COLLECTION_ID.in(collectionIds))
        .groupBy(COLLECTION_LISTING.COLLECTION_ID)
        .fetch()
        .forEach(
            r ->
                out.put(
                    r.get(COLLECTION_LISTING.COLLECTION_ID),
                    r.get(total) == null ? 0L : r.get(total).longValue()));
    return out;
  }

  // --- public rail ---

  @Override
  public List<Collection> findPublicRail(UUID orgId, String locale, String defaultLocale) {
    var reqT = COLLECTION_TRANSLATION.as("rail_req");
    var defT = COLLECTION_TRANSLATION.as("rail_def");
    org.jooq.Field<String> name =
        org.jooq.impl.DSL.coalesce(reqT.NAME, defT.NAME, COLLECTION.SLUG).as("rail_name");
    return dsl.select(COLLECTION.fields())
        .select(name)
        .from(COLLECTION)
        .leftJoin(reqT)
        .on(reqT.COLLECTION_ID.eq(COLLECTION.ID).and(reqT.LANGUAGE.eq(locale)))
        .leftJoin(defT)
        .on(defT.COLLECTION_ID.eq(COLLECTION.ID).and(defT.LANGUAGE.eq(defaultLocale)))
        .where(
            COLLECTION
                .ORG_ID
                .eq(orgId)
                // An empty shelf is never advertised: EXISTS ≥1 PUBLISHED listing in the
                // collection.
                .and(
                    org.jooq.impl.DSL.exists(
                        org.jooq
                            .impl
                            .DSL
                            .selectOne()
                            .from(COLLECTION_LISTING)
                            .join(PRODUCT_LISTING)
                            .on(PRODUCT_LISTING.ID.eq(COLLECTION_LISTING.PRODUCT_LISTING_ID))
                            .where(
                                COLLECTION_LISTING
                                    .COLLECTION_ID
                                    .eq(COLLECTION.ID)
                                    .and(
                                        PRODUCT_LISTING.STATUS.eq(
                                            com.loai.inventory.repository.generated.enums
                                                .ListingStatus.PUBLISHED))))))
        .orderBy(COLLECTION.SORT_ORDER.asc(), COLLECTION.SLUG.asc())
        .fetch(
            r ->
                new Collection(
                    r.get(COLLECTION.ID),
                    r.get(COLLECTION.ORG_ID),
                    r.get(COLLECTION.SLUG),
                    r.get(name),
                    r.get(COLLECTION.SORT_ORDER),
                    r.get(COLLECTION.CREATED_AT),
                    r.get(COLLECTION.UPDATED_AT)));
  }

  private static CollectionTranslation toTranslation(CollectionTranslationRecord r) {
    return new CollectionTranslation(r.getLanguage(), r.getName());
  }

  /**
   * Map a collection row. The single {@code name} is the org's default-locale translation joined in
   * by {@link #selectCollection()}; a bare record from an INSERT/UPDATE {@code RETURNING} does not
   * carry it, so {@code field(...) == null} → name null (the service fills those return values from
   * the write's default-locale row).
   */
  private Collection toCollection(Record r) {
    String name = r.field(DEFAULT_CT.NAME) == null ? null : r.get(DEFAULT_CT.NAME);
    return new Collection(
        r.get(COLLECTION.ID),
        r.get(COLLECTION.ORG_ID),
        r.get(COLLECTION.SLUG),
        name,
        r.get(COLLECTION.SORT_ORDER),
        r.get(COLLECTION.CREATED_AT),
        r.get(COLLECTION.UPDATED_AT));
  }
}
