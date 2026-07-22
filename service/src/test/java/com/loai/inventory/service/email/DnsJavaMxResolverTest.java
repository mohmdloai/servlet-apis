package com.loai.inventory.service.email;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.loai.inventory.service.email.DnsJavaMxResolver.Outcome;
import com.loai.inventory.service.email.MxResolver.MxResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Type;

/**
 * {@link DnsJavaMxResolver}'s result mapping with the dnsjava seam stubbed — no network. The
 * RFC-shaped decisions under test: null MX (7505), the implicit-MX A/AAAA fallback (5321 §5.1),
 * NXDOMAIN, and everything-flaky → UNKNOWN (the gate's fail-open input).
 */
class DnsJavaMxResolverTest {

  private static MXRecord mx(String host, String target) throws Exception {
    return new MXRecord(Name.fromString(host), DClass.IN, 3600, 10, Name.fromString(target));
  }

  /** A resolver whose answers are keyed by record type; unlisted types answer TYPE_NOT_FOUND. */
  private static DnsJavaMxResolver stubbed(Map<Integer, Outcome> byType) {
    return new DnsJavaMxResolver(
        (domain, type) -> byType.getOrDefault(type, new Outcome(Lookup.TYPE_NOT_FOUND, null)));
  }

  @Test
  void mxRecordsPresent_isDeliverable() throws Exception {
    var resolver =
        stubbed(
            Map.of(
                Type.MX,
                new Outcome(Lookup.SUCCESSFUL, List.of(mx("example.com.", "mail.example.com.")))));
    assertEquals(MxResult.DELIVERABLE, resolver.lookup("example.com"));
  }

  @Test
  void nullMx_isUndeliverable() throws Exception {
    // RFC 7505: a single MX with the root target is the domain's explicit "no mail, ever".
    var resolver =
        stubbed(Map.of(Type.MX, new Outcome(Lookup.SUCCESSFUL, List.of(mx("example.com.", ".")))));
    assertEquals(MxResult.UNDELIVERABLE, resolver.lookup("example.com"));
  }

  @Test
  void nullMxAmongOthers_isStillDeliverable() throws Exception {
    // A null MX only counts when it is the ONLY record.
    var resolver =
        stubbed(
            Map.of(
                Type.MX,
                new Outcome(
                    Lookup.SUCCESSFUL,
                    List.of(mx("example.com.", "."), mx("example.com.", "mail.example.com.")))));
    assertEquals(MxResult.DELIVERABLE, resolver.lookup("example.com"));
  }

  @Test
  void nxdomain_isUndeliverable() {
    var resolver = stubbed(Map.of(Type.MX, new Outcome(Lookup.HOST_NOT_FOUND, null)));
    assertEquals(MxResult.UNDELIVERABLE, resolver.lookup("nosuchdomain.example"));
  }

  @Test
  void noMxButARecord_isDeliverable_implicitMx() {
    var resolver =
        stubbed(
            Map.of(
                Type.MX, new Outcome(Lookup.TYPE_NOT_FOUND, null),
                Type.A, new Outcome(Lookup.SUCCESSFUL, null)));
    assertEquals(MxResult.DELIVERABLE, resolver.lookup("a-only.example"));
  }

  @Test
  void noMxButAaaaRecord_isDeliverable_implicitMx() {
    var resolver =
        stubbed(
            Map.of(
                Type.MX, new Outcome(Lookup.TYPE_NOT_FOUND, null),
                Type.AAAA, new Outcome(Lookup.SUCCESSFUL, null)));
    assertEquals(MxResult.DELIVERABLE, resolver.lookup("v6-only.example"));
  }

  @Test
  void noMxNoAddressRecords_isUndeliverable() {
    var resolver = stubbed(Map.of(Type.MX, new Outcome(Lookup.TYPE_NOT_FOUND, null)));
    assertEquals(MxResult.UNDELIVERABLE, resolver.lookup("web-less.example"));
  }

  @Test
  void noMxAndFlakyAddressAnswer_isUnknown() {
    // TRY_AGAIN on the fallback forbids concluding "no host at all" — fail open.
    var resolver =
        stubbed(
            Map.of(
                Type.MX, new Outcome(Lookup.TYPE_NOT_FOUND, null),
                Type.A, new Outcome(Lookup.TRY_AGAIN, null)));
    assertEquals(MxResult.UNKNOWN, resolver.lookup("flaky.example"));
  }

  @Test
  void tryAgainOnMx_isUnknown() {
    var resolver = stubbed(Map.of(Type.MX, new Outcome(Lookup.TRY_AGAIN, null)));
    assertEquals(MxResult.UNKNOWN, resolver.lookup("timeout.example"));
  }

  @Test
  void unrecoverableOnMx_isUnknown() {
    var resolver = stubbed(Map.of(Type.MX, new Outcome(Lookup.UNRECOVERABLE, null)));
    assertEquals(MxResult.UNKNOWN, resolver.lookup("servfail.example"));
  }

  @Test
  void queryThrowing_isUnknown_neverPropagates() {
    var resolver =
        new DnsJavaMxResolver(
            (domain, type) -> {
              throw new IllegalArgumentException("unparseable name");
            });
    assertEquals(MxResult.UNKNOWN, resolver.lookup("badلname.example"));
  }
}
