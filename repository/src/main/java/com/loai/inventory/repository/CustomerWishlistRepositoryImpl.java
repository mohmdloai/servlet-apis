package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER_WISHLIST;

import com.loai.inventory.domain.repository.CustomerWishlistRepository;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Postgres/jOOQ implementation of the customer wishlist (roadmap item 3, {@code
 * stories/customer_wishlist.md}).
 *
 * <p>Every statement is scoped by {@code (org_id, customer_id)} — the same pair the session
 * principal carries — so a row belonging to another customer, or to the same email in another org,
 * is unreachable rather than merely unrendered.
 */
public final class CustomerWishlistRepositoryImpl implements CustomerWishlistRepository {

  private final DSLContext dsl;

  public CustomerWishlistRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<UUID> findListingIds(UUID orgId, UUID customerId) {
    return dsl.select(CUSTOMER_WISHLIST.PRODUCT_LISTING_ID)
        .from(CUSTOMER_WISHLIST)
        .where(CUSTOMER_WISHLIST.ORG_ID.eq(orgId).and(CUSTOMER_WISHLIST.CUSTOMER_ID.eq(customerId)))
        // Newest saved first — matches customer_wishlist_owner_idx, so the read never sorts.
        .orderBy(CUSTOMER_WISHLIST.CREATED_AT.desc(), CUSTOMER_WISHLIST.ID.desc())
        .fetch(CUSTOMER_WISHLIST.PRODUCT_LISTING_ID);
  }

  @Override
  public int count(UUID orgId, UUID customerId) {
    return dsl.fetchCount(
        dsl.selectFrom(CUSTOMER_WISHLIST)
            .where(
                CUSTOMER_WISHLIST
                    .ORG_ID
                    .eq(orgId)
                    .and(CUSTOMER_WISHLIST.CUSTOMER_ID.eq(customerId))));
  }

  @Override
  public boolean add(UUID orgId, UUID customerId, UUID productListingId) {
    // ON CONFLICT DO NOTHING against the (customer, listing) unique pair: the login-merge replays
    // the guest's whole list, so "already saved" is the common case, not an error.
    return dsl.insertInto(CUSTOMER_WISHLIST)
            .set(CUSTOMER_WISHLIST.ID, UUID.randomUUID())
            .set(CUSTOMER_WISHLIST.ORG_ID, orgId)
            .set(CUSTOMER_WISHLIST.CUSTOMER_ID, customerId)
            .set(CUSTOMER_WISHLIST.PRODUCT_LISTING_ID, productListingId)
            .onConflictDoNothing()
            .execute()
        > 0;
  }

  @Override
  public int remove(UUID orgId, UUID customerId, UUID productListingId) {
    return dsl.deleteFrom(CUSTOMER_WISHLIST)
        .where(
            CUSTOMER_WISHLIST
                .ORG_ID
                .eq(orgId)
                .and(CUSTOMER_WISHLIST.CUSTOMER_ID.eq(customerId))
                .and(CUSTOMER_WISHLIST.PRODUCT_LISTING_ID.eq(productListingId)))
        .execute();
  }
}
