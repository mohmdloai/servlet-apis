package com.loai.inventory.common.security;

import io.jsonwebtoken.Jwts;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * RFC 8292 — the {@code Authorization: vapid t=<jwt>, k=<public key>} header a push service uses to
 * identify (and rate-limit, and contact) the application server. The JWT is ES256 over {@code aud}
 * = the push endpoint's <b>origin</b> (scheme + host + port, never the path — the path is the
 * subscription and is nobody's business in a token), {@code sub} = the operator's contact ({@code
 * mailto:} or {@code https:}), and {@code exp} at most 24 h out; 12 h here, so a clock a few hours
 * off in either direction still lands inside the service's window.
 *
 * <p>Signed with jjwt, which {@code common} already carries for the session tokens; ES256 there
 * produces the JOSE {@code R || S} signature form the RFC requires (not DER).
 */
public final class VapidSigner {

  /** Token lifetime. RFC 8292 caps it at 24 h; half that leaves room for clock skew. */
  static final Duration TOKEN_TTL = Duration.ofHours(12);

  private final VapidKeys keys;
  private final String subject;

  /**
   * @param subject the {@code sub} claim — {@code mailto:ops@example.com} or {@code
   *     https://example.com}
   */
  public VapidSigner(VapidKeys keys, String subject) {
    if (keys == null || !keys.isConfigured()) {
      throw new IllegalArgumentException("VapidSigner needs a configured key pair");
    }
    if (subject == null
        || subject.isBlank()
        || !(subject.startsWith("mailto:") || subject.startsWith("https://"))) {
      throw new IllegalArgumentException(
          "WEB_PUSH_SUBJECT must be a mailto: or https: URI (RFC 8292 §2.1)");
    }
    this.keys = keys;
    this.subject = subject.strip();
  }

  /** The complete {@code Authorization} header value for a POST to {@code endpoint}. */
  public String authorizationHeader(String endpoint, Instant now) {
    return "vapid t=" + token(endpoint, now) + ", k=" + keys.publicKeyBase64Url();
  }

  /** The bare JWT — visible for the test that verifies it against the public key. */
  String token(String endpoint, Instant now) {
    return Jwts.builder()
        .header()
        .type("JWT")
        .and()
        .audience()
        .single(originOf(endpoint))
        .subject(subject)
        .expiration(Date.from(now.plus(TOKEN_TTL)))
        .signWith(keys.privateKey(), Jwts.SIG.ES256)
        .compact();
  }

  /** {@code scheme://host[:port]} — the audience a push service checks the token against. */
  public static String originOf(String endpoint) {
    URI uri;
    try {
      uri = URI.create(endpoint);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("push endpoint is not a valid URI", e);
    }
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("push endpoint has no scheme or host");
    }
    StringBuilder origin = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
    if (uri.getPort() != -1) {
      origin.append(':').append(uri.getPort());
    }
    return origin.toString();
  }
}
