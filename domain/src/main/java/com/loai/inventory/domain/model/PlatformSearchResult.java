package com.loai.inventory.domain.model;

import java.util.UUID;

/**
 * One hit from {@code GET /api/admin/search?q=} — <strong>and the field whitelist itself</strong>.
 *
 * <p>Rule 3 of {@link com.loai.inventory.domain.repository.PlatformSearchRepository} says a result
 * carries identity, one label, one sublabel and its tenant, and nothing else. Unlike {@code
 * PlatformQueueRow}, which needs a sealed hierarchy because each queue triages on different facts,
 * every search result answers the same question — "what is this identifier, and whose is it?" — so
 * one flat record with four declared fields <em>is</em> the whitelist. Widening it widens every
 * type at once, which is exactly the edit that should be hard to make by accident.
 *
 * <p>What each type puts in {@code label} / {@code sublabel}:
 *
 * <table>
 *   <caption>The per-type field whitelist</caption>
 *   <tr><th>type</th><th>label</th><th>sublabel</th></tr>
 *   <tr><td>{@code org}</td><td>{@code name}</td><td>{@code slug}</td></tr>
 *   <tr><td>{@code app_user}</td><td>{@code email}</td><td>{@code display_name} (nullable)</td></tr>
 *   <tr><td>{@code customer}</td><td>{@code email}</td><td><em>nothing</em></td></tr>
 *   <tr><td>{@code sales_order}</td><td>{@code order_number}</td><td>{@code status}</td></tr>
 *   <tr><td>{@code payment_transaction}</td><td>{@code provider_ref}</td><td>{@code provider}</td></tr>
 * </table>
 *
 * <p><strong>A customer result carries no name, phone or address</strong> — not even the name,
 * which is not searchable here either. The email was the input and the tenant is the answer; a name
 * would be pure spill. {@code PlatformSearchIT.results_carryNoCustomerPii} pins all three.
 *
 * <p><strong>No URLs.</strong> Route construction is the frontend's job ({@code
 * searchResultPath(result)}, story 77) — a server emitting {@code /admin/…} paths couples this API
 * to a route table it cannot see, and story 77 found that one of the five types has no route at
 * all.
 *
 * <p>This widens <em>default exposure</em>, not capability: a platform ADMIN already bypasses org
 * checks and SUPPORT already has read-only impersonation. What changes is that it is now one
 * keystroke — which is why the field set is enumerated rather than joined to whatever was
 * convenient.
 *
 * @param org the owning tenant, or {@code null} for {@link PlatformSearchType#ORG} and {@link
 *     PlatformSearchType#APP_USER}, which have none.
 */
public record PlatformSearchResult(
    PlatformSearchType type, UUID id, String label, String sublabel, PlatformSearchOrg org) {}
