package com.loai.inventory.domain.model;

import java.util.UUID;

/**
 * "About: SO-2026-00042" — the record a ticket names, frozen at open. The label is what the
 * merchant saw; the id lets the desk find it through console search. It is deliberately not a
 * foreign key: the ticket must outlive a cancelled order.
 */
public record TicketRef(TicketRefType type, UUID id, String label) {}
