package com.loai.inventory.api.dto;

/** Body of {@code POST /api/me/password}. Maps {@code current_password} / {@code new_password}. */
public class ChangePasswordRequest {
  private String currentPassword;
  private String newPassword;

  public ChangePasswordRequest() {}

  public String getCurrentPassword() {
    return currentPassword;
  }

  public void setCurrentPassword(String currentPassword) {
    this.currentPassword = currentPassword;
  }

  public String getNewPassword() {
    return newPassword;
  }

  public void setNewPassword(String newPassword) {
    this.newPassword = newPassword;
  }
}
