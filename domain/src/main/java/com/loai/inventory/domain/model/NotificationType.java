package com.loai.inventory.domain.model;

/**
 * The kind of domain event a notification represents. Stored as its {@code name()} in the open
 * {@code notification.type} TEXT column, so adding a type needs no migration. Templates key on this
 * enum (see the service layer). More types are added as their producer hooks are wired (see the
 * routing table in {@code docs/notifications-plan.md} §8).
 */
public enum NotificationType {
  ORDER_PLACED
}
