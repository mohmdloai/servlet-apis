package com.loai.inventory.domain.model;

import java.util.List;

/**
 * One type's slice of a search response: the capped results plus <strong>the true count of
 * everything that matched</strong>.
 *
 * <p>{@code total} is never {@code results.size()}. The cap is 5 and there is no pagination — this
 * is a jump-to affordance, not a worklist — so the only honest way to truncate is to say what was
 * hidden. A client renders "showing 5 of 17"; filling {@code total} with the page size instead
 * would be the truncating-filter defect wearing a number.
 *
 * <p><strong>An empty group is never sent.</strong> {@link
 * com.loai.inventory.domain.repository.PlatformSearchRepository} may return one with {@code total =
 * 0}; the service drops it before it reaches the wire, so a client branches on presence — the same
 * reflex as the overview's {@code degraded[]}. A group present with zero results is a shape the UI
 * would have to render around for no information.
 *
 * <p>Because {@code total} is exact, the cap buys nothing on the database side: a count cannot
 * early-exit. Every probe pays its full predicate once regardless, which is why every {@code
 * EXPLAIN} behind V75 was taken on the {@code count(*)} shape rather than the top-5 fetch.
 */
public record PlatformSearchGroup(
    PlatformSearchType type, long total, List<PlatformSearchResult> results) {

  /**
   * Nothing matched this probe. The service omits these rather than serializing {@code total: 0}.
   */
  public static PlatformSearchGroup empty(PlatformSearchType type) {
    return new PlatformSearchGroup(type, 0, List.of());
  }

  public boolean isEmpty() {
    return total == 0 && results.isEmpty();
  }
}
