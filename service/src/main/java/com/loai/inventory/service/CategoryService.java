package com.loai.inventory.service;

import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.repository.CategoryRepository;
import com.loai.inventory.domain.repository.CategoryRepositoryFactory;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hierarchical, org-scoped product categories. Slug is unique per org. A self-FK cannot express
 * "parent must be in the same org" nor "no cycles", so those rules live here; deletion is blocked
 * while a category still has children.
 */
public class CategoryService {
  private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

  private final DSLContext rootDsl;
  private final CategoryRepositoryFactory repoFactory;

  public CategoryService(DSLContext rootDsl, CategoryRepositoryFactory repoFactory) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
  }

  public Category getById(UUID orgId, UUID id) {
    CategoryRepository repo = repoFactory.create(rootDsl);
    return repo.findById(orgId, id).orElseThrow(() -> new NotFoundException("Category", id));
  }

  public List<Category> getAll(UUID orgId, int page, int size) {
    int offset = Pagination.offset(page, size);
    CategoryRepository repo = repoFactory.create(rootDsl);
    return repo.findAll(orgId, offset, size);
  }

  public long count(UUID orgId) {
    return repoFactory.create(rootDsl).count(orgId);
  }

  public Category create(UUID orgId, String name, String slug, UUID parentCategoryId) {
    validate(name, slug);

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

          Category category = new Category();
          category.setOrgId(orgId);
          category.setParentCategoryId(parentCategoryId);
          category.setName(name);
          category.setSlug(slug);

          Category saved = repo.insert(category);
          log.info("Created category id={} orgId={} slug={}", saved.getId(), orgId, slug);
          return saved;
        });
  }

  public Category update(UUID orgId, UUID id, String name, String slug, UUID parentCategoryId) {
    validate(name, slug);

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

          existing.setName(name);
          existing.setSlug(slug);
          existing.setParentCategoryId(parentCategoryId);

          Category updated = repo.update(existing);
          log.info("Updated category id={} orgId={}", id, orgId);
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

  private void validate(String name, String slug) {
    if (name == null || name.isBlank()) {
      throw new ValidationException("name is required");
    }
    if (slug == null || slug.isBlank()) {
      throw new ValidationException("slug is required");
    }
  }
}
