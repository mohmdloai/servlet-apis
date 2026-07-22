package com.loai.inventory.service.email;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The email <em>quality</em> gate (story 87) — layered after the existing syntax gate ({@code
 * Text.normalizeEmail} + {@link EmailAddresses#isSingleValid}), never replacing it: a
 * disposable-domain blocklist plus an MX deliverability check. Callers pick their strictness per
 * flow:
 *
 * <ul>
 *   <li><b>register</b> — {@link #check} strict: any non-OK verdict is a 400.
 *   <li><b>portal OTP</b> — {@link #undeliverable} only (MX leg, silent skip): the blocklist is
 *       deliberately NOT consulted there — a disposable-email customer can legitimately exist via
 *       lenient checkout and must still be able to log in.
 *   <li><b>checkout</b> — {@link #check} lenient: the caller logs the verdict and proceeds.
 * </ul>
 *
 * <p>Both legs fail open: an empty/unloadable blocklist matches nothing, an {@code UNKNOWN} MX
 * answer passes, and the whole MX leg short-circuits to a pass while {@code EMAIL_MX_CHECK_ENABLED}
 * is off (the ship-dark default).
 */
public class EmailGate {

  private static final Logger log = LoggerFactory.getLogger(EmailGate.class);

  /** Classpath location of the vendored disposable-email-domains snapshot (CC0). */
  static final String BLOCKLIST_RESOURCE = "/email/disposable_email_blocklist.conf";

  public enum Verdict {
    OK,
    /** The domain (or a parent of it) is on the disposable/throwaway blocklist. */
    DISPOSABLE,
    /** DNS proves the domain cannot receive mail (NXDOMAIN / no MX+A / null MX). */
    UNDELIVERABLE
  }

  private final Set<String> blockedDomains;
  private final MxResolver mxResolver;
  private final boolean mxCheckEnabled;

  public EmailGate(Set<String> blockedDomains, MxResolver mxResolver, boolean mxCheckEnabled) {
    this.blockedDomains = Set.copyOf(blockedDomains);
    this.mxResolver = mxResolver;
    this.mxCheckEnabled = mxCheckEnabled;
    log.info(
        "EmailGate: {} blocked domains, MX check {}",
        blockedDomains.size(),
        mxCheckEnabled ? "on" : "off");
  }

  /**
   * Full verdict for an <b>already-normalized, syntactically valid</b> email: blocklist first
   * (cheap, in-memory), then the MX leg. Never throws.
   */
  public Verdict check(String normalizedEmail) {
    String domain = domainOf(normalizedEmail);
    if (domain == null) {
      return Verdict.OK; // not our layer — the syntax gate upstream owns malformed input
    }
    if (isBlocked(domain)) {
      return Verdict.DISPOSABLE;
    }
    return undeliverableDomain(domain) ? Verdict.UNDELIVERABLE : Verdict.OK;
  }

  /** The MX leg alone — the portal-OTP check (no blocklist; see the class note). Never throws. */
  public boolean undeliverable(String normalizedEmail) {
    String domain = domainOf(normalizedEmail);
    return domain != null && undeliverableDomain(domain);
  }

  private boolean undeliverableDomain(String domain) {
    if (!mxCheckEnabled) {
      return false;
    }
    // UNKNOWN (timeout/SERVFAIL/…) passes — only proven undeliverability rejects.
    return mxResolver.lookup(domain) == MxResolver.MxResult.UNDELIVERABLE;
  }

  /**
   * Registrable-domain blocklist match: the full domain, then each parent suffix ({@code
   * a.b.mailinator.com} → {@code b.mailinator.com} → {@code mailinator.com}) — the list stores
   * second-level domains, and a subdomain of a burner host is the same burner host.
   */
  private boolean isBlocked(String domain) {
    String candidate = domain;
    while (true) {
      if (blockedDomains.contains(candidate)) {
        return true;
      }
      int dot = candidate.indexOf('.');
      if (dot < 0) {
        return false;
      }
      candidate = candidate.substring(dot + 1);
    }
  }

  /**
   * The bare lowercase domain of {@code email}, or null when there is no usable {@code @domain}.
   */
  public static String domainOf(String email) {
    if (email == null) {
      return null;
    }
    int at = email.lastIndexOf('@');
    if (at < 0 || at == email.length() - 1) {
      return null;
    }
    String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
    return domain.isBlank() ? null : domain;
  }

  /**
   * Load the blocklist: {@code overridePath} (the {@code EMAIL_BLOCKLIST_PATH} env — a file dropped
   * on the host to update the list without a rebuild) when given, else the vendored classpath
   * snapshot. Unreadable/missing input → empty set + WARN, never a startup failure (fail-open).
   */
  public static Set<String> loadBlocklist(String overridePath) {
    try {
      if (overridePath != null && !overridePath.isBlank()) {
        try (BufferedReader reader = Files.newBufferedReader(Path.of(overridePath.trim()))) {
          return parse(reader);
        }
      }
      InputStream resource = EmailGate.class.getResourceAsStream(BLOCKLIST_RESOURCE);
      if (resource == null) {
        log.warn(
            "Disposable-email blocklist resource {} missing — gate matches nothing",
            BLOCKLIST_RESOURCE);
        return Set.of();
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
        return parse(reader);
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Failed to load the disposable-email blocklist — gate matches nothing", e);
      return Set.of();
    }
  }

  private static Set<String> parse(BufferedReader reader) throws IOException {
    Set<String> domains = new HashSet<>();
    String line;
    while ((line = reader.readLine()) != null) {
      String trimmed = line.trim().toLowerCase(Locale.ROOT);
      if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
        domains.add(trimmed);
      }
    }
    return domains;
  }
}
