package com.loai.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loai.inventory.service.StorefrontService.FacetGroup;
import com.loai.inventory.service.StorefrontService.FacetValue;
import java.util.List;

/**
 * The public catalog page envelope. Identical to {@link PageResponse} — {@code
 * data/total/page/size} — plus an optional {@code facets} block (roadmap item 7).
 *
 * <p>{@code facets} is omitted entirely (NON_NULL) unless the caller asked with {@code
 * ?include_facets=true}, so every reader that does not — home strips, the availability path, any
 * cached CDN entry minted before facets shipped — gets a <b>byte-identical</b> envelope. That is
 * what lets the counts be opt-in without versioning the read.
 */
public class PublicListingsPageResponse {

  private final List<PublicListingResponse> data;
  private final long total;
  private final int page;
  private final int size;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final List<Facet> facets;

  private PublicListingsPageResponse(
      List<PublicListingResponse> data, long total, int page, int size, List<Facet> facets) {
    this.data = data;
    this.total = total;
    this.page = page;
    this.size = size;
    this.facets = facets;
  }

  public static PublicListingsPageResponse of(
      List<PublicListingResponse> data, long total, int page, int size, List<FacetGroup> facets) {
    return new PublicListingsPageResponse(
        data, total, page, size, facets == null ? null : facets.stream().map(Facet::from).toList());
  }

  public List<PublicListingResponse> getData() {
    return data;
  }

  public long getTotal() {
    return total;
  }

  public int getPage() {
    return page;
  }

  public int getSize() {
    return size;
  }

  public List<Facet> getFacets() {
    return facets;
  }

  /** One facet group: {@code {attribute:{slug,label}, values:[…]}}. */
  public record Facet(Attribute attribute, List<Value> values) {
    static Facet from(FacetGroup g) {
      return new Facet(
          new Attribute(g.slug(), g.label()), g.values().stream().map(Value::from).toList());
    }
  }

  /** The axis identity — a slug the URL grammar keys on plus a locale-resolved label. */
  public record Attribute(String slug, String label) {}

  /**
   * One selectable value with its honest {@code count} ({@code COUNT(DISTINCT listing)}) and
   * whether the caller currently has it selected.
   */
  public record Value(String slug, String label, long count, boolean selected) {
    static Value from(FacetValue v) {
      return new Value(v.slug(), v.label(), v.count(), v.selected());
    }
  }
}
