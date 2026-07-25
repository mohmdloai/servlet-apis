package com.loai.inventory.domain.model;

import java.util.Map;
import java.util.UUID;

/**
 * One selectable value on a {@link VariantAttribute} — "m" on "size", "red" on "color" (slice VG1).
 *
 * <p>{@code slug} is the stable machine handle (what a variant's option map and the facets slice's
 * {@code attr_size=m} grammar both key on); {@code names} is {@code language → display name}, so
 * the storefront can render "M" or "متوسط" from the same row.
 */
public record VariantAttributeValue(
    UUID id, UUID attributeId, String slug, Map<String, String> names) {}
