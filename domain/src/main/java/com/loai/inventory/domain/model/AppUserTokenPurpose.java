package com.loai.inventory.domain.model;

/**
 * Why an {@link AppUserMagicToken} was minted. Stored as {@code name()} (TEXT + CHECK in V49).
 *
 * <ul>
 *   <li>{@code PASSWORD_RESET} — self-service "forgot password".
 *   <li>{@code INVITE} — a provisioned owner or admin-created account sets its first password and
 *       activates.
 * </ul>
 */
public enum AppUserTokenPurpose {
  PASSWORD_RESET,
  INVITE
}
