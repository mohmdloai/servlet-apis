package com.loai.inventory.service;

import com.loai.inventory.common.crypto.P256;
import com.loai.inventory.common.exception.ServiceUnavailableException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PushSubscription;
import com.loai.inventory.domain.repository.PushSubscriptionRepository;
import com.loai.inventory.domain.repository.PushSubscriptionRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.service.push.WebPushConfig;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A staff user's own push subscriptions ({@code /api/me/push-subscriptions}, V96). Own-account only
 * by construction: every method takes the caller's user id from the security context and never a
 * body-supplied one.
 *
 * <p>The producer side — which subscriptions are <em>live</em> for a notification — lives in {@link
 * NotificationService#pushTargetsFor}; this class is the device's side of the contract: subscribe
 * (validated), re-subscribe (refresh keys + token generation), unsubscribe (idempotent,
 * oracle-free), and the config read that tells a client whether any of it is on.
 */
public class PushSubscriptionService {

  private static final Logger log = LoggerFactory.getLogger(PushSubscriptionService.class);

  /** Push endpoints are long, but not this long. Bounds the row and the log line. */
  static final int MAX_ENDPOINT_LENGTH = 2048;

  static final int MAX_USER_AGENT_LENGTH = 255;

  private static final int AUTH_BYTES = 16;

  private final DSLContext rootDsl;
  private final PushSubscriptionRepositoryFactory repoFactory;
  private final UserRepositoryFactory userRepoFactory;
  private final WebPushConfig config;

  public PushSubscriptionService(
      DSLContext rootDsl,
      PushSubscriptionRepositoryFactory repoFactory,
      UserRepositoryFactory userRepoFactory,
      WebPushConfig config) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.userRepoFactory = userRepoFactory;
    this.config = config == null ? WebPushConfig.disabled() : config;
  }

  /** What one subscribe call did: the row as stored, and whether it was new. */
  public record SubscribeResult(PushSubscription subscription, boolean created) {}

  public WebPushConfig config() {
    return config;
  }

  /**
   * Store (or refresh) this browser's subscription for {@code userId}.
   *
   * @throws ServiceUnavailableException when no VAPID key pair is configured (503)
   * @throws ValidationException on a missing / non-https endpoint or keys that do not decode to a
   *     65-byte P-256 point and a 16-byte secret (400)
   */
  public SubscribeResult subscribe(
      UUID userId, String endpoint, String p256dh, String auth, String userAgent) {
    if (!config.enabled()) {
      throw new ServiceUnavailableException("Web Push is not configured on this server");
    }
    String cleanEndpoint = validateEndpoint(endpoint);
    String cleanP256dh = validateP256dh(p256dh);
    String cleanAuth = validateAuth(auth);
    String cleanUserAgent = trimUserAgent(userAgent);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          PushSubscriptionRepository repo = repoFactory.create(txDsl);
          // The row is live only for the token generation it was minted in — read it in the same
          // transaction as the write so a concurrent logout-all cannot land between the two.
          int tokenVersion = userRepoFactory.create(txDsl).getTokenVersion(userId);
          boolean existed = repo.findByEndpoint(cleanEndpoint).isPresent();
          PushSubscription saved =
              repo.upsert(
                  userId, cleanEndpoint, cleanP256dh, cleanAuth, cleanUserAgent, tokenVersion);
          log.info(
              "push subscription {} for user {} ({})",
              existed ? "refreshed" : "created",
              userId,
              com.loai.inventory.common.security.VapidSigner.originOf(cleanEndpoint));
          return new SubscribeResult(saved, !existed);
        });
  }

  /**
   * Remove this browser's subscription. Idempotent and own-only: an endpoint that is not the
   * caller's — or never existed — is the same silent success (no ownership oracle).
   */
  public void unsubscribe(UUID userId, String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new ValidationException("endpoint is required");
    }
    String clean = endpoint.strip();
    rootDsl.transaction(
        cfg -> {
          int deleted = repoFactory.create(DSL.using(cfg)).deleteByUserAndEndpoint(userId, clean);
          if (deleted > 0) {
            log.info("push subscription removed for user {}", userId);
          }
        });
  }

  /** The caller's live subscriptions — for the "devices with push" read (no endpoint, no keys). */
  public List<PushSubscription> listLive(UUID userId) {
    return repoFactory.create(rootDsl).findLiveByUser(userId);
  }

  private static String validateEndpoint(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new ValidationException("endpoint is required");
    }
    String clean = endpoint.strip();
    if (clean.length() > MAX_ENDPOINT_LENGTH) {
      throw new ValidationException("endpoint is too long");
    }
    URI uri;
    try {
      uri = URI.create(clean);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("endpoint is not a valid URL");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
      throw new ValidationException("endpoint must be an https URL");
    }
    return clean;
  }

  private static String validateP256dh(String p256dh) {
    byte[] raw = decode(p256dh, "keys.p256dh");
    try {
      P256.decodePoint(raw);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("keys.p256dh must be a 65-byte uncompressed P-256 point");
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static String validateAuth(String auth) {
    byte[] raw = decode(auth, "keys.auth");
    if (raw.length != AUTH_BYTES) {
      throw new ValidationException("keys.auth must decode to 16 bytes");
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  /** Browsers hand out base64url; some clients re-encode with standard base64. Accept both. */
  private static byte[] decode(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ValidationException(field + " is required");
    }
    String v = value.strip();
    try {
      return Base64.getUrlDecoder().decode(v);
    } catch (IllegalArgumentException ignored) {
      // fall through
    }
    try {
      return Base64.getDecoder().decode(v);
    } catch (IllegalArgumentException e) {
      throw new ValidationException(field + " is not base64");
    }
  }

  private static String trimUserAgent(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return null;
    }
    String s = userAgent.strip();
    return s.length() <= MAX_USER_AGENT_LENGTH ? s : s.substring(0, MAX_USER_AGENT_LENGTH);
  }
}
