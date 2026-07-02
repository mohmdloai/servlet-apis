package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;

/**
 * A row of a user's in-app feed: the {@link Notification} joined to its in-app delivery's read /
 * dismissed state and link target. Read-model projection — not persisted directly.
 */
public record InAppFeedItem(
    Notification notification,
    OffsetDateTime readAt,
    OffsetDateTime dismissedAt,
    String linkTarget) {}
