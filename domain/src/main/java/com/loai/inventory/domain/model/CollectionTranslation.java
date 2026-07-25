package com.loai.inventory.domain.model;

/**
 * One language's copy of a collection's name (roadmap item 8, the V63 translation-table pattern). A
 * collection has one row per {@code (collection, language)}; the org's {@code default_locale} row
 * is required (service-enforced), other languages optional. Backed by {@code
 * collection_translation} (V71) — a nav label, so unlike listing titles it carries no folded search
 * column.
 */
public record CollectionTranslation(String language, String name) {}
