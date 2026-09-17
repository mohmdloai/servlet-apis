package com.loai.inventory.service.storefront;

import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.service.storefront.StorefrontRevalidator.Scope;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a service calls once its write has <b>committed</b> (after {@code transactionResult} returns
 * — the {@code orgStatus.invalidate} placement), so a revalidated read can never race ahead of the
 * data it re-reads.
 *
 * <pre>
 * profileChanged(orgId):  slug ← SELECT org.slug  (root DSL, outside the write's tx)
 *                         revalidator.revalidate(slug, {PROFILE})
 *                         any exception → WARN, swallowed        -- a hint never fails a write
 * </pre>
 */
public final class StorefrontCacheNotifier {

  private static final Logger log = LoggerFactory.getLogger(StorefrontCacheNotifier.class);

  /** For the constructors that predate the hook (and the ITs that still use them). */
  public static final StorefrontCacheNotifier NOOP =
      new StorefrontCacheNotifier(null, null, StorefrontRevalidator.NOOP);

  private final DSLContext rootDsl;
  private final OrgRepositoryFactory orgRepoFactory;
  private final StorefrontRevalidator revalidator;

  public StorefrontCacheNotifier(
      DSLContext rootDsl, OrgRepositoryFactory orgRepoFactory, StorefrontRevalidator revalidator) {
    this.rootDsl = rootDsl;
    this.orgRepoFactory = orgRepoFactory;
    this.revalidator = revalidator;
  }

  public void profileChanged(UUID orgId) {
    if (revalidator == StorefrontRevalidator.NOOP) return;
    try {
      orgRepoFactory
          .create(rootDsl)
          .findById(orgId)
          .map(Org::getSlug)
          .ifPresent(this::profileChanged);
    } catch (RuntimeException e) {
      log.warn("storefront revalidate skipped for org {}: {}", orgId, e.toString());
    }
  }

  public void profileChanged(String orgSlug) {
    if (orgSlug == null || orgSlug.isBlank()) return;
    try {
      revalidator.revalidate(orgSlug, Set.of(Scope.PROFILE));
    } catch (RuntimeException e) {
      log.warn("storefront revalidate skipped for {}: {}", orgSlug, e.toString());
    }
  }
}
