package com.loai.inventory.common.security;

import java.util.UUID;
import org.mindrot.jbcrypt.BCrypt;

public class PasswordHasher {

  /**
   * A hash of an unguessable value at the same cost factor {@link #hash} uses, computed once at
   * class-load. It exists so a login that has no real hash to check can still spend the same ~100
   * ms of bcrypt — see {@link #verifyDummy}.
   */
  private static final String DUMMY_HASH =
      BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt());

  private PasswordHasher() {}

  public static String hash(String rawPassword) {
    return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
  }

  public static boolean verify(String rawPassword, String hash) {
    return BCrypt.checkpw(rawPassword, hash);
  }

  /**
   * Burn one bcrypt verification against a fixed internal hash and return {@code false}.
   *
   * <p>The point is the time, not the answer: an unknown email used to return immediately while a
   * known one paid for a bcrypt compare, and that difference is measurable from outside — an
   * account-enumeration oracle on a public endpoint. Callers use this on the no-such-user branch so
   * both paths cost the same. It always fails; there is no password that matches.
   */
  public static boolean verifyDummy(String rawPassword) {
    BCrypt.checkpw(rawPassword == null ? "" : rawPassword, DUMMY_HASH);
    return false;
  }
}
