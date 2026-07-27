package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One event in a tenant's platform history — the common projection the two ledgers merge into (see
 * {@code stories/platform_org_timeline.md}).
 *
 * <p><strong>{@code source} is carried, not inferred.</strong> An {@code AUDIT} entry means <em>an
 * operator changed this tenant</em>; an {@code IMPERSONATION} entry means <em>an operator looked
 * inside it</em>. Those are different facts and the client must be able to tell them apart without
 * pattern-matching on verb strings — an access log flattened into an administrative one reads as
 * "nothing happened here" during exactly the period someone is asking about.
 *
 * <p><strong>{@code action} is open text.</strong> {@code platform_audit.action} is a {@code
 * VARCHAR(64)} with no enum behind it, deliberately (the same call V44 made for {@code
 * notification.type}), so a newly audited action needs no migration. It is carried through verbatim
 * and never validated against a known set. There is deliberately no Java enum here: it would be a
 * second definition of a vocabulary the database is already the authority on, and it would 500 on
 * the first action someone adds without updating it. Rendering the unknown is the client's problem.
 *
 * @param actorId the operator responsible — the audit row's {@code actor_id}, or the impersonation
 *     row's {@code impersonator_id}. Never invented; a row whose actor cannot be resolved carries a
 *     null {@link #actorEmail()} rather than a plausible-looking wrong name.
 * @param detailJson the action's payload as stored, already JSON; {@code null} when empty
 */
public record OrgTimelineEntry(
    UUID id,
    OffsetDateTime at,
    OrgTimelineSource source,
    String action,
    UUID actorId,
    String actorEmail,
    String actorDisplayName,
    String detailJson) {

  /** Which ledger an entry came from. */
  public enum OrgTimelineSource {
    AUDIT("audit"),
    IMPERSONATION("impersonation");

    private final String wire;

    OrgTimelineSource(String wire) {
      this.wire = wire;
    }

    /** The lowercase form that crosses the wire. */
    public String wire() {
      return wire;
    }
  }

  /** This entry with its actor resolved — the batch-load step, one query per page. */
  public OrgTimelineEntry withActor(String email, String displayName) {
    return new OrgTimelineEntry(id, at, source, action, actorId, email, displayName, detailJson);
  }
}
