package com.loai.inventory.domain.model;

/**
 * One language's copy of a page's body (content-localization epic, slice L4). A page has one row
 * per {@code (page, language)}; the org's {@code default_locale} row is required
 * (service-enforced), other languages optional. {@code body} is always present. Backed by {@code
 * storefront_page_translation} (V63).
 */
public record StorefrontPageTranslation(String language, String body) {}
