package com.loai.inventory.service.auth;

import com.loai.inventory.domain.model.AppUserMagicToken;
import com.loai.inventory.domain.model.AppUserTokenPurpose;
import com.loai.inventory.domain.repository.AppUserMagicTokenRepositoryFactory;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Mints and redeems app_user credential tokens (the registration/forgot-password flows,
 * stories/11_st_platform_admin_console.md), mirroring {@link
 * com.loai.inventory.service.MagicLinkService} but for {@code app_user}. The raw token is a 256-bit
 * {@link SecureRandom} value returned only in the emailed URL; only its SHA-256 hash (reusing
 * {@link RefreshTokenStore#hashToken}) is stored. Tokens are one-shot: {@link #consume} stamps
 * {@code consumed_at} atomically, so a used link cannot be replayed. TTL is shorter for a password
 * reset than for an invite.
 */
public class CredentialTokenService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final int TOKEN_BYTES = 32; // 256 bits

  private final DSLContext rootDsl;
  private final AppUserMagicTokenRepositoryFactory tokenRepoFactory;
  private final String publicBaseUrl;
  private final Duration resetTtl;
  private final Duration inviteTtl;
  private final Duration verifyTtl;

  public CredentialTokenService(
      DSLContext rootDsl,
      AppUserMagicTokenRepositoryFactory tokenRepoFactory,
      String publicBaseUrl,
      Duration resetTtl,
      Duration inviteTtl,
      Duration verifyTtl) {
    this.rootDsl = rootDsl;
    this.tokenRepoFactory = tokenRepoFactory;
    this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
    this.resetTtl = resetTtl;
    this.inviteTtl = inviteTtl;
    this.verifyTtl = verifyTtl;
  }

  /**
   * Mint a token inside the caller's txn and return the raw value (store only its hash). Runs in
   * {@code txDsl} so a token minted beside another write commits/rolls back with it.
   */
  public String mint(
      DSLContext txDsl, UUID userId, AppUserTokenPurpose purpose, OffsetDateTime now) {
    String rawToken = generateRawToken();
    tokenRepoFactory
        .create(txDsl)
        .insert(
            AppUserMagicToken.forInsert(
                userId, RefreshTokenStore.hashToken(rawToken), purpose, now.plus(ttlFor(purpose))));
    return rawToken;
  }

  /** Mint a token on its own transaction (autocommit) — for the forgot-password entry point. */
  public String mintAutonomous(UUID userId, AppUserTokenPurpose purpose, OffsetDateTime now) {
    return mint(rootDsl, userId, purpose, now);
  }

  /**
   * Redeem a raw token of {@code purpose} inside the caller's txn: returns the token's {@code
   * user_id} and marks it consumed, or empty for a missing/expired/consumed/wrong-purpose token.
   * Runs in {@code txDsl} so the consume commits atomically with the password change beside it.
   */
  public Optional<UUID> consume(
      DSLContext txDsl, AppUserTokenPurpose purpose, String rawToken, OffsetDateTime now) {
    if (rawToken == null || rawToken.isBlank()) {
      return Optional.empty();
    }
    return tokenRepoFactory
        .create(txDsl)
        .consume(RefreshTokenStore.hashToken(rawToken), purpose, now);
  }

  /** Absolute link a user follows to set a new password after "forgot password". */
  public String resetUrl(String rawToken) {
    return publicBaseUrl + "/reset-password?token=" + rawToken;
  }

  /** Absolute link a provisioned/invited user follows to set their first password and activate. */
  public String activateUrl(String rawToken) {
    return publicBaseUrl + "/activate?token=" + rawToken;
  }

  /** Absolute link a self-registered user follows to prove their inbox and sign in (story 88). */
  public String verifyUrl(String rawToken) {
    return publicBaseUrl + "/verify-email?token=" + rawToken;
  }

  /**
   * Supersede every live token of {@code purpose} for {@code userId} — resend-verification mints a
   * fresh link and only the latest one may redeem.
   */
  public void invalidateActive(UUID userId, AppUserTokenPurpose purpose, OffsetDateTime now) {
    tokenRepoFactory.create(rootDsl).invalidateActive(userId, purpose, now);
  }

  /**
   * When a token of {@code purpose} minted at {@code now} stops redeeming — the same arithmetic
   * {@link #mint} stamps on the row, exposed so a caller that must report the expiry to a client
   * reads it from here instead of re-deriving it from the TTL config. Two statements of one rule is
   * how a response starts naming an expiry the row does not have.
   */
  public OffsetDateTime expiresAt(AppUserTokenPurpose purpose, OffsetDateTime now) {
    return now.plus(ttlFor(purpose));
  }

  private Duration ttlFor(AppUserTokenPurpose purpose) {
    return switch (purpose) {
      case INVITE -> inviteTtl;
      case PASSWORD_RESET -> resetTtl;
      case EMAIL_VERIFY -> verifyTtl;
    };
  }

  private static String generateRawToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return URL_ENCODER.encodeToString(bytes);
  }

  private static String stripTrailingSlash(String url) {
    return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
  }
}
