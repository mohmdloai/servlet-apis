package com.loai.inventory.domain.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An <b>org-level</b> option axis — "Size", "Color" — with its display names per language and every
 * value defined on it (slice VG1).
 *
 * <p>Org-scoped rather than listing-scoped on purpose: "Size" and its "M" are shared across every
 * listing that sells in sizes, which is exactly what lets the facets slice aggregate counts with
 * one grouped query instead of reconciling per-listing option vocabularies.
 *
 * <p>{@code names} is {@code language → name} (the {@code attribute_translation} rows, the V63
 * house pattern); a language with no authored name is simply absent and the caller falls back to
 * the slug.
 */
public record VariantAttribute(
    UUID id,
    UUID orgId,
    String slug,
    Map<String, String> names,
    List<VariantAttributeValue> values) {}
