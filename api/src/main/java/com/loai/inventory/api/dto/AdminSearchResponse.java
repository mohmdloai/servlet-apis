package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.PlatformSearchGroup;
import com.loai.inventory.domain.model.PlatformSearchOrg;
import com.loai.inventory.domain.model.PlatformSearchResult;
import com.loai.inventory.service.platform.PlatformSearchService;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/admin/search?q=} on the wire: {@code {query, groups:[{type, total,
 * results:[…]}]}}.
 *
 * <p>Three shapes of honesty are carried by this record rather than asserted somewhere else:
 *
 * <ul>
 *   <li><strong>{@code total} is the true count</strong>, not {@code results.size()}, so a client
 *       can render "showing 5 of 17" instead of truncating in silence.
 *   <li><strong>Empty groups never appear.</strong> {@link PlatformSearchService} drops them, so a
 *       client branches on presence — the same reflex as the overview's {@code degraded[]}.
 *   <li><strong>No URLs.</strong> Results carry stable identity only; route construction is the
 *       frontend's job ({@code searchResultPath}, story 77), and one of the five types has no route
 *       at all.
 * </ul>
 *
 * <p>Jackson drops nulls, so a {@code customer} result carries no {@code sublabel} key at all
 * rather than a null one, and an {@code org} / {@code app_user} result carries no {@code org}
 * block.
 */
public record AdminSearchResponse(String query, List<Group> groups) {

  public static AdminSearchResponse from(PlatformSearchService.SearchResults results) {
    return new AdminSearchResponse(
        results.query(), results.groups().stream().map(Group::from).toList());
  }

  /** One type's hits, capped, beside the true count of everything that matched. */
  public record Group(String type, long total, List<Result> results) {

    static Group from(PlatformSearchGroup group) {
      return new Group(
          group.type().wire(), group.total(), group.results().stream().map(Result::from).toList());
    }
  }

  /**
   * One hit. The field set mirrors {@link PlatformSearchResult} exactly — identity, one label, one
   * sublabel, one tenant — because that record <em>is</em> the whitelist and a DTO that added to it
   * would silently widen the boundary this endpoint is gated on.
   */
  public record Result(String type, UUID id, String label, String sublabel, Org org) {

    static Result from(PlatformSearchResult result) {
      return new Result(
          result.type().wire(),
          result.id(),
          result.label(),
          result.sublabel(),
          result.org() == null ? null : Org.from(result.org()));
    }
  }

  /**
   * The tenant block: {@code {id, name, slug, status}}. {@code status} is the {@code OrgStatus} the
   * server derived — never the {@code active} boolean, and never something the client re-derives.
   *
   * <p>Absent on {@code org} and {@code app_user} results, which have no owning tenant. Present and
   * possibly {@code suspended} on everything else: nothing filters on it, because a suspended
   * merchant's order is exactly the one an operator is most likely to be asked about.
   */
  public record Org(UUID id, String name, String slug, String status) {

    static Org from(PlatformSearchOrg org) {
      return new Org(org.id(), org.name(), org.slug(), org.status().wire());
    }
  }
}
