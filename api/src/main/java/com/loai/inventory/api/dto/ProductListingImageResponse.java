package com.loai.inventory.api.dto;

import com.loai.inventory.service.ProductListingService.ImageView;
import java.util.UUID;

public class ProductListingImageResponse {
  private UUID id;
  private String url;
  private String altText;
  private int sortOrder;

  private ProductListingImageResponse() {}

  public static ProductListingImageResponse from(ImageView v) {
    ProductListingImageResponse r = new ProductListingImageResponse();
    r.id = v.id();
    r.url = v.url();
    r.altText = v.altText();
    r.sortOrder = v.sortOrder();
    return r;
  }

  public UUID getId() {
    return id;
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
