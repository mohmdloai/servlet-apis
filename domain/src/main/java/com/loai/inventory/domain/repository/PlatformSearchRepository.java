package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PlatformSearchGroup;

/**
 * The cross-org identifier lookups behind {@code GET /api/admin/search?q=} — the third read in this
 * codebase that deliberately omits the {@code org_id} filter, and a <strong>sibling</strong> of
 * {@link PlatformStatsRepository} and {@link PlatformQueueRepository} rather than an addition to
 * either.
 *
 * <p>That separation is the whole safety argument. {@code PlatformStatsRepository} is safe to
 * review because it returns <em>counts only</em>; {@code PlatformQueueRepository} because its rows
 * are a sealed per-kind whitelist. Hanging search methods off either would spend exactly the
 * guarantee that makes it reviewable. So results live here, under the same three rules:
 *
 * <ol>
 *   <li><strong>Platform-gated.</strong> Reached solely through {@code GET /api/admin/search}
 *       behind {@code AuthzHelper.requirePlatformRead} (ADMIN or SUPPORT). No org-scoped route may
 *       call it.
 *   <li><strong>Read-only.</strong> No mutation belongs here, ever.
 *   <li><strong>Results, against a declared per-type field whitelist.</strong> The whitelist is
 *       {@link com.loai.inventory.domain.model.PlatformSearchResult} — identity, one label, one
 *       sublabel, one tenant — and {@code PlatformSearchIT.results_carryNoCustomerPii} is what
 *       keeps it true as fields get added.
 * </ol>
 *
 * <p><strong>One method per probe, and nothing that fans out.</strong> Which probes a query fires
 * is a classification decision, and it is made once in {@code PlatformSearchQuery} where a reviewer
 * can read the whole table without running anything. This interface is deliberately not given a
 * {@code search(String)} entry point: a single method would move that decision in here, out of the
 * one place it is documented, and would make "run every probe on every keystroke" a one-line
 * change.
 *
 * <p><strong>Every term arrives already normalized</strong>, by the same {@code
 * common/.../text/Text} function that wrote the column being probed ({@code normalizeEmail} for
 * emails, {@code normalizeNumeric} for references and order numbers). The normalization table lives
 * beside the classification table, for the same reason.
 *
 * <p><strong>Each method returns the true {@code total}</strong>, not the size of the capped list.
 * An exact count cannot early-exit, so the cap buys nothing here — see {@link PlatformSearchGroup}.
 *
 * <p><strong>Nothing filters on org status.</strong> A suspended tenant's orders are searchable;
 * see {@link com.loai.inventory.domain.model.PlatformSearchOrg}.
 */
public interface PlatformSearchRepository {

  /**
   * Tenants whose {@code slug} equals the term exactly, or whose {@code name} contains it once both
   * sides are folded through the database's own {@code fold_search}. Exact slug hits sort first: an
   * exact identifier is unambiguous and a name match is a guess.
   *
   * <p>The only fuzzy probe that survives this slice, and it is bounded — {@code org} is 200 rows
   * on {@code perfdb}, where the whole thing (including a {@code fold_search} call per row)
   * measured under a millisecond. Do not index it reflexively.
   *
   * @param term the trimmed query, raw; both the slug comparison and the fold happen in SQL.
   */
  PlatformSearchGroup orgs(String term, int limit);

  /** Platform-plane identities whose {@code email} equals {@code normalizedEmail} exactly. */
  PlatformSearchGroup users(String normalizedEmail, int limit);

  /**
   * CRM records whose {@code email} equals {@code normalizedEmail} exactly, across every tenant.
   *
   * <p><strong>Email only — there is no name probe, and that is a measurement, not an
   * oversight.</strong> See {@code SearchAdminHandler}'s Javadoc for the 1706 ms.
   *
   * <p>This is the one deliberate cross-tenant disclosure in the slice: it reveals which merchants
   * a person shops at. A platform ADMIN could already learn that by other means, so it is not new
   * capability — but it is newly one keystroke, and {@code
   * PlatformSearchIT.sameCustomerEmailInTwoOrgs_returnsBoth} pins it so removing it later is a
   * visible decision.
   */
  PlatformSearchGroup customers(String normalizedEmail, int limit);

  /**
   * Orders whose {@code order_number} equals {@code normalizedOrderNumber} exactly, across every
   * tenant. {@code UNIQUE (org_id, order_number)} is <em>per org</em>, so the same number exists in
   * many tenants and this legitimately returns a list — the fact the whole slice exists to surface.
   */
  PlatformSearchGroup salesOrders(String normalizedOrderNumber, int limit);

  /**
   * Transactions whose {@code provider_ref} equals {@code normalizedRef} exactly. {@code UNIQUE
   * (provider, provider_ref)} makes this the one identifier here that genuinely is globally unique
   * — though the read still returns a group, because the response shape does not special-case it.
   */
  PlatformSearchGroup paymentTransactions(String normalizedRef, int limit);
}
