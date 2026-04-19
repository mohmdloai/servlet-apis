package com.loai.inventory.api.dto;

public class CreateOrgRequest {
  private String name;
  private String slug;

  public CreateOrgRequest() {}

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSlug() {
    return slug;
  }

  public void setSlug(String slug) {
    this.slug = slug;
  }
}
