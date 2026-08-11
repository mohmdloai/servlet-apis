package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.text.Phone;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.repository.generated.tables.records.CustomerRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CustomerRepositoryImpl implements CustomerRepository {
  private static final Logger log = LoggerFactory.getLogger(CustomerRepositoryImpl.class);
  private final DSLContext dsl;

  public CustomerRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<Customer> findById(UUID orgId, UUID id) {
    return dsl.selectFrom(CUSTOMER)
        .where(CUSTOMER.ORG_ID.eq(orgId).and(CUSTOMER.ID.eq(id)))
        .fetchOptional()
        .map(this::toCustomer);
  }

  @Override
  public Optional<Customer> findByEmail(UUID orgId, String email) {
    return dsl.selectFrom(CUSTOMER)
        .where(CUSTOMER.ORG_ID.eq(orgId).and(CUSTOMER.EMAIL.eq(email)))
        .fetchOptional()
        .map(this::toCustomer);
  }

  @Override
  public void markEmailVerified(UUID orgId, UUID id, OffsetDateTime verifiedAt) {
    // Only stamp when currently null → records the FIRST proof of ownership, idempotently.
    dsl.update(CUSTOMER)
        .set(CUSTOMER.EMAIL_VERIFIED_AT, verifiedAt)
        .where(
            CUSTOMER
                .ORG_ID
                .eq(orgId)
                .and(CUSTOMER.ID.eq(id))
                .and(CUSTOMER.EMAIL_VERIFIED_AT.isNull()))
        .execute();
  }

  @Override
  public List<Customer> findAll(UUID orgId, int offset, int limit) {
    return findAll(orgId, null, offset, limit);
  }

  @Override
  public List<Customer> findAll(UUID orgId, String q, int offset, int limit) {
    return dsl.selectFrom(CUSTOMER)
        .where(searchCondition(orgId, q))
        // created_at DESC ALWAYS — including under `q`. See the interface Javadoc: this is a
        // directory, not a worklist, so the queue-vs-ledger convention deliberately does not apply.
        .orderBy(CUSTOMER.CREATED_AT.desc())
        .offset(offset)
        .limit(limit)
        .fetch()
        .map(this::toCustomer);
  }

  @Override
  public long count(UUID orgId) {
    return count(orgId, null);
  }

  @Override
  public long count(UUID orgId, String q) {
    return dsl.fetchCount(dsl.selectFrom(CUSTOMER).where(searchCondition(orgId, q)));
  }

  /**
   * Shared filter for the directory read and its count — always org-scoped, plus an optional
   * name-or-email match. One definition, two callers: a list whose total disagrees with its rows is
   * the drift this codebase keeps structurally impossible rather than testing for.
   *
   * <p><b>Each leg normalizes with the same function that wrote the column.</b> The name leg calls
   * the DB's own {@code fold_search} on both sides against V62's generated {@code name_search} (the
   * {@code ProductRepositoryImpl.searchCondition} precedent), so {@code "احمد"} also finds {@code
   * "أحمد"}; the email leg lower-cases and trims exactly as {@code Text.normalizeEmail} did on the
   * way in. A fold applied on only one side is a recall bug that no test notices until an
   * Arabic-locale merchant reports it.
   *
   * <p><b>There is no minimum length, and that is measured rather than assumed.</b> Slice 3 set a
   * 2-character floor on the <em>cross-org</em> search as its DoS defence and the epic recorded
   * that it failed: pg_trgm extracts no trigram below three characters, so a 2-char {@code LIKE}
   * chose the GIN index and rechecked all 200 000 customers — 1706 ms. That pathology is
   * structurally unreachable here, because this query is org-scoped and the planner never chooses
   * the trigram index for it. Measured on {@code perfdb} (200 orgs × 1000 customers): a
   * <em>one-character</em> {@code q} runs 0.24–0.65 ms via {@code Bitmap Index Scan on
   * customer_org_idx} → filter, 26 buffers, all {@code shared hit}; 2, 3 and 4 characters are
   * indistinguishable (0.13–1.30 ms). Forcing {@code enable_bitmapscan=off} still picks the org
   * index, never the GIN. The cost is linear in the tenant's own size (the same predicate over all
   * 200 000 rows, unbounded, is a 42–49 ms Seq Scan), so a 20 000-customer tenant projects to ~5
   * ms. A character floor would defend nothing that the org filter does not already bound.
   */
  private org.jooq.Condition searchCondition(UUID orgId, String q) {
    org.jooq.Condition c = CUSTOMER.ORG_ID.eq(orgId);
    if (q == null || q.isBlank()) {
      return c;
    }
    String term = q.trim();
    org.jooq.Field<String> folded =
        org.jooq.impl.DSL.field("fold_search({0})", String.class, org.jooq.impl.DSL.val(term));
    org.jooq.Condition byName =
        CUSTOMER.NAME_SEARCH.like(
            org.jooq.impl.DSL.concat(
                org.jooq.impl.DSL.inline("%"), folded, org.jooq.impl.DSL.inline("%")));
    org.jooq.Condition byEmail = CUSTOMER.EMAIL.containsIgnoreCase(term);
    return c.and(byName.or(byEmail));
  }

  @Override
  public Customer insert(Customer customer) {
    CustomerRecord record =
        dsl.insertInto(CUSTOMER)
            .set(CUSTOMER.ORG_ID, customer.getOrgId())
            .set(CUSTOMER.EMAIL, customer.getEmail())
            .set(CUSTOMER.NAME, customer.getName())
            // Derived here rather than taken from the caller, so no write path can persist a phone
            // without its dialable twin — the same reflex as deriving it in the checkout upsert.
            .set(CUSTOMER.PHONE, customer.getPhone())
            .set(CUSTOMER.PHONE_E164, Phone.toE164(customer.getPhone()))
            .set(CUSTOMER.ADDRESS, customer.getAddress())
            .set(CUSTOMER.LOCALE, customer.getLocale())
            .returning()
            .fetchOne();
    if (record == null) {
      throw new IllegalStateException("INSERT into customer returned no record");
    }

    log.debug(
        "Inserted customer id={} orgId={} email={}",
        record.getId(),
        record.getOrgId(),
        record.getEmail());
    return toCustomer(record);
  }

  @Override
  public Customer update(Customer customer) {
    CustomerRecord record =
        dsl.update(CUSTOMER)
            .set(CUSTOMER.EMAIL, customer.getEmail())
            .set(CUSTOMER.NAME, customer.getName())
            // Re-derived on every update, so an edited phone can never keep the old dialable twin.
            .set(CUSTOMER.PHONE, customer.getPhone())
            .set(CUSTOMER.PHONE_E164, Phone.toE164(customer.getPhone()))
            .set(CUSTOMER.ADDRESS, customer.getAddress())
            .set(CUSTOMER.LOCALE, customer.getLocale())
            .set(CUSTOMER.UPDATED_AT, OffsetDateTime.now())
            .where(CUSTOMER.ORG_ID.eq(customer.getOrgId()).and(CUSTOMER.ID.eq(customer.getId())))
            .returning()
            .fetchOne();
    if (record == null) {
      throw new NotFoundException("Customer", customer.getId());
    }

    log.debug("Updated customer id={}", record.getId());
    return toCustomer(record);
  }

  @Override
  public boolean fillLocaleIfAbsent(UUID orgId, UUID id, String locale) {
    if (locale == null || locale.isBlank()) {
      return false;
    }
    // The IS NULL predicate is the guarantee, in SQL rather than in a read-then-write the next
    // concurrent checkout could race.
    return dsl.update(CUSTOMER)
            .set(CUSTOMER.LOCALE, locale)
            .where(CUSTOMER.ORG_ID.eq(orgId).and(CUSTOMER.ID.eq(id)).and(CUSTOMER.LOCALE.isNull()))
            .execute()
        > 0;
  }

  @Override
  public void deleteById(UUID orgId, UUID id) {
    int deleted =
        dsl.deleteFrom(CUSTOMER).where(CUSTOMER.ORG_ID.eq(orgId).and(CUSTOMER.ID.eq(id))).execute();
    if (deleted == 0) {
      throw new NotFoundException("Customer", id);
    }
  }

  @Override
  public boolean existsByEmail(UUID orgId, String email) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(CUSTOMER)
            .where(CUSTOMER.ORG_ID.eq(orgId).and(CUSTOMER.EMAIL.eq(email))));
  }

  @Override
  public boolean existsByEmailAndIdNot(UUID orgId, String email, UUID excludeId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from(CUSTOMER)
            .where(
                CUSTOMER
                    .ORG_ID
                    .eq(orgId)
                    .and(CUSTOMER.EMAIL.eq(email))
                    .and(CUSTOMER.ID.ne(excludeId))));
  }

  private Customer toCustomer(CustomerRecord r) {
    Customer c =
        new Customer(
            r.getId(),
            r.getOrgId(),
            r.getEmail(),
            r.getName(),
            r.getPhone(),
            r.getAddress(),
            r.getCreatedAt(),
            r.getUpdatedAt());
    c.setEmailVerifiedAt(r.getEmailVerifiedAt());
    c.setPhoneE164(r.getPhoneE164());
    c.setLocale(r.getLocale());
    return c;
  }
}
