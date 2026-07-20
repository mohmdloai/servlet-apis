package com.loai.inventory.domain.model;

/**
 * One language's copy of a category's localized content (content-localization epic, slice L3). A
 * category has one row per {@code (category, language)}; the org's {@code default_locale} row is
 * required (service-enforced), other languages optional. Backed by {@code category_translation}
 * (V63), whose generated {@code name_search} column drives Arabic-aware search — this value type
 * carries only the authored {@code name}.
 */
public record CategoryTranslation(String language, String name) {}
