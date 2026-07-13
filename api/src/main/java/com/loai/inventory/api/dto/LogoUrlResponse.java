package com.loai.inventory.api.dto;

/**
 * Response for {@code GET /api/orgs/{orgId}/logo}: the current logo as a short-lived presigned GET
 * {@code url}, or an empty body ({@code url} omitted — NON_NULL mapper) when no logo is set. The
 * admin-plane preview read; the public storefront profile carries its own presigned copy. See
 * {@code stories/storefront_org_profile.md}.
 */
public class LogoUrlResponse {
  private String url;

  private LogoUrlResponse() {}

  public static LogoUrlResponse of(String url) {
    LogoUrlResponse r = new LogoUrlResponse();
    r.url = url;
    return r;
  }

  public String getUrl() {
    return url;
  }
}
