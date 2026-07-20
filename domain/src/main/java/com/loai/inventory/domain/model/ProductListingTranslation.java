package com.loai.inventory.domain.model;

/**
 * One language's copy of a listing's localized content (content-localization epic, slice L2). A
 * listing has one row per {@code (listing, language)}; the org's {@code default_locale} row is
 * required (service-enforced), other languages optional. {@code title} is always present; {@code
 * marketingCopy} may be null. Backed by {@code product_listing_translation} (V63), whose generated
 * {@code title_search} column drives Arabic-aware search — this value type carries only the
 * authored fields.
 */
public record ProductListingTranslation(String language, String title, String marketingCopy) {}
