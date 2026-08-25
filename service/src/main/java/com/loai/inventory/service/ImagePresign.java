package com.loai.inventory.service;

/**
 * A presigned image upload slot: where to PUT the bytes, the org-scoped object key to attach on the
 * following write, and how long the URL lives. Shared by the category and collection image flows
 * ({@code stories/category_image.md}, {@code stories/collection_image.md}); the banner flow
 * predates it and keeps its own identical record.
 */
public record ImagePresign(String uploadUrl, String objectKey, long expiresInSeconds) {}
