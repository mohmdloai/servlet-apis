package com.loai.inventory.service.push;

import java.util.UUID;

/**
 * One device to send to — the frozen copy of a subscription a push delivery carries (V96 {@code
 * notification_delivery_push}). {@code subscriptionId} is what a 404/410 prunes.
 */
public record PushTarget(UUID subscriptionId, String endpoint, String p256dh, String auth) {}
