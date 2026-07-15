package com.loai.inventory.domain.model;

public enum ActorType {
  USER,
  SERVICE,
  SYSTEM,
  MIGRATION,

  /**
   * A CRM {@code customer} authenticated on the passwordless portal plane ({@code /api/portal/*}).
   * Never an {@code app_user}, never carries org/system roles — a customer token is structurally
   * unusable on the staff/admin plane (separate signing key + {@code aud} + path/filter). See
   * {@code servlet-apis/stories/portal_auth_core.md}.
   */
  CUSTOMER
}
