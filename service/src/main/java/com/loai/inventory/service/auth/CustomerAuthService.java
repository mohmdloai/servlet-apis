package com.loai.inventory.service.auth;

import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.service.email.EmailAddresses;
import com.loai.inventory.service.email.EmailGate;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The customer-portal authentication service — the passwordless OTP login and the isolated,
 * rotating session it mints. Mirrors {@link AuthService} on the customer plane, sharing none of its
 * state: a second {@link JwtUtil} ({@code aud=customer}), the {@link CustomerSessionStore}, and the
 * {@link CustomerOtpStore}. Returns raw token strings; the servlet writes the portal cookies. See
 * {@code stories/portal_auth_core.md} §{@code CustomerAuthService}.
 *
 * <p><b>OTP delivery is synchronous and preference-bypassing.</b> The login code is sent directly
 * through the {@link EmailSender} inside {@code request-code} — <em>not</em> enqueued through
 * {@code NotificationService}. A sign-in code is a transactional security message: it must not be
 * suppressible by a customer's marketing opt-out (the one-click unsubscribe would otherwise lock
 * them out) and must not wait on the delivery sweeper. A send failure is swallowed (logged, not
 * thrown) so the response stays the uniform {@code 200} — a {@code 5xx} on a real address would be
 * an enumeration oracle.
 */
public class CustomerAuthService {

  private static final Logger log = LoggerFactory.getLogger(CustomerAuthService.class);

  /**
   * The floor customer {@code token_version}; a logout-all bumps it (Redis-only — no DB column).
   */
  static final int BASE_TOKEN_VERSION = 1;

  private final DSLContext rootDsl;
  private final CustomerRepositoryFactory customerRepositoryFactory;
  private final OrgRepositoryFactory orgRepositoryFactory;
  private final CustomerOtpStore otpStore;
  private final CustomerSessionStore sessionStore;
  private final JwtUtil customerJwtUtil;
  private final EmailSender emailSender;
  private final EmailGate emailGate;
  private final int perEmailSendLimit;
  private final int perEmailWindowSeconds;

  public CustomerAuthService(
      DSLContext rootDsl,
      CustomerRepositoryFactory customerRepositoryFactory,
      OrgRepositoryFactory orgRepositoryFactory,
      CustomerOtpStore otpStore,
      CustomerSessionStore sessionStore,
      JwtUtil customerJwtUtil,
      EmailSender emailSender,
      EmailGate emailGate,
      int perEmailSendLimit,
      int perEmailWindowSeconds) {
    this.rootDsl = rootDsl;
    this.customerRepositoryFactory = customerRepositoryFactory;
    this.orgRepositoryFactory = orgRepositoryFactory;
    this.otpStore = otpStore;
    this.sessionStore = sessionStore;
    this.customerJwtUtil = customerJwtUtil;
    this.emailSender = emailSender;
    this.emailGate = emailGate;
    this.perEmailSendLimit = perEmailSendLimit;
    this.perEmailWindowSeconds = perEmailWindowSeconds;
  }

  /** A freshly-minted portal session (the servlet turns these into cookies + a body). */
  public record SessionResult(
      String accessToken, String refreshToken, long expiresIn, Customer customer) {}

  // Bootstrap: request-code / verify-code

  /**
   * Send a login code for {@code (orgSlug, email)} if — and only if — that pair maps to a real
   * customer, but <em>always</em> report success. An unknown email, a malformed email, or a
   * throttled inbox all take the same no-send path, so the response is a constant {@code
   * {sent:true}} with no account-existence oracle (epic decision #8).
   */
  public void requestCode(String orgSlug, String email) {
    Org org = resolveActiveOrg(orgSlug);
    String normalized = normalizeEmail(email);
    if (normalized == null) {
      return; // malformed — silently no-op (uniform response)
    }
    // MX leg only (story 87) — a code sent to an undeliverable domain could never arrive, so
    // skipping the send changes nothing observable while saving the synchronous SMTP call. The
    // blocklist is deliberately NOT consulted here: a disposable-email customer can exist via
    // lenient checkout and must still be able to log in. Same uniform response either way.
    if (emailGate.undeliverable(normalized)) {
      log.info("OTP send skipped for an undeliverable email domain");
      return;
    }
    Optional<Customer> customer =
        customerRepositoryFactory.create(rootDsl).findByEmail(org.getId(), normalized);
    if (customer.isEmpty()) {
      return; // no customer → never send, but the caller still returns {sent:true}
    }
    // Per-email inbox throttle: advisory — a suppressed send still returns the uniform response.
    if (!otpStore.allowSend(org.getId(), normalized, perEmailSendLimit, perEmailWindowSeconds)) {
      log.info("OTP send throttled for org {} (per-email limit)", org.getId());
      return;
    }
    String code = otpStore.issueCode(org.getId(), normalized, System.currentTimeMillis());
    UUID customerId = customer.get().getId();
    // Send the code synchronously, bypassing the notification/preference pipeline (see the class
    // note): a login code is transactional and must not be suppressible or delayed.
    sendCode(normalized, code);
    log.info("Portal login code issued for customer {} in org {}", customerId, org.getId());
  }

  /**
   * Transmit the login code now, directly through the {@link EmailSender}. A provider failure is
   * swallowed (logged, never thrown, no address logged) so {@code request-code} still returns the
   * uniform response — availability, not an enumeration oracle. The code is a 6-digit numeric, so
   * interpolating it into the HTML is injection-safe. No unsubscribe footer: this is not a
   * subscription email.
   */
  private void sendCode(String toEmail, String code) {
    String subject = "Your sign-in code";
    String html =
        "<p>Your sign-in code is <strong>"
            + code
            + "</strong>. It expires in 10 minutes.</p>"
            + "<p>If you didn't request it, you can ignore this email.</p>";
    try {
      emailSender.send(new EmailMessage(toEmail, subject, html));
    } catch (RuntimeException e) {
      log.warn("Failed to send a portal login code (email provider error)", e);
    }
  }

  /**
   * Verify a presented code and, on success, mint the portal session + stamp {@code
   * email_verified_at}. Every failure — unknown email, wrong/expired/exhausted code — is one
   * generic {@link AuthenticationException} (no oracle).
   */
  public SessionResult verifyCode(
      String orgSlug, String email, String code, String deviceInfo, String sourceIp) {
    Org org = resolveActiveOrg(orgSlug);
    String normalized = normalizeEmail(email);
    UUID orgId = org.getId();
    // Look up the customer first, but do NOT short-circuit on absence — always run verify so timing
    // and the error are uniform whether or not the email has a customer.
    Optional<Customer> customer =
        normalized == null
            ? Optional.empty()
            : customerRepositoryFactory.create(rootDsl).findByEmail(orgId, normalized);
    CustomerOtpStore.VerifyResult result =
        normalized == null
            ? CustomerOtpStore.VerifyResult.FAIL
            : otpStore.verify(orgId, normalized, code, System.currentTimeMillis());
    if (result != CustomerOtpStore.VerifyResult.MATCH || customer.isEmpty()) {
      throw new AuthenticationException("Invalid or expired code");
    }
    UUID customerId = customer.get().getId();
    OffsetDateTime now = OffsetDateTime.now();
    customerRepositoryFactory.create(rootDsl).markEmailVerified(orgId, customerId, now);
    // Re-read so the returned profile carries the freshly-stamped email_verified_at.
    Customer fresh =
        customerRepositoryFactory
            .create(rootDsl)
            .findById(orgId, customerId)
            .orElseThrow(() -> new NotFoundException("Customer", customerId));
    log.info("Portal login verified for customer {} in org {}", customerId, orgId);
    return issueSession(orgId, fresh, deviceInfo, sourceIp);
  }

  // Session lifecycle

  private SessionResult issueSession(
      UUID orgId, Customer customer, String deviceInfo, String sourceIp) {
    UUID customerId = customer.getId();
    int version = sessionStore.getCachedTokenVersion(orgId, customerId).orElse(BASE_TOKEN_VERSION);
    UUID familyId = UUID.randomUUID();
    String accessToken =
        customerJwtUtil.generateCustomerAccessToken(customerId, orgId, version, familyId);
    String rawRefresh = UUID.randomUUID().toString();
    sessionStore.store(
        CustomerSessionStore.hashRefresh(rawRefresh),
        new CustomerSessionStore.TokenData(
            orgId, customerId, familyId, deviceInfo, sourceIp, Instant.now()));
    sessionStore.cacheTokenVersion(orgId, customerId, version);
    return new SessionResult(
        accessToken, rawRefresh, customerJwtUtil.getAccessTtlMillis() / 1000, customer);
  }

  /** Rotate a refresh token within its family (reuse of a rotated-away token → 401). */
  public SessionResult refresh(String rawRefreshToken, String sourceIp) {
    if (rawRefreshToken == null) {
      throw new AuthenticationException("Invalid refresh token");
    }
    String hash = CustomerSessionStore.hashRefresh(rawRefreshToken);
    CustomerSessionStore.TokenData data =
        sessionStore
            .find(hash)
            .orElseThrow(
                () -> {
                  // Proven reuse (the token was rotated away, so two holders exist) burns the whole
                  // family and its outstanding access tokens; an ordinary unknown token just 401s.
                  // A re-presentation within seconds of the rotation is neither — it is the portal
                  // bounce route racing the page's own refresh over one cookie jar, and burning
                  // the family there logs the shopper out everywhere for nothing. Same rule as the
                  // staff plane — see AuthService#refresh.
                  if (sessionStore.rotatedWithinGrace(hash)) {
                    log.debug(
                        "Customer refresh token re-presented inside the rotation grace window —"
                            + " 401 only");
                  } else {
                    sessionStore
                        .findRotatedFamily(hash)
                        .ifPresent(
                            ref -> {
                              log.warn(
                                  "Customer refresh-token reuse detected — revoking family {} of"
                                      + " customer {} in org {}",
                                  ref.familyId(),
                                  ref.customerId(),
                                  ref.orgId());
                              sessionStore.revokeFamily(
                                  ref.familyId(), ref.orgId(), ref.customerId());
                              sessionStore.denyFamilyAccess(
                                  ref.familyId(), customerJwtUtil.getAccessTtlMillis() / 1000);
                            });
                  }
                  return new AuthenticationException("Invalid refresh token");
                });
    // Rotate: retire the presented token behind a tombstone, so its next presentation is
    // identifiable as reuse rather than as an anonymous bad token.
    sessionStore.rotateAway(hash, data);

    UUID orgId = data.orgId();
    UUID customerId = data.customerId();
    Customer customer =
        customerRepositoryFactory
            .create(rootDsl)
            .findById(orgId, customerId)
            .orElseThrow(() -> new AuthenticationException("Customer not found"));

    int version = sessionStore.getCachedTokenVersion(orgId, customerId).orElse(BASE_TOKEN_VERSION);
    String accessToken =
        customerJwtUtil.generateCustomerAccessToken(customerId, orgId, version, data.familyId());
    String newRawRefresh = UUID.randomUUID().toString();
    sessionStore.store(
        CustomerSessionStore.hashRefresh(newRawRefresh),
        new CustomerSessionStore.TokenData(
            orgId, customerId, data.familyId(), data.deviceInfo(), sourceIp, Instant.now()));
    sessionStore.cacheTokenVersion(orgId, customerId, version);
    return new SessionResult(
        accessToken, newRawRefresh, customerJwtUtil.getAccessTtlMillis() / 1000, customer);
  }

  /** Log out this device: kill its access token now (family denylist) and revoke its refresh. */
  public void logout(String rawRefreshToken) {
    if (rawRefreshToken == null) {
      return;
    }
    String hash = CustomerSessionStore.hashRefresh(rawRefreshToken);
    sessionStore
        .find(hash)
        .ifPresent(d -> sessionStore.denyFamilyAccess(d.familyId(), accessTtlSeconds()));
    sessionStore.revoke(hash);
  }

  /** Log out everywhere: bump the customer's token_version + revoke every family (fail-closed). */
  public void logoutAll(UUID orgId, UUID customerId) {
    int current = sessionStore.getCachedTokenVersion(orgId, customerId).orElse(BASE_TOKEN_VERSION);
    int newVersion = current + 1;
    try {
      sessionStore.cacheTokenVersion(orgId, customerId, newVersion);
      sessionStore.revokeAllForCustomer(orgId, customerId);
    } catch (RuntimeException e) {
      log.warn(
          "customer logout-all cache write failed for ({}, {}); dropping cached version to fail"
              + " closed",
          orgId,
          customerId,
          e);
      sessionStore.invalidateTokenVersion(orgId, customerId);
    }
  }

  public java.util.List<CustomerSessionStore.SessionInfo> listSessions(
      UUID orgId, UUID customerId) {
    return sessionStore.listSessions(orgId, customerId);
  }

  /** The staff plane's {@link AuthService#sessionFamilyOf} mirrored — same no-guess contract. */
  public java.util.Optional<UUID> sessionFamilyOf(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      return java.util.Optional.empty();
    }
    return sessionStore
        .find(CustomerSessionStore.hashRefresh(rawRefreshToken))
        .map(CustomerSessionStore.TokenData::familyId);
  }

  /**
   * Sign one device out — the staff plane's {@code AuthService#revokeSession} scoped to a customer.
   * {@code revokeFamily} is the ownership check: it returns false unless the family is a member of
   * *this* {@code (orgId, customerId)}'s set, so another shopper's family id is a 404 and never a
   * revoke. The denylist write is what kills that device's outstanding access token now rather than
   * at its next refresh — without it the revoked phone keeps reading for up to the access TTL.
   *
   * <p>Deliberately NOT a {@code token_version} bump: that is {@link #logoutAll}'s hammer and would
   * sign out every other device too, which is the opposite of what a per-device revoke means.
   */
  public void revokeSession(UUID orgId, UUID customerId, UUID familyId) {
    if (!sessionStore.revokeFamily(familyId, orgId, customerId)) {
      throw new NotFoundException("Session not found: " + familyId);
    }
    sessionStore.denyFamilyAccess(familyId, accessTtlSeconds());
  }

  // Filter passthroughs (fail-closed token_version + per-device kill)

  public boolean isTokenVersionValid(UUID orgId, UUID customerId, int claimedVersion) {
    Optional<Integer> cached = sessionStore.getCachedTokenVersion(orgId, customerId);
    return cached.isPresent() && cached.get() == claimedVersion;
  }

  public boolean isDeviceRevoked(UUID familyId) {
    return sessionStore.isFamilyAccessRevoked(familyId);
  }

  // helpers

  private Org resolveActiveOrg(String orgSlug) {
    Org org =
        orgRepositoryFactory
            .create(rootDsl)
            .findBySlug(orgSlug)
            .orElseThrow(() -> new NotFoundException("Store not found: " + orgSlug));
    if (!org.isActive()) {
      throw new NotFoundException("Store not found: " + orgSlug);
    }
    return org;
  }

  /**
   * Normalize an email for lookup/challenge keying, or {@code null} when not a single valid one.
   */
  private static String normalizeEmail(String raw) {
    String normalized = Text.normalizeEmail(raw);
    return EmailAddresses.isSingleValid(normalized) ? normalized : null;
  }

  private long accessTtlSeconds() {
    return customerJwtUtil.getAccessTtlMillis() / 1000;
  }
}
