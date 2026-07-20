package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.PageKind;
import com.loai.inventory.domain.model.StorefrontPage;
import com.loai.inventory.domain.model.StorefrontPageTranslation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link StorefrontPage} (customization epic slice C4). Reads are org-scoped;
 * {@link #upsert} is the idempotent create-or-replace behind {@code PUT …/pages/{kind}} (the UNIQUE
 * {@code (org_id, kind)} key). There is no cross-entity resolution here — a page is self-contained,
 * so the public read is the same {@link #findByKind} the admin uses.
 */
public interface StorefrontPageRepository {

  /** Every page the org has written, {@code kind ASC} (the admin editor's status list). */
  List<StorefrontPage> findAllByOrg(UUID orgId);

  /** One page by kind, or empty when the org has never written it (→ public 404). */
  Optional<StorefrontPage> findByKind(UUID orgId, PageKind kind);

  /**
   * Idempotent create-or-replace of a {@code (org_id, kind)} page: both bodies are written whole
   * each call (merge-null semantics buy nothing on two fields) and {@code updated_at} is refreshed.
   * Returns the stored row.
   */
  StorefrontPage upsert(StorefrontPage page);

  /**
   * Delete the org's page of this kind. Returns {@code true} when a row was removed, {@code false}
   * when the org had never written it (the caller maps that to a 404 — the kind is valid, the page
   * just doesn't exist).
   */
  boolean deleteByKind(UUID orgId, PageKind kind);

  // --- translations (content-localization slice L4) ---

  /** Replace the whole per-language translation set for a page (delete-then-insert). */
  void replaceTranslations(UUID pageId, List<StorefrontPageTranslation> translations);

  /** Every language's body for one page, ordered by language. */
  List<StorefrontPageTranslation> findTranslations(UUID pageId);
}
