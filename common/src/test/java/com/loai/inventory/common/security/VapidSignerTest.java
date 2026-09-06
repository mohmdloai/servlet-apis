package com.loai.inventory.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.crypto.P256;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** RFC 8292 — the header shape, the audience, the lifetime, and the key-pair validation. */
class VapidSignerTest {

  private static final String ENDPOINT =
      "https://fcm.googleapis.com/fcm/send/dQw4w9WgXcQ:APA91bE_long_opaque_token";

  @Test
  void theHeaderParsesAsVapidTAndK_andTheTokenVerifiesWithThePublicKey() {
    KeyPair pair = P256.generate();
    VapidKeys keys = VapidKeys.of(pair);
    VapidSigner signer = new VapidSigner(keys, "mailto:ops@yabta3.com");
    Instant now = Instant.parse("2026-09-05T10:00:00Z");

    String header = signer.authorizationHeader(ENDPOINT, now);

    assertTrue(header.startsWith("vapid t="), header);
    String[] parts = header.substring("vapid ".length()).split(", ");
    assertEquals(2, parts.length);
    String token = parts[0].substring("t=".length());
    String k = parts[1].substring("k=".length());
    assertEquals(keys.publicKeyBase64Url(), k);
    assertFalse(k.contains("="), "base64url without padding");
    assertEquals(65, Base64.getUrlDecoder().decode(k).length);

    // Verify against the SAME fixed clock the token was signed with: the vector's `now` is a
    // date, so a parser on the wall clock starts throwing ExpiredJwt 12 h after that date (it did,
    // on 2026-09-06). The test is about the header's shape, not the wall clock.
    Claims claims =
        Jwts.parser()
            .verifyWith(pair.getPublic())
            .clock(() -> Date.from(now))
            .build()
            .parseSignedClaims(token)
            .getPayload();
    // aud is the ORIGIN — scheme + host only, never the path that identifies the subscription.
    assertEquals(java.util.Set.of("https://fcm.googleapis.com"), claims.getAudience());
    assertEquals("mailto:ops@yabta3.com", claims.getSubject());
    long ttl = claims.getExpiration().toInstant().getEpochSecond() - now.getEpochSecond();
    assertTrue(ttl > 0 && ttl <= Duration.ofHours(24).toSeconds(), "exp within RFC's 24 h");
    assertEquals(VapidSigner.TOKEN_TTL.toSeconds(), ttl);
  }

  @Test
  void theAudienceKeepsAnExplicitPort_andRejectsAHostlessEndpoint() {
    assertEquals(
        "https://push.example:8443", VapidSigner.originOf("https://push.example:8443/x/y"));
    assertEquals("https://push.example", VapidSigner.originOf("https://push.example/x?y=z"));
    assertThrows(IllegalArgumentException.class, () -> VapidSigner.originOf("not a url"));
    assertThrows(IllegalArgumentException.class, () -> VapidSigner.originOf("/relative/path"));
  }

  @Test
  void theSubjectMustBeMailtoOrHttps() {
    VapidKeys keys = VapidKeys.of(P256.generate());
    assertThrows(IllegalArgumentException.class, () -> new VapidSigner(keys, "ops@yabta3.com"));
    assertThrows(IllegalArgumentException.class, () -> new VapidSigner(keys, "http://yabta3.com"));
    assertThrows(IllegalArgumentException.class, () -> new VapidSigner(keys, " "));
  }

  @Test
  void keysFromEnv_roundTripTheRawEncodings() {
    KeyPair pair = P256.generate();
    String pub = b64(P256.encodePoint((ECPublicKey) pair.getPublic()));
    String priv = b64(P256.encodeScalar((ECPrivateKey) pair.getPrivate()));

    VapidKeys keys = VapidKeys.fromBase64Url(pub, priv);

    assertTrue(keys.isConfigured());
    assertEquals(pub, keys.publicKeyBase64Url());
  }

  @Test
  void keysFromEnv_bothBlankIsDisabled_oneBlankOrMalformedOrMismatchedIsAStartupFailure() {
    assertFalse(VapidKeys.fromBase64Url(null, null).isConfigured());
    assertFalse(VapidKeys.fromBase64Url(" ", "").isConfigured());

    KeyPair a = P256.generate();
    KeyPair b = P256.generate();
    String pubA = b64(P256.encodePoint((ECPublicKey) a.getPublic()));
    String privA = b64(P256.encodeScalar((ECPrivateKey) a.getPrivate()));
    String privB = b64(P256.encodeScalar((ECPrivateKey) b.getPrivate()));

    assertThrows(IllegalArgumentException.class, () -> VapidKeys.fromBase64Url(pubA, null));
    assertThrows(IllegalArgumentException.class, () -> VapidKeys.fromBase64Url(null, privA));
    assertThrows(IllegalArgumentException.class, () -> VapidKeys.fromBase64Url("not*b64", privA));
    assertThrows(
        IllegalArgumentException.class, () -> VapidKeys.fromBase64Url(pubA, b64(new byte[31])));
    // A public key that does not belong to the private key would 401 on every send, four retries
    // deep. It fails at boot instead.
    assertThrows(IllegalArgumentException.class, () -> VapidKeys.fromBase64Url(pubA, privB));
  }

  private static String b64(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }
}
