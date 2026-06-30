package com.loai.inventory.api.dto;

import com.loai.inventory.service.StorefrontService.PublicImage;

/** Public image: a display URL + metadata only. Object keys are never exposed. */
public class PublicListingImageResponse {
  private String url;
  private String altText;
  private int sortOrder;

  private PublicListingImageResponse() {}

  public static PublicListingImageResponse from(PublicImage i) {
    PublicListingImageResponse r = new PublicListingImageResponse();
    r.url = i.url();
    r.altText = i.altText();
    r.sortOrder = i.sortOrder();
    return r;
  }

  public String getUrl() {
    return url;
  }

  public String getAltText() {
    return altText;
  }

  public int getSortOrder() {
    return sortOrder;
  }
}
