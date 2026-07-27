package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PlatformSearchGroup;
import com.loai.inventory.domain.model.PlatformSearchOrg;
import com.loai.inventory.domain.model.PlatformSearchResult;
import com.loai.inventory.domain.model.PlatformSearchType;
import com.loai.inventory.domain.repository.PlatformSearchRepository;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * The cross-org identifier lookups behind {@code GET /api/admin/search?q=}. See {@link
 * PlatformSearchRepository} for the three rules that keep this un-scoped read safe, and {@code
 * PlatformSearchQuery} for which probes a given query fires and how its term was normalized —
 * neither decision is restated here.
 *
 * <p><strong>Every SELECT lists its columns.</strong> Not {@code select()}: rule 3 is a field
 * whitelist, and a star would quietly widen it the next time a column lands on one of these tables
 * — {@code payment_transaction.raw_payload} and {@code customer.phone} are both one star away from
 * an operator's screen. Same discipline as {@link PlatformQueueRepositoryImpl}.
 *
 * <p><strong>Every probe pays its count.</strong> The response promises a true {@code total}, and
 * an exact count cannot early-exit, so the {@code LIMIT} saves nothing — each method runs its
 * predicate twice (once counted, once fetched) and V75's measurements were taken on the counted
 * shape for exactly that reason.
 *
 * <p><strong>Nothing filters on {@code org.active} or {@code suspended_at}.</strong> A suspended
 * merchant's orders are the ones most likely to generate support contact.
 */
public final class PlatformSearchRepositoryImpl implements PlatformSearchRepository {

  private final DSLContext dsl;

  public PlatformSearchRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public PlatformSearchGroup orgs(String term, int limit) {
    if (blank(term)) {
      return PlatformSearchGroup.empty(PlatformSearchType.ORG);
    }
    // fold_search on BOTH sides — the ProductRepositoryImpl.searchCondition precedent. `org` has no
    // generated name_search column (only customer and product_listing do), so the stored side is a
    // function call too; at 200 rows that is honest and cheap (measured sub-millisecond on perfdb),
    // and it keeps the query fold and the stored key consistent by construction.
    Field<String> folded = DSL.field("fold_search({0})", String.class, DSL.val(term));
    Condition slugHit = ORG.SLUG.eq(term.toLowerCase(java.util.Locale.ROOT));
    Condition nameHit =
        DSL.field("fold_search({0})", String.class, ORG.NAME)
            .like(DSL.concat(DSL.inline("%"), folded, DSL.inline("%")));
    Condition where = slugHit.or(nameHit);

    long total = dsl.fetchCount(ORG, where);
    List<PlatformSearchResult> results =
        dsl.select(ORG.ID, ORG.NAME, ORG.SLUG)
            .from(ORG)
            .where(where)
            // Exact slug first: an exact identifier is unambiguous, a name match is a guess.
            .orderBy(DSL.field(slugHit).desc(), ORG.CREATED_AT.desc(), ORG.ID.desc())
            .limit(limit)
            .fetch(
                r ->
                    new PlatformSearchResult(
                        PlatformSearchType.ORG,
                        r.get(ORG.ID),
                        r.get(ORG.NAME),
                        r.get(ORG.SLUG),
                        // An org IS the tenant; there is no owning org above it.
                        null));
    return new PlatformSearchGroup(PlatformSearchType.ORG, total, results);
  }

  @Override
  public PlatformSearchGroup users(String normalizedEmail, int limit) {
    if (blank(normalizedEmail)) {
      return PlatformSearchGroup.empty(PlatformSearchType.APP_USER);
    }
    // Served by V11's UNIQUE (email) — the one identifier in this schema whose unique leads with
    // the
    // thing being searched. Measured as an index-only scan on perfdb; V75 adds nothing here.
    // password_hash and token_version are deliberately absent, star or no star.
    Condition where = APP_USER.EMAIL.eq(normalizedEmail);
    long total = dsl.fetchCount(APP_USER, where);
    List<PlatformSearchResult> results =
        dsl.select(APP_USER.ID, APP_USER.EMAIL, APP_USER.DISPLAY_NAME)
            .from(APP_USER)
            .where(where)
            .orderBy(APP_USER.CREATED_AT.desc(), APP_USER.ID.desc())
            .limit(limit)
            .fetch(
                r ->
                    new PlatformSearchResult(
                        PlatformSearchType.APP_USER,
                        r.get(APP_USER.ID),
                        r.get(APP_USER.EMAIL),
                        r.get(APP_USER.DISPLAY_NAME),
                        // A platform identity may hold roles in several tenants and belongs to
                        // none.
                        null));
    return new PlatformSearchGroup(PlatformSearchType.APP_USER, total, results);
  }

  @Override
  public PlatformSearchGroup customers(String normalizedEmail, int limit) {
    if (blank(normalizedEmail)) {
      return PlatformSearchGroup.empty(PlatformSearchType.CUSTOMER);
    }
    // No name, phone, address or name_search anywhere in this query — the label IS the email that
    // was searched, and the answer is the tenant. `customer.name` is not selected even though it is
    // not searchable either; carrying it would be pure spill.
    Condition where = CUSTOMER.EMAIL.eq(normalizedEmail);
    long total = dsl.fetchCount(CUSTOMER, where);
    List<PlatformSearchResult> results =
        dsl.select(
                CUSTOMER.ID,
                CUSTOMER.EMAIL,
                ORG.ID,
                ORG.NAME,
                ORG.SLUG,
                ORG.ACTIVE,
                ORG.SUSPENDED_AT)
            .from(CUSTOMER)
            .join(ORG)
            .on(ORG.ID.eq(CUSTOMER.ORG_ID))
            .where(where)
            .orderBy(CUSTOMER.CREATED_AT.desc(), CUSTOMER.ID.desc())
            .limit(limit)
            .fetch(
                r ->
                    new PlatformSearchResult(
                        PlatformSearchType.CUSTOMER,
                        r.get(CUSTOMER.ID),
                        r.get(CUSTOMER.EMAIL),
                        null,
                        org(r)));
    return new PlatformSearchGroup(PlatformSearchType.CUSTOMER, total, results);
  }

  @Override
  public PlatformSearchGroup salesOrders(String normalizedOrderNumber, int limit) {
    if (blank(normalizedOrderNumber)) {
      return PlatformSearchGroup.empty(PlatformSearchType.SALES_ORDER);
    }
    // UNIQUE (org_id, order_number) leads with the scope, so this matches nothing on its leading
    // column and was a 2.5-second parallel seq scan before V75's sales_order (order_number).
    // No lines, no customer_id, no notes: the whole question here is "whose order is this".
    Condition where = SALES_ORDER.ORDER_NUMBER.eq(normalizedOrderNumber);
    long total = dsl.fetchCount(SALES_ORDER, where);
    List<PlatformSearchResult> results =
        dsl.select(
                SALES_ORDER.ID,
                SALES_ORDER.ORDER_NUMBER,
                SALES_ORDER.STATUS,
                ORG.ID,
                ORG.NAME,
                ORG.SLUG,
                ORG.ACTIVE,
                ORG.SUSPENDED_AT)
            .from(SALES_ORDER)
            .join(ORG)
            .on(ORG.ID.eq(SALES_ORDER.ORG_ID))
            .where(where)
            .orderBy(SALES_ORDER.CREATED_AT.desc(), SALES_ORDER.ID.desc())
            .limit(limit)
            .fetch(
                r ->
                    new PlatformSearchResult(
                        PlatformSearchType.SALES_ORDER,
                        r.get(SALES_ORDER.ID),
                        r.get(SALES_ORDER.ORDER_NUMBER),
                        name(r.get(SALES_ORDER.STATUS)),
                        org(r)));
    return new PlatformSearchGroup(PlatformSearchType.SALES_ORDER, total, results);
  }

  @Override
  public PlatformSearchGroup paymentTransactions(String normalizedRef, int limit) {
    if (blank(normalizedRef)) {
      return PlatformSearchGroup.empty(PlatformSearchType.PAYMENT_TRANSACTION);
    }
    // UNIQUE (provider, provider_ref) also leads with the scope, so a provider-less lookup matched
    // none of it — 257 ms before V75's payment_transaction (provider_ref). raw_payload,
    // proof_object_key, customer_note and claimed_by_customer_id are all deliberately unselected.
    // Ordered on recorded_at, the same clock the org-scoped transaction ledger uses.
    Condition where = PAYMENT_TRANSACTION.PROVIDER_REF.eq(normalizedRef);
    long total = dsl.fetchCount(PAYMENT_TRANSACTION, where);
    List<PlatformSearchResult> results =
        dsl.select(
                PAYMENT_TRANSACTION.ID,
                PAYMENT_TRANSACTION.PROVIDER_REF,
                PAYMENT_TRANSACTION.PROVIDER,
                ORG.ID,
                ORG.NAME,
                ORG.SLUG,
                ORG.ACTIVE,
                ORG.SUSPENDED_AT)
            .from(PAYMENT_TRANSACTION)
            .join(ORG)
            .on(ORG.ID.eq(PAYMENT_TRANSACTION.ORG_ID))
            .where(where)
            .orderBy(PAYMENT_TRANSACTION.RECORDED_AT.desc(), PAYMENT_TRANSACTION.ID.desc())
            .limit(limit)
            .fetch(
                r ->
                    new PlatformSearchResult(
                        PlatformSearchType.PAYMENT_TRANSACTION,
                        r.get(PAYMENT_TRANSACTION.ID),
                        r.get(PAYMENT_TRANSACTION.PROVIDER_REF),
                        provider(r.get(PAYMENT_TRANSACTION.PROVIDER)),
                        org(r)));
    return new PlatformSearchGroup(PlatformSearchType.PAYMENT_TRANSACTION, total, results);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * The tenant, with the state named by the server. {@link OrgStatus#of} is the same derivation the
   * org list, the tenant tiles and the queue rows run — no second copy of the rule, and never a
   * boolean for a client to re-derive from.
   */
  private static PlatformSearchOrg org(Record r) {
    return new PlatformSearchOrg(
        r.get(ORG.ID),
        r.get(ORG.NAME),
        r.get(ORG.SLUG),
        OrgStatus.of(Boolean.TRUE.equals(r.get(ORG.ACTIVE)), r.get(ORG.SUSPENDED_AT)));
  }

  /** The DB's lowercase provider label back to the domain enum's name, as every DTO reports it. */
  private static String provider(
      com.loai.inventory.repository.generated.enums.PaymentProvider generated) {
    return generated == null ? null : PaymentProvider.fromDbLiteral(generated.getLiteral()).name();
  }

  private static String name(Enum<?> value) {
    return value == null ? null : value.name();
  }
}
