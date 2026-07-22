package com.loai.inventory.service.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.service.email.EmailGate.Verdict;
import com.loai.inventory.service.email.MxResolver.MxResult;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * {@link EmailGate} verdict composition (story 87): blocklist precedence + registrable-domain
 * matching, the fail-open UNKNOWN mapping, and the ship-dark MX toggle. The DNS leg itself is
 * covered by {@link DnsJavaMxResolverTest}.
 */
class EmailGateTest {

  private static final Set<String> BLOCKED = Set.of("mailinator.com", "10minutemail.com");

  private static MxResolver fixed(MxResult result) {
    return domain -> result;
  }

  // ── blocklist leg ─────────────────────────────────────────────────────────

  @Test
  void blockedDomain_isDisposable() {
    EmailGate gate = new EmailGate(BLOCKED, fixed(MxResult.DELIVERABLE), true);
    assertEquals(Verdict.DISPOSABLE, gate.check("someone@mailinator.com"));
  }

  @Test
  void subdomainOfBlockedDomain_isDisposable() {
    EmailGate gate = new EmailGate(BLOCKED, fixed(MxResult.DELIVERABLE), true);
    assertEquals(Verdict.DISPOSABLE, gate.check("x@a.b.mailinator.com"));
  }

  @Test
  void unlistedDomain_passesTheBlocklist() {
    EmailGate gate = new EmailGate(BLOCKED, fixed(MxResult.DELIVERABLE), true);
    assertEquals(Verdict.OK, gate.check("someone@example.com"));
  }

  @Test
  void suffixOverlapWithoutLabelBoundary_isNotAMatch() {
    // notmailinator.com merely ends with a blocked string — label-wise it is a different domain.
    EmailGate gate = new EmailGate(BLOCKED, fixed(MxResult.DELIVERABLE), true);
    assertEquals(Verdict.OK, gate.check("x@notmailinator.com"));
  }

  @Test
  void blocklistWins_andShortCircuitsTheMxLookup() {
    AtomicInteger lookups = new AtomicInteger();
    MxResolver counting =
        domain -> {
          lookups.incrementAndGet();
          return MxResult.UNDELIVERABLE;
        };
    EmailGate gate = new EmailGate(BLOCKED, counting, true);
    assertEquals(Verdict.DISPOSABLE, gate.check("x@mailinator.com"));
    assertEquals(0, lookups.get(), "the cheap in-memory check must run before any DNS");
  }

  // ── MX leg ────────────────────────────────────────────────────────────────

  @Test
  void undeliverableDomain_isUndeliverable() {
    EmailGate gate = new EmailGate(BLOCKED, fixed(MxResult.UNDELIVERABLE), true);
    assertEquals(Verdict.UNDELIVERABLE, gate.check("x@nosuchdomain.example"));
  }

  @Test
  void unknownMxAnswer_passes_failOpen() {
    EmailGate gate = new EmailGate(BLOCKED, fixed(MxResult.UNKNOWN), true);
    assertEquals(Verdict.OK, gate.check("x@flaky-dns.example"));
  }

  @Test
  void mxToggleOff_shortCircuitsToOk_withoutResolving() {
    AtomicInteger lookups = new AtomicInteger();
    MxResolver counting =
        domain -> {
          lookups.incrementAndGet();
          return MxResult.UNDELIVERABLE;
        };
    EmailGate gate = new EmailGate(BLOCKED, counting, false);
    assertEquals(Verdict.OK, gate.check("x@nosuchdomain.example"));
    assertEquals(0, lookups.get(), "the dark toggle must not even ask");
  }

  // ── undeliverable() — the portal-OTP leg ──────────────────────────────────

  @Test
  void undeliverable_ignoresTheBlocklist() {
    // A disposable-domain customer can exist via lenient checkout — the OTP path must not consult
    // the blocklist, only real deliverability.
    EmailGate gate = new EmailGate(BLOCKED, fixed(MxResult.DELIVERABLE), true);
    assertFalse(gate.undeliverable("x@mailinator.com"));
    EmailGate dead = new EmailGate(BLOCKED, fixed(MxResult.UNDELIVERABLE), true);
    assertTrue(dead.undeliverable("x@mailinator.com"));
  }

  @Test
  void undeliverable_isFalseWhileTheToggleIsOff() {
    EmailGate gate = new EmailGate(BLOCKED, fixed(MxResult.UNDELIVERABLE), false);
    assertFalse(gate.undeliverable("x@nosuchdomain.example"));
  }

  // ── domainOf ──────────────────────────────────────────────────────────────

  @Test
  void domainOf_extractsAndLowercases() {
    assertEquals("example.com", EmailGate.domainOf("User@Example.COM"));
    assertNull(EmailGate.domainOf(null));
    assertNull(EmailGate.domainOf("no-at-sign"));
    assertNull(EmailGate.domainOf("trailing@"));
  }

  @Test
  void malformedEmail_isOk_notOurLayer() {
    // The syntax gate upstream owns malformed input; the quality gate never double-judges it.
    EmailGate gate = new EmailGate(BLOCKED, fixed(MxResult.UNDELIVERABLE), true);
    assertEquals(Verdict.OK, gate.check("not-an-email"));
  }

  // ── loadBlocklist ─────────────────────────────────────────────────────────

  @Test
  void loadBlocklist_readsTheVendoredSnapshot() {
    Set<String> domains = EmailGate.loadBlocklist(null);
    assertTrue(domains.contains("mailinator.com"), "the canonical burner domain is present");
    assertTrue(domains.size() > 1000, "the vendored snapshot is the full list, not a stub");
  }

  @Test
  void loadBlocklist_missingOverridePath_isEmptyNotFatal() {
    assertEquals(Set.of(), EmailGate.loadBlocklist("/nonexistent/blocklist.conf"));
  }
}
