package com.loai.inventory.service.email;

/**
 * Answers "can this domain receive mail?" via DNS. The three-valued result is the load-bearing
 * design: a resolver that is down or slow must answer {@link MxResult#UNKNOWN}, never {@link
 * MxResult#UNDELIVERABLE} — the {@link EmailGate} maps UNKNOWN to a pass (fail-open), so a DNS blip
 * can never block a real signup or sale. See {@code stories/87}.
 */
public interface MxResolver {

  enum MxResult {
    /** The domain has usable MX records (or an A/AAAA implicit-MX fallback, RFC 5321 §5.1). */
    DELIVERABLE,
    /** Proven undeliverable: NXDOMAIN, no MX and no address records, or a null MX (RFC 7505). */
    UNDELIVERABLE,
    /** The lookup could not complete (timeout/SERVFAIL/…). Callers must treat this as a pass. */
    UNKNOWN
  }

  /** Resolve deliverability for a bare, lowercase domain (no {@code @}, no trailing dot). */
  MxResult lookup(String domain);
}
