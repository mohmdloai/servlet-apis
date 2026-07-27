package com.loai.inventory.service.platform;

import com.loai.inventory.domain.model.PlatformSearchGroup;
import com.loai.inventory.domain.model.PlatformSearchType;
import com.loai.inventory.domain.repository.PlatformSearchRepository;
import com.loai.inventory.domain.repository.PlatformSearchRepositoryFactory;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;

/**
 * The cross-org identifier lookup behind {@code GET /api/admin/search?q=} ({@code
 * stories/platform_search.md}) — the way in when an operator starts from something a customer
 * quoted rather than from a tile.
 *
 * <p>Validation, classification and normalization are all one layer up, in {@link
 * PlatformSearchQuery}, so this class is only the dispatch: fire the probes that query fires, drop
 * the groups that matched nothing, hand back the rest. Mirrors {@link PlatformQueueService}, which
 * is likewise paging-and-parsing only with the rules one layer down.
 *
 * <p><strong>A result set is never a redirect.</strong> Four of the five identifiers are unique
 * <em>per org</em>, not globally, so {@code SO-2026-00042} legitimately matches many tenants. Even
 * a single hit comes back as a group of one; a client may choose to navigate straight through, but
 * the server never pretends a lookup was unambiguous when it was not.
 */
public class PlatformSearchService {

  private final DSLContext dsl;
  private final PlatformSearchRepositoryFactory searchRepoFactory;

  public PlatformSearchService(DSLContext dsl, PlatformSearchRepositoryFactory searchRepoFactory) {
    this.dsl = dsl;
    this.searchRepoFactory = searchRepoFactory;
  }

  /** The whole response: the normalized query, and one group per type that matched anything. */
  public record SearchResults(String query, List<PlatformSearchGroup> groups) {}

  /**
   * Run {@code q}'s probes and return the groups that matched.
   *
   * <p><strong>Empty groups are omitted</strong>, never sent as {@code total: 0} — the same reflex
   * as the overview's {@code degraded[]}: a client branches on presence. A group present with zero
   * results is a shape the UI would have to render around for no information.
   *
   * @throws com.loai.inventory.common.exception.ValidationException blank {@code q}, or shorter
   *     than {@link PlatformSearchQuery#MIN_LENGTH} once normalized.
   */
  public SearchResults search(String q) {
    PlatformSearchQuery query = PlatformSearchQuery.of(q);
    PlatformSearchRepository repo = searchRepoFactory.create(dsl);

    List<PlatformSearchGroup> groups = new ArrayList<>();
    for (PlatformSearchQuery.Term term : query.terms()) {
      PlatformSearchGroup group = probe(repo, term);
      if (!group.isEmpty()) {
        groups.add(group);
      }
    }
    return new SearchResults(query.query(), List.copyOf(groups));
  }

  /**
   * The type-to-method binding, total over the enum so a sixth type is a compile error rather than
   * a silently un-probed group. Which types are <em>reachable</em> for a given query is {@link
   * PlatformSearchQuery}'s table; this switch only says where each one is served from.
   */
  private static PlatformSearchGroup probe(
      PlatformSearchRepository repo, PlatformSearchQuery.Term term) {
    int cap = PlatformSearchQuery.GROUP_CAP;
    return switch (term.type()) {
      case ORG -> repo.orgs(term.value(), cap);
      case APP_USER -> repo.users(term.value(), cap);
      case CUSTOMER -> repo.customers(term.value(), cap);
      case SALES_ORDER -> repo.salesOrders(term.value(), cap);
      case PAYMENT_TRANSACTION -> repo.paymentTransactions(term.value(), cap);
    };
  }

  /** Every type the endpoint can ever return, for the handler's own documentation and tests. */
  public static List<PlatformSearchType> types() {
    return PlatformSearchQuery.coveredTypes();
  }
}
