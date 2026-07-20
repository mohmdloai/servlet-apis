package com.loai.inventory.service;

import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.CategoryTranslation;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.CategoryRepository;
import com.loai.inventory.domain.repository.CategoryRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hierarchical, org-scoped product categories. Slug is unique per org. A self-FK cannot express
 * "parent must be in the same org" nor "no cycles", so those rules live here; deletion is blocked
 * while a category still has children.
 *
 * <p>Slice L3 (content localization): {@code name} is authored per language in {@code
 * category_translation}; the org's {@code default_locale} row is required and dual-written to the
 * legacy {@code category.name} column (rollback safe until L6). Writes accept either an explicit
 * {@code translations} set or the legacy single {@code name} (synthesized into the default-locale
 * row); admin reads embed every language.
 */
public class CategoryService {
  private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

  /** Defensive cap on a stored translated category name. */
  static final int MAX_NAME_CHARS = 255;

  /**
   * The BCP-47 languages the storefront serves today; widening is a CHECK edit (V63) + this set.
   */
  private static final Set<String> SUPPORTED_LOCALES = Set.of("ar", "en");

  private final DSLContext rootDsl;
  private final CategoryRepositoryFactory repoFactory;
  private final OrgRepositoryFactory orgRepoFactory;

  public CategoryService(
      DSLContext rootDsl,
      CategoryRepositoryFactory repoFactory,
      OrgRepositoryFactory orgRepoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.orgRepoFactory = orgRepoFactory;
  }

  /**
   * A category plus every language's translation — the admin detail/list view (slice L3). The base
   * {@code category}'s {@code name} stays populated (dual-written to the org's default locale) for
   * the label and pre-L6 rollback.
   */
  public record CategoryView(Category category, List<CategoryTranslation> translations) {}

  /**
   * The localized content of a create/update write (slice L3). {@code translations} is the authored
   * per-language set; when it is null/empty the legacy single {@code name} is synthesized into one
   * row at the org's default locale (backward-compatible with pre-L3 callers).
   */
  public record TranslatedNameInput(List<CategoryTranslation> translations, String name) {}

  public CategoryView getById(UUID orgId, UUID id) {
    CategoryRepository repo = repoFactory.create(rootDsl);
    Category category =
        repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Category", id));
    return new CategoryView(category, repo.findTranslations(id));
  }

  public List<CategoryView> getAll(UUID orgId, int page, int size) {
    int offset = Pagination.offset(page, size);
    CategoryRepository repo = repoFactory.create(rootDsl);
    List<Category> categories = repo.findAll(orgId, offset, size);
    if (categories.isEmpty()) {
      return List.of();
    }
    List<UUID> ids = categories.stream().map(Category::getId).toList();
    Map<UUID, List<CategoryTranslation>> byCategory = repo.findTranslationsForCategories(ids);
    return categories.stream()
        .map(c -> new CategoryView(c, byCategory.getOrDefault(c.getId(), List.of())))
        .toList();
  }

  public long count(UUID orgId) {
    return repoFactory.create(rootDsl).count(orgId);
  }

  /** Single-language convenience: {@code name} becomes the org's default-locale translation row. */
  public Category create(UUID orgId, String name, String slug, UUID parentCategoryId) {
    return create(orgId, slug, parentCategoryId, new TranslatedNameInput(null, name));
  }

  public Category create(
      UUID orgId, String slug, UUID parentCategoryId, TranslatedNameInput content) {
    validateSlug(slug);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CategoryRepository repo = repoFactory.create(txDsl);

          if (repo.existsBySlug(orgId, slug)) {
            throw new ConflictException("Category slug already used in this org: " + slug);
          }
          if (parentCategoryId != null && !repo.existsById(orgId, parentCategoryId)) {
            throw new ValidationException("parent_category_id not found in this org");
          }

          List<CategoryTranslation> translations = normalizeTranslations(orgId, content, txDsl);
          CategoryTranslation defaultRow = translations.get(0); // resolver puts default first

          Category category = new Category();
          category.setOrgId(orgId);
          category.setParentCategoryId(parentCategoryId);
          // Dual-write the default-locale name onto the legacy column (rollback safe until L6).
          category.setName(defaultRow.name());
          category.setSlug(slug);

          Category saved = repo.insert(category);
          repo.replaceTranslations(saved.getId(), translations);
          log.info(
              "Created category id={} orgId={} slug={} langs={}",
              saved.getId(),
              orgId,
              slug,
              translations.size());
          return saved;
        });
  }

  /** Single-language convenience: {@code name} becomes the org's default-locale translation row. */
  public Category update(UUID orgId, UUID id, String name, String slug, UUID parentCategoryId) {
    return update(orgId, id, slug, parentCategoryId, new TranslatedNameInput(null, name));
  }

  public Category update(
      UUID orgId, UUID id, String slug, UUID parentCategoryId, TranslatedNameInput content) {
    validateSlug(slug);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CategoryRepository repo = repoFactory.create(txDsl);

          Category existing =
              repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Category", id));

          if (repo.existsBySlugAndIdNot(orgId, slug, id)) {
            throw new ConflictException("Category slug already used in this org: " + slug);
          }
          validateParent(repo, orgId, id, parentCategoryId);

          List<CategoryTranslation> translations = normalizeTranslations(orgId, content, txDsl);
          CategoryTranslation defaultRow = translations.get(0);

          existing.setName(defaultRow.name());
          existing.setSlug(slug);
          existing.setParentCategoryId(parentCategoryId);

          Category updated = repo.update(existing);
          repo.replaceTranslations(id, translations); // PUT replaces the whole set
          log.info("Updated category id={} orgId={} langs={}", id, orgId, translations.size());
          return updated;
        });
  }

  public void delete(UUID orgId, UUID id) {
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          CategoryRepository repo = repoFactory.create(txDsl);

          repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Category", id));
          if (repo.hasChildren(orgId, id)) {
            throw new ConflictException("Cannot delete a category that still has subcategories");
          }
          repo.deleteById(orgId, id);
          log.info("Deleted category id={} orgId={}", id, orgId);
        });
  }

  /** Parent must exist in the org, not be the category itself, and not create a cycle. */
  private void validateParent(
      CategoryRepository repo, UUID orgId, UUID categoryId, UUID parentCategoryId) {
    if (parentCategoryId == null) {
      return;
    }
    if (parentCategoryId.equals(categoryId)) {
      throw new ValidationException("A category cannot be its own parent");
    }
    if (!repo.existsById(orgId, parentCategoryId)) {
      throw new ValidationException("parent_category_id not found in this org");
    }
    // Walk up from the proposed parent; if we reach categoryId, this edge closes a cycle.
    UUID cursor = parentCategoryId;
    int guard = 0;
    while (cursor != null) {
      if (cursor.equals(categoryId)) {
        throw new ValidationException("parent_category_id would create a cycle");
      }
      if (++guard > 1000) {
        throw new ValidationException("category hierarchy too deep");
      }
      Category node = repo.findById(orgId, cursor).orElse(null);
      cursor = node == null ? null : node.getParentCategoryId();
    }
  }

  private void validateSlug(String slug) {
    if (slug == null || slug.isBlank()) {
      throw new ValidationException("slug is required");
    }
  }

  /**
   * Normalize + validate a write's translations into the persisted set (slice L3). Accepts either
   * an explicit {@code translations} list or the legacy single {@code name} (synthesized into one
   * default-locale row). Every value is NFC-normalized ({@link Text}) and length-capped; an entry
   * whose name normalizes to blank is treated as not-provided (dropped); the org's {@code
   * default_locale} row must survive that with a non-blank name, else 400. The returned list is
   * default-locale-first (callers dual-write the legacy {@code name} from {@code get(0)}).
   */
  private List<CategoryTranslation> normalizeTranslations(
      UUID orgId, TranslatedNameInput content, DSLContext txDsl) {
    String defaultLocale = defaultLocale(orgId, txDsl);
    List<CategoryTranslation> source;
    if (content != null && content.translations() != null && !content.translations().isEmpty()) {
      source = content.translations();
    } else {
      source =
          List.of(new CategoryTranslation(defaultLocale, content == null ? null : content.name()));
    }

    Map<String, CategoryTranslation> byLang = new LinkedHashMap<>();
    for (CategoryTranslation t : source) {
      if (t == null) {
        continue;
      }
      String lang = t.language() == null ? null : t.language().trim().toLowerCase(Locale.ROOT);
      if (lang == null || lang.isBlank()) {
        throw new ValidationException("translation language is required");
      }
      if (!SUPPORTED_LOCALES.contains(lang)) {
        throw new ValidationException("unsupported language: " + lang);
      }
      String name = Text.normalizeText(t.name());
      if (name == null) {
        continue; // blank tab — not provided; the default-locale requirement is checked below
      }
      if (name.length() > MAX_NAME_CHARS) {
        throw new ValidationException("name exceeds the " + MAX_NAME_CHARS + "-character limit");
      }
      if (byLang.containsKey(lang)) {
        throw new ValidationException("duplicate translation for language: " + lang);
      }
      byLang.put(lang, new CategoryTranslation(lang, name));
    }

    CategoryTranslation defaultRow = byLang.get(defaultLocale);
    if (defaultRow == null) {
      throw new ValidationException(
          "a name in the org's default locale (" + defaultLocale + ") is required");
    }
    List<CategoryTranslation> ordered = new ArrayList<>(byLang.size());
    ordered.add(defaultRow);
    for (Map.Entry<String, CategoryTranslation> e : byLang.entrySet()) {
      if (!e.getKey().equals(defaultLocale)) {
        ordered.add(e.getValue());
      }
    }
    return ordered;
  }

  private String defaultLocale(UUID orgId, DSLContext txDsl) {
    OrgRepository orgRepo = orgRepoFactory.create(txDsl);
    Org org = orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Org", orgId));
    String loc = org.getDefaultLocale();
    return loc == null || loc.isBlank() ? "ar" : loc.trim().toLowerCase(Locale.ROOT);
  }
}
