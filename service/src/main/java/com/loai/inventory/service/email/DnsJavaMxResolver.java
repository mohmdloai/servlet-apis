package com.loai.inventory.service.email;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.ExtendedResolver;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.MXRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * {@link MxResolver} backed by dnsjava against the system's configured nameservers. Result mapping
 * (the RFC-shaped part — the transport is dnsjava's):
 *
 * <ul>
 *   <li>MX records present → {@code DELIVERABLE}; unless the only record is the RFC 7505 null MX (a
 *       single {@code .} target — the domain's explicit "we never accept mail") → {@code
 *       UNDELIVERABLE}.
 *   <li>Domain exists but has no MX ({@code TYPE_NOT_FOUND}) → RFC 5321 implicit-MX fallback: an A
 *       or AAAA record makes it {@code DELIVERABLE}; none → {@code UNDELIVERABLE}.
 *   <li>NXDOMAIN ({@code HOST_NOT_FOUND}) → {@code UNDELIVERABLE}.
 *   <li>Anything else — timeout, SERVFAIL, unparseable name, resolver exception → {@code UNKNOWN}
 *       (the gate fails open on UNKNOWN; only <em>proven</em> undeliverability rejects).
 * </ul>
 */
public class DnsJavaMxResolver implements MxResolver {

  private static final Logger log = LoggerFactory.getLogger(DnsJavaMxResolver.class);

  /** The dnsjava seam — one DNS question, dnsjava's result code + records. Stubbed in tests. */
  interface DnsQuery {
    Outcome query(String domain, int type);
  }

  /** {@code result} is a {@link Lookup} result constant; {@code records} may be null. */
  record Outcome(int result, List<Record> records) {}

  private final DnsQuery dnsQuery;

  /** Production wiring: system nameservers, one try, {@code timeoutMs} per query. */
  public DnsJavaMxResolver(int timeoutMs) {
    ExtendedResolver resolver = new ExtendedResolver();
    resolver.setTimeout(Duration.ofMillis(timeoutMs));
    resolver.setRetries(0);
    this.dnsQuery =
        (domain, type) -> {
          Lookup lookup;
          try {
            lookup = new Lookup(domain, type);
          } catch (TextParseException e) {
            // Not a resolvable DNS name (e.g. a raw IDN label) — surfaces as UNKNOWN upstream.
            throw new IllegalArgumentException(e);
          }
          lookup.setResolver(resolver);
          Record[] records = lookup.run();
          return new Outcome(lookup.getResult(), records == null ? null : List.of(records));
        };
  }

  /** Test seam. */
  DnsJavaMxResolver(DnsQuery dnsQuery) {
    this.dnsQuery = dnsQuery;
  }

  @Override
  public MxResult lookup(String domain) {
    try {
      Outcome mx = dnsQuery.query(domain, Type.MX);
      return switch (mx.result()) {
        case Lookup.SUCCESSFUL ->
            nullMxOnly(mx.records()) ? MxResult.UNDELIVERABLE : MxResult.DELIVERABLE;
        case Lookup.HOST_NOT_FOUND -> MxResult.UNDELIVERABLE; // NXDOMAIN
        case Lookup.TYPE_NOT_FOUND -> implicitMxFallback(domain); // domain exists, no MX
        default -> MxResult.UNKNOWN; // TRY_AGAIN / UNRECOVERABLE — fail open
      };
    } catch (RuntimeException e) {
      // Includes unparseable names (e.g. IDN labels Text.normalizeEmail leaves as-is) — never
      // block on what we can't ask about.
      log.debug("MX lookup failed for a domain — treating as UNKNOWN", e);
      return MxResult.UNKNOWN;
    }
  }

  /** RFC 5321 implicit MX: with no MX records, an A/AAAA host still receives mail. */
  private MxResult implicitMxFallback(String domain) {
    boolean sawUnknown = false;
    for (int type : new int[] {Type.A, Type.AAAA}) {
      Outcome address = dnsQuery.query(domain, type);
      if (address.result() == Lookup.SUCCESSFUL) {
        return MxResult.DELIVERABLE;
      }
      if (address.result() != Lookup.HOST_NOT_FOUND && address.result() != Lookup.TYPE_NOT_FOUND) {
        sawUnknown = true; // a flaky answer forbids concluding "no host at all"
      }
    }
    return sawUnknown ? MxResult.UNKNOWN : MxResult.UNDELIVERABLE;
  }

  /** RFC 7505: exactly one MX whose target is the root name ({@code .}) = "no mail, ever". */
  private static boolean nullMxOnly(List<Record> records) {
    if (records == null || records.size() != 1) {
      return false;
    }
    return records.get(0) instanceof MXRecord mx && Name.root.equals(mx.getTarget());
  }
}
