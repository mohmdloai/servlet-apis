package com.loai.inventory.domain.model;

public record ActorContext(String actorId, ActorType actorType) {

  public static ActorContext user(String userId) {
    return new ActorContext(userId, ActorType.USER);
  }

  public static ActorContext service(String serviceName) {
    return new ActorContext(serviceName, ActorType.SERVICE);
  }

  public static ActorContext system(String jobName) {
    return new ActorContext(jobName, ActorType.SYSTEM);
  }

  public static ActorContext migration(String migrationName) {
    return new ActorContext(migrationName, ActorType.MIGRATION);
  }
}
