package com.loai.inventory.domain.model;

import java.util.UUID;

/**
 * The principal that performed an action, as recorded in audit/log rows. {@code actorId}/{@code
 * actorType} identify the principal (during an impersonation overlay, this is the <em>target</em>).
 * {@code impersonatorId}, when non-null, records the real driver behind an overlay — so a ledger
 * row reads "the target did X" while still answering "…but this admin held the keyboard".
 */
public record ActorContext(String actorId, ActorType actorType, UUID impersonatorId) {

  public static ActorContext user(String userId) {
    return new ActorContext(userId, ActorType.USER, null);
  }

  public static ActorContext service(String serviceName) {
    return new ActorContext(serviceName, ActorType.SERVICE, null);
  }

  public static ActorContext system(String jobName) {
    return new ActorContext(jobName, ActorType.SYSTEM, null);
  }

  public static ActorContext migration(String migrationName) {
    return new ActorContext(migrationName, ActorType.MIGRATION, null);
  }
}
