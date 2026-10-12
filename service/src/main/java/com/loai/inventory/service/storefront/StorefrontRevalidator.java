package com.loai.inventory.service.storefront;

import java.util.Set;

/**
 * Tells the storefront that an org's cached public reads are stale ({@code
 * stories/storefront_revalidate_hook.md}). A hint, never a dependency: implementations do not
 * block, do not throw and are not retried — the storefront's TTL is the backstop.
 */
public interface StorefrontRevalidator {

  /** The closed scope set of the wire contract; the storefront maps each to a cache tag. */
  enum Scope {
    PROFILE("profile"),
    CATALOG("catalog"),
    CONTENT("content"),
    REVIEWS("reviews");

    private final String wire;

    Scope(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }
  }

  void revalidate(String orgSlug, Set<Scope> scopes);

  /** Hook not configured: every write behaves exactly as it did before the hook existed. */
  StorefrontRevalidator NOOP = (orgSlug, scopes) -> {};
}
