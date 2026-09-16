package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.SUPPLIER;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.text.Phone;
import com.loai.inventory.domain.model.Supplier;
import com.loai.inventory.domain.repository.SupplierRepository;
import com.loai.inventory.repository.generated.tables.records.SupplierRecord;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

public final class SupplierRepositoryImpl implements SupplierRepository {
  private final DSLContext dsl;

  public SupplierRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<Supplier> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(SUPPLIER)
        .where(SUPPLIER.ORG_ID.eq(orgId).and(SUPPLIER.ID.eq(id)))
        .fetchOptional()
        .map(SupplierRepositoryImpl::toSupplier);
  }

  @Override
  public Map<UUID, Supplier> findByIds(UUID orgId, Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Supplier> out = new HashMap<>();
    dsl.selectFrom(SUPPLIER)
        .where(SUPPLIER.ORG_ID.eq(orgId).and(SUPPLIER.ID.in(ids)))
        .fetch()
        .forEach(r -> out.put(r.getId(), toSupplier(r)));
    return out;
  }

  @Override
  public List<Supplier> findAll(UUID orgId, String q, Boolean active, int offset, int limit) {
    return dsl.selectFrom(SUPPLIER)
        // name ASC under V62's und-x-icu collation orders Arabic and Latin properly; id ASC breaks
        // the tie. Never re-sorted by a filter — see SupplierRepository#findAll.
        .where(searchCondition(orgId, q, active))
        .orderBy(SUPPLIER.NAME.asc(), SUPPLIER.ID.asc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(SupplierRepositoryImpl::toSupplier);
  }

  @Override
  public long count(UUID orgId, String q, Boolean active) {
    return dsl.fetchCount(dsl.selectFrom(SUPPLIER).where(searchCondition(orgId, q, active)));
  }

  /**
   * One predicate, two callers (rows and total), so a list whose count disagrees with its rows is
   * structurally impossible.
   *
   * <p>Each leg normalizes with the function that wrote the column — {@code fold_search} on both
   * sides of the generated {@code name_search} (so {@code احمد} finds {@code أحمد}), a
   * case-insensitive substring on the email {@code Text.normalizeEmail} wrote. Phone is
   * deliberately not a leg (the {@code search_doesNotMatchPhone} pin, one table over).
   */
  private Condition searchCondition(UUID orgId, String q, Boolean active) {
    Condition c = SUPPLIER.ORG_ID.eq(orgId);
    if (active != null) {
      c = c.and(SUPPLIER.ACTIVE.eq(active));
    }
    if (q == null || q.isBlank()) {
      return c;
    }
    String term = q.trim();
    Field<String> folded = DSL.field("fold_search({0})", String.class, DSL.val(term));
    Condition byName =
        SUPPLIER.NAME_SEARCH.like(DSL.concat(DSL.inline("%"), folded, DSL.inline("%")));
    return c.and(byName.or(SUPPLIER.EMAIL.containsIgnoreCase(term)));
  }

  @Override
  public Optional<Supplier> findByFoldedName(UUID orgId, String name) {
    Field<String> folded = DSL.field("fold_search({0})", String.class, DSL.val(name));
    return dsl.selectFrom(SUPPLIER)
        .where(SUPPLIER.ORG_ID.eq(orgId).and(SUPPLIER.NAME_SEARCH.eq(folded)))
        .fetchOptional()
        .map(SupplierRepositoryImpl::toSupplier);
  }

  @Override
  public Supplier insert(Supplier s) {
    SupplierRecord record =
        dsl.insertInto(SUPPLIER)
            .set(SUPPLIER.ORG_ID, s.getOrgId())
            .set(SUPPLIER.NAME, s.getName())
            .set(SUPPLIER.PHONE, s.getPhone())
            // Derived here, so no write path can persist a phone without its dialable twin.
            .set(SUPPLIER.PHONE_E164, Phone.toE164(s.getPhone()))
            .set(SUPPLIER.EMAIL, s.getEmail())
            .set(SUPPLIER.ADDRESS, s.getAddress())
            .set(SUPPLIER.NOTES, s.getNotes())
            .set(SUPPLIER.ACTIVE, s.isActive())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into supplier returned no record");
    }
    return toSupplier(record);
  }

  @Override
  public Supplier update(Supplier s) {
    SupplierRecord record =
        dsl.update(SUPPLIER)
            .set(SUPPLIER.NAME, s.getName())
            .set(SUPPLIER.PHONE, s.getPhone())
            .set(SUPPLIER.PHONE_E164, Phone.toE164(s.getPhone()))
            .set(SUPPLIER.EMAIL, s.getEmail())
            .set(SUPPLIER.ADDRESS, s.getAddress())
            .set(SUPPLIER.NOTES, s.getNotes())
            .set(SUPPLIER.ACTIVE, s.isActive())
            .set(SUPPLIER.UPDATED_AT, OffsetDateTime.now())
            .where(SUPPLIER.ORG_ID.eq(s.getOrgId()).and(SUPPLIER.ID.eq(s.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("Supplier", s.getId());
    }
    return toSupplier(record);
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(SUPPLIER).where(SUPPLIER.ORG_ID.eq(orgId).and(SUPPLIER.ID.eq(id))).execute();
    if (deleted == 0) {
      throw new NotFoundException("Supplier", id);
    }
  }

  private static Supplier toSupplier(SupplierRecord r) {
    Supplier s = new Supplier();
    s.setId(r.getId());
    s.setOrgId(r.getOrgId());
    s.setName(r.getName());
    s.setPhone(r.getPhone());
    s.setPhoneE164(r.getPhoneE164());
    s.setEmail(r.getEmail());
    s.setAddress(r.getAddress());
    s.setNotes(r.getNotes());
    s.setActive(Boolean.TRUE.equals(r.getActive()));
    s.setCreatedAt(r.getCreatedAt());
    s.setUpdatedAt(r.getUpdatedAt());
    return s;
  }
}
