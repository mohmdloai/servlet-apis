package com.loai.inventory.api.dto;

/** Body for {@code PATCH /api/admin/users/{id}} - enable or disable the account. */
public class SetUserActiveRequest {
  private Boolean active;

  public Boolean getActive() {
    return active;
  }

  public void setActive(Boolean active) {
    this.active = active;
  }
}
