package com.loai.inventory.domain.model;

/**
 * Why an {@link AppUserMagicToken} was minted. Stored as {@code name()} (TEXT + CHECK in V49,
 * widened in V65).
 *
 * <ul>
 *   <li>{@code PASSWORD_RESET} — self-service "forgot password".
 *   <li>{@code INVITE} — a provisioned owner or admin-created account sets its first password and
 *       activates.
 *   <li>{@code EMAIL_VERIFY} — a self-registered account proves its inbox to activate login (story
 *       88; redemption stamps {@code app_user.email_verified_at} and signs the user in).
 * </ul>
 */
public enum AppUserTokenPurpose {
  PASSWORD_RESET,
  INVITE,
  EMAIL_VERIFY
}
