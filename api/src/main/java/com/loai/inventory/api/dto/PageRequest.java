package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontPageService.PageInput;

/**
 * Upsert body for a storefront text page ({@code PUT /api/orgs/{orgId}/storefront/pages/{kind}},
 * customization epic slice C4). Both bodies are carried whole (the PUT is idempotent create-or-
 * replace — a null/blank body clears that locale). The default-locale-body-required and length-cap
 * validation is the service's; {@code kind} comes from the path, not this body.
 */
public class PageRequest {

  private String bodyAr;
  private String bodyEn;

  public PageRequest() {}

  public PageInput toInput() {
    return new PageInput(bodyAr, bodyEn);
  }

  public void setBodyAr(String bodyAr) {
    this.bodyAr = bodyAr;
  }

  public void setBodyEn(String bodyEn) {
    this.bodyEn = bodyEn;
  }
}
