package com.loai.inventory.service.auth;

import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.AppUserTokenPurpose;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.auth.AuthService.LoginResult;
import com.loai.inventory.service.email.EmailAddresses;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Self-service account flows that live outside the admin plane (stories/11): public registration,
 * "forgot password" (request + reset), and invite activation. Registration and token redemption own
 * their own transactions here (the account/org rows and the token consume must be atomic); once
 * committed, session issuance is delegated to {@link AuthService#issueSession} so the user is
 * logged in with fresh cookies exactly as {@code login} would.
 *
 * <p>Token redemption bumps {@code token_version} and propagates a logout-all before minting the
 * new session — a password change (self-serve reset or first-set) invalidates every prior session,
 * matching {@code AuthService.changePassword} and {@code UserAdminService.resetPassword}.
 */
public class AccountService {

  private static final Logger log = LoggerFactory.getLogger(AccountService.class);
  private static final int MIN_PASSWORD_LENGTH = 8;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final DSLContext rootDsl;
  private final UserRepositoryFactory userRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final CredentialTokenService tokenService;
  private final AuthMailer mailer;
  private final AuthService authService;

  public AccountService(
      DSLContext rootDsl,
      UserRepositoryFactory userRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      CredentialTokenService tokenService,
      AuthMailer mailer,
      AuthService authService) {
    this.rootDsl = rootDsl;
    this.userRepoFactory = userRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.tokenService = tokenService;
    this.mailer = mailer;
    this.authService = authService;
  }

  /**
   * Register a new USER self-service. Creates the account (and, when {@code orgName} is given, an
   * org with the registrant as its OWNER) in one transaction, then logs them in. 409 if the email
   * is already registered.
   */
  public LoginResult register(
      String email, String rawPassword, String orgName, String deviceInfo, String sourceIp) {
    String normalizedEmail = Text.normalizeEmail(email);
    if (!EmailAddresses.isSingleValid(normalizedEmail)) {
      throw new ValidationException("a valid email is required");
    }
    validatePassword(rawPassword);
    String normalizedOrgName = Text.normalizeText(orgName);
    boolean withOrg = normalizedOrgName != null;
    if (withOrg) {
      OrgService.validateName(normalizedOrgName);
    }

    AppUser created =
        rootDsl.transactionResult(
            cfg -> {
              DSLContext tx = DSL.using(cfg);
              UserRepository userRepo = userRepoFactory.create(tx);
              if (userRepo.findByEmail(normalizedEmail).isPresent()) {
                throw new ConflictException("email already registered");
              }
              AppUser user =
                  userRepo.insert(
                      new AppUser(
                          null,
                          normalizedEmail,
                          PasswordHasher.hash(rawPassword),
                          ActorType.USER,
                          true,
                          0,
                          null,
                          null));
              if (withOrg) {
                OrgRepository orgRepo = orgRepoFactory.create(tx);
                Org org = new Org();
                org.setName(normalizedOrgName);
                org.setSlug(uniqueSlug(orgRepo, normalizedOrgName));
                org.setActive(true);
                Org saved = orgRepo.insert(org);
                userRepo.insertOrgRole(user.getId(), saved.getId(), OrgRole.OWNER);
              }
              return user;
            });

    log.info("User registered: id={} withOrg={}", created.getId(), withOrg);
    return authService.issueSession(created, deviceInfo, sourceIp);
  }

  /**
   * Begin a password reset. Always returns without signalling whether the email exists (no account
   * enumeration): only an existing, active account gets a token minted and a link emailed. Any
   * internal failure is swallowed so the caller's response is uniform.
   */
  public void requestPasswordReset(String email, OffsetDateTime now) {
    try {
      String normalizedEmail = Text.normalizeEmail(email);
      if (!EmailAddresses.isSingleValid(normalizedEmail)) {
        return;
      }
      Optional<AppUser> user = userRepoFactory.create(rootDsl).findByEmail(normalizedEmail);
      if (user.isEmpty() || !user.get().isActive()) {
        return;
      }
      String rawToken =
          tokenService.mintAutonomous(user.get().getId(), AppUserTokenPurpose.PASSWORD_RESET, now);
      mailer.sendPasswordReset(normalizedEmail, tokenService.resetUrl(rawToken));
    } catch (RuntimeException e) {
      // Never let an internal error reveal that the email did/didn't exist.
      log.warn("password reset request failed", e);
    }
  }

  /** Complete a password reset with a PASSWORD_RESET token, then log the user in. */
  public LoginResult resetPassword(
      String rawToken, String newPassword, OffsetDateTime now, String deviceInfo, String sourceIp) {
    return redeem(
        AppUserTokenPurpose.PASSWORD_RESET,
        rawToken,
        newPassword,
        now,
        deviceInfo,
        sourceIp,
        false);
  }

  /**
   * Activate an invited account with an INVITE token (sets first password + activates), then log
   * in.
   */
  public LoginResult activate(
      String rawToken, String newPassword, OffsetDateTime now, String deviceInfo, String sourceIp) {
    return redeem(
        AppUserTokenPurpose.INVITE, rawToken, newPassword, now, deviceInfo, sourceIp, true);
  }

  private LoginResult redeem(
      AppUserTokenPurpose purpose,
      String rawToken,
      String newPassword,
      OffsetDateTime now,
      String deviceInfo,
      String sourceIp,
      boolean activating) {
    validatePassword(newPassword);

    Redeemed redeemed =
        rootDsl.transactionResult(
            cfg -> {
              DSLContext tx = DSL.using(cfg);
              UUID userId =
                  tokenService
                      .consume(tx, purpose, rawToken, now)
                      .orElseThrow(() -> new ValidationException("invalid or expired token"));
              UserRepository userRepo = userRepoFactory.create(tx);
              AppUser user =
                  userRepo
                      .findById(userId)
                      .orElseThrow(() -> new NotFoundException("User", userId));
              // A reset must not revive a disabled account; an invite legitimately activates one.
              if (!activating && !user.isActive()) {
                throw new AuthenticationException("Account is disabled");
              }
              userRepo.updatePasswordHash(userId, PasswordHasher.hash(newPassword));
              AppUser effective = user;
              if (activating && !user.isActive()) {
                effective = userRepo.setActive(userId, true);
              }
              int newVersion = userRepo.incrementTokenVersion(userId);
              return new Redeemed(effective, newVersion);
            });

    // Invalidate every prior session BEFORE minting the fresh one for this device.
    authService.propagateLogoutAll(redeemed.user().getId(), redeemed.newVersion());
    log.info("Credential {} redeemed for user id={}", purpose, redeemed.user().getId());
    return authService.issueSession(redeemed.user(), deviceInfo, sourceIp);
  }

  private record Redeemed(AppUser user, int newVersion) {}

  private static void validatePassword(String rawPassword) {
    if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
      throw new ValidationException(
          "password must be at least " + MIN_PASSWORD_LENGTH + " characters");
    }
  }

  /**
   * Derive a slug from the org name that satisfies {@link OrgService#validateSlug} and is unique.
   * Retries with a short random suffix on collision so a self-serve registrant is never blocked by
   * a name someone else already took.
   */
  private static String uniqueSlug(OrgRepository orgRepo, String name) {
    String base = slugify(name);
    if (base.length() < 3) {
      base = base.isEmpty() ? "org" : base + "-org";
    }
    if (base.length() > 56) {
      base = base.substring(0, 56);
    }
    String slug = base;
    for (int i = 0; i < 6 && orgRepo.existsBySlug(slug); i++) {
      slug = base + "-" + Integer.toHexString(RANDOM.nextInt(0x10000));
    }
    if (orgRepo.existsBySlug(slug)) {
      throw new ConflictException("could not derive a unique org slug; choose a different name");
    }
    OrgService.validateSlug(slug);
    return slug;
  }

  private static String slugify(String name) {
    return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
  }
}
