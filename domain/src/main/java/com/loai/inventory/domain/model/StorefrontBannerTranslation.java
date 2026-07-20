package com.loai.inventory.domain.model;

/**
 * One language's copy of a banner's localized content (content-localization epic, slice L4). A
 * banner has one row per {@code (banner, language)}; the org's {@code default_locale} row is
 * required (service-enforced, via the default-locale headline rule), other languages optional.
 * Either {@code headline} or {@code subheading} must be present (DB CHECK). Backed by {@code
 * storefront_banner_translation} (V63).
 */
public record StorefrontBannerTranslation(String language, String headline, String subheading) {}
