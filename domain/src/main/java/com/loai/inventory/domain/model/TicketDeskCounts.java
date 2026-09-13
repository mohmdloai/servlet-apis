package com.loai.inventory.domain.model;

/**
 * The desk's four status counts plus how many of the open ones say "I can't sell". Read through one
 * predicate set ({@code TicketDeskPredicates}) shared with the inbox rows, so the overview tile,
 * the tab badge and the list are the same number by construction.
 */
public record TicketDeskCounts(
    long open, long awaitingMerchant, long resolved, long closed, long blockingOpen) {}
