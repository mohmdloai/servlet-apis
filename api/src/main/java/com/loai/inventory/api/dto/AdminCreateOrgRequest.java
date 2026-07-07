package com.loai.inventory.api.dto;

/**
 * Body for {@code POST /api/admin/orgs} (platform provisioning, PG1): create a client org with a
 * first OWNER. {@code ownerEmail} is attached if the user exists, otherwise a fresh USER account is
 * minted with an unusable password (the client sets it via a later reset/invite).
 */
public class AdminCreateOrgRequest {
  private String name;
  private String slug;
  private String ownerEmail;

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

  public String getOwnerEmail() {
    return ownerEmail;
  }

  public void setOwnerEmail(String ownerEmail) {
    this.ownerEmail = ownerEmail;
  }
}
