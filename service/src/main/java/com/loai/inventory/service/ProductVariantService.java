package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ProductVariant;
import com.loai.inventory.domain.model.VariantAttribute;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepository;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.ProductRepositoryFactory;
import com.loai.inventory.domain.repository.ProductVariantRepository;
import com.loai.inventory.domain.repository.ProductVariantRepository.ValueRef;
import com.loai.inventory.domain.repository.ProductVariantRepositoryFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin variant surface (slice VG1, {@code stories/catalog_variants_model.md}) — one listing's
 * option axes and variant rows, read whole and written whole.
 *
 * <p>The write is an atomic <b>set-replace</b>, the {@code PUT /{id}/categories} precedent: the
 * payload IS the intended state. A new variant mints a <b>child product</b> (its own SKU, optional
 * barcode, own inventory row); an existing one (matched on {@code variant_key}) is updated; one
 * that is absent is <b>deactivated, never deleted</b> — its child may already back order lines and
 * inventory, and that history must stand.
 *
 * <p>Stock is deliberately not managed here. Because a variant is a child product, the existing
 * inventory screens (initialise / restock / adjust / ledger, barcode resolve) already handle it
 * with zero new inventory code — that is the whole point of the child-product architecture (§1).
 */
public class ProductVariantService {

  private static final Logger log = LoggerFactory.getLogger(ProductVariantService.class);

  /**
   * Caps (architecture §2), server-enforced as cause-naming 400s. They exist to keep the variant
   * grid a grid: three axes is already a 3-dimensional matrix, and 100 rows is more than any human
   * curates by hand.
   */
  static final int MAX_ATTRIBUTES = 3;

  static final int MAX_VARIANTS = 100;
  static final int MAX_VALUES_PER_ATTRIBUTE = 40;

  /** Slug discipline for axes, values, and generated variant keys (the {@code OrgService} form). */
  private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$");

  private static final int MAX_SLUG_CHARS = 80;

  /** The languages the storefront serves (mirrors {@code ProductListingService}). */
  private static final List<String> LANGUAGES = List.of("ar", "en");

  private final DSLContext rootDsl;
  private final ProductVariantRepositoryFactory variantRepoFactory;
  private final ProductListingRepositoryFactory listingRepoFactory;
  private final ProductRepositoryFactory productRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;

  public ProductVariantService(
      DSLContext rootDsl,
      ProductVariantRepositoryFactory variantRepoFactory,
      ProductListingRepositoryFactory listingRepoFactory,
      ProductRepositoryFactory productRepoFactory,
      OrgRepositoryFactory orgRepoFactory) {
    this.rootDsl = rootDsl;
    this.variantRepoFactory = variantRepoFactory;
    this.listingRepoFactory = listingRepoFactory;
    this.productRepoFactory = productRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
  }

  // views (admin plane — internal ids are fine here)

  /**
   * One variant row as the admin sees it: the public {@code key}, the option map ({@code attribute
   * slug → value slug}), the variant's own price, and its child product's identity (id, SKU,
   * barcode) so the rail can deep-link to that product's inventory screen.
   */
  public record VariantRow(
      UUID id,
      String key,
      Map<String, String> options,
      BigDecimal salesPrice,
      int sortOrder,
      boolean active,
      UUID productId,
      String sku,
      String barcode) {}

  /** A listing's whole variant set: the axes in play plus every variant row (inactive included). */
  public record VariantSetView(List<VariantAttribute> attributes, List<VariantRow> variants) {}

  // write inputs

  /** One declared value on an axis: the stable slug plus its display name per language. */
  public record ValueInput(String slug, Map<String, String> names) {}

  /** One declared axis: slug, display names per language, and the values selectable on it. */
  public record AttributeInput(String slug, Map<String, String> names, List<ValueInput> values) {}

  /**
   * One variant row of the intended set. {@code key} is optional — absent, it is generated from the
   * option slugs in attribute-slug order ({@code color=red, size=m → "red-m"}). {@code sku} is
   * required for a new variant (it is the org-unique product identity); {@code barcode} is optional
   * and, when given, buys per-variant scan-to-stock/scan-to-sell for free off the V16 partial
   * unique.
   */
  public record VariantInput(
      String key,
      Map<String, String> options,
      BigDecimal salesPrice,
      String sku,
      String barcode,
      Boolean active) {}

  // read

  public VariantSetView getVariants(UUID orgId, UUID listingId) {
    ProductListingRepository listingRepo = listingRepoFactory.create(rootDsl);
    listingRepo
        .findById(orgId, listingId)
        .orElseThrow(() -> new NotFoundException("ProductListing", listingId));
    ProductVariantRepository variantRepo = variantRepoFactory.create(rootDsl);
    ProductRepository productRepo = productRepoFactory.create(rootDsl);
    return readSet(orgId, listingId, variantRepo, productRepo);
  }

  private VariantSetView readSet(
      UUID orgId,
      UUID listingId,
      ProductVariantRepository variantRepo,
      ProductRepository productRepo) {
    List<ProductVariant> variants = variantRepo.findByListing(orgId, listingId);
    if (variants.isEmpty()) {
      return new VariantSetView(List.of(), List.of());
    }

    List<UUID> variantIds = variants.stream().map(ProductVariant::id).toList();
    Map<UUID, List<UUID>> valueIdsByVariant = variantRepo.findValueIdsByVariant(variantIds);
    Set<UUID> allValueIds = new LinkedHashSet<>();
    valueIdsByVariant.values().forEach(allValueIds::addAll);

    Map<UUID, ValueRef> refsById = new HashMap<>();
    Set<UUID> attributeIds = new LinkedHashSet<>();
    for (ValueRef ref : variantRepo.findValueRefs(allValueIds)) {
      refsById.put(ref.valueId(), ref);
      attributeIds.add(ref.attributeId());
    }
    List<VariantAttribute> attributes = variantRepo.findAttributes(orgId, attributeIds);

    // The child products carry the SKU/barcode the rail edits and the id it deep-links stock by.
    List<UUID> childIds = variants.stream().map(ProductVariant::productId).toList();
    Map<UUID, Product> childById = new HashMap<>();
    for (UUID childId : childIds) {
      productRepo.findById(orgId, childId).ifPresent(p -> childById.put(p.getId(), p));
    }

    List<VariantRow> rows = new ArrayList<>(variants.size());
    for (ProductVariant v : variants) {
      Product child = childById.get(v.productId());
      rows.add(
          new VariantRow(
              v.id(),
              v.variantKey(),
              optionsOf(valueIdsByVariant.getOrDefault(v.id(), List.of()), refsById),
              v.salesPrice(),
              v.sortOrder(),
              v.active(),
              v.productId(),
              child == null ? null : child.getSku(),
              child == null ? null : child.getBarcode()));
    }
    return new VariantSetView(attributes, rows);
  }

  /** {@code attribute slug → value slug}, sorted by attribute slug so the map is deterministic. */
  private static Map<String, String> optionsOf(List<UUID> valueIds, Map<UUID, ValueRef> refsById) {
    Map<String, String> options = new TreeMap<>();
    for (UUID valueId : valueIds) {
      ValueRef ref = refsById.get(valueId);
      if (ref != null) {
        options.put(ref.attributeSlug(), ref.valueSlug());
      }
    }
    return options;
  }

  // write (atomic set-replace)

  /**
   * Replace a listing's whole variant set in one transaction. Validation runs against the
   * normalized payload <b>before</b> a row is touched, so a cap breach or a duplicate combination
   * applies nothing; SKU/barcode conflicts surface as 409s from the same transaction and roll it
   * back.
   */
  public VariantSetView replaceVariants(
      UUID orgId, UUID listingId, List<AttributeInput> attributes, List<VariantInput> variants) {
    List<AttributeInput> attrs = attributes == null ? List.of() : attributes;
    List<VariantInput> rows = variants == null ? List.of() : variants;

    if (attrs.size() > MAX_ATTRIBUTES) {
      throw new ValidationException("at most " + MAX_ATTRIBUTES + " attributes per listing");
    }
    if (rows.size() > MAX_VARIANTS) {
      throw new ValidationException("at most " + MAX_VARIANTS + " variants per listing");
    }
    if (attrs.isEmpty() && !rows.isEmpty()) {
      throw new ValidationException("at least one attribute is required to define variants");
    }

    // 1. Normalize + validate the declared axes.
    Map<String, AttributeInput> byAttrSlug = new LinkedHashMap<>();
    for (AttributeInput a : attrs) {
      String slug = requireSlug(a == null ? null : a.slug(), "attribute slug");
      if (byAttrSlug.containsKey(slug)) {
        throw new ValidationException("duplicate attribute: " + slug);
      }
      List<ValueInput> values = a.values() == null ? List.of() : a.values();
      if (values.isEmpty()) {
        throw new ValidationException("attribute '" + slug + "' must declare at least one value");
      }
      if (values.size() > MAX_VALUES_PER_ATTRIBUTE) {
        throw new ValidationException(
            "attribute '" + slug + "' exceeds the " + MAX_VALUES_PER_ATTRIBUTE + "-value limit");
      }
      Map<String, ValueInput> byValueSlug = new LinkedHashMap<>();
      List<ValueInput> normalizedValues = new ArrayList<>(values.size());
      for (ValueInput v : values) {
        String valueSlug = requireSlug(v == null ? null : v.slug(), "value slug");
        if (byValueSlug.containsKey(valueSlug)) {
          throw new ValidationException(
              "duplicate value '" + valueSlug + "' on attribute '" + slug + "'");
        }
        ValueInput normalized = new ValueInput(valueSlug, normalizeNames(v.names()));
        byValueSlug.put(valueSlug, normalized);
        normalizedValues.add(normalized);
      }
      byAttrSlug.put(slug, new AttributeInput(slug, normalizeNames(a.names()), normalizedValues));
    }

    // 2. Normalize + validate the variant rows against those axes.
    record NormalizedVariant(
        String key,
        Map<String, String> options,
        BigDecimal salesPrice,
        String sku,
        String barcode,
        boolean active,
        int sortOrder) {}

    List<NormalizedVariant> normalizedRows = new ArrayList<>(rows.size());
    Set<String> seenKeys = new HashSet<>();
    Set<String> seenCombinations = new HashSet<>();
    for (int i = 0; i < rows.size(); i++) {
      VariantInput v = rows.get(i);
      if (v == null) {
        throw new ValidationException("variant at index " + i + " is null");
      }
      Map<String, String> options = v.options() == null ? Map.of() : v.options();
      // Exactly one value per declared attribute — no partial combinations, no stray axes.
      Map<String, String> normalizedOptions = new TreeMap<>();
      for (Map.Entry<String, String> e : options.entrySet()) {
        String attrSlug = normalizeSlug(e.getKey());
        if (!byAttrSlug.containsKey(attrSlug)) {
          throw new ValidationException(
              "variant at index " + i + " references undeclared attribute '" + e.getKey() + "'");
        }
        String valueSlug = normalizeSlug(e.getValue());
        boolean declared =
            byAttrSlug.get(attrSlug).values().stream()
                .anyMatch(candidate -> candidate.slug().equals(valueSlug));
        if (!declared) {
          throw new ValidationException(
              "variant at index "
                  + i
                  + " uses value '"
                  + e.getValue()
                  + "' undeclared on attribute '"
                  + attrSlug
                  + "'");
        }
        normalizedOptions.put(attrSlug, valueSlug);
      }
      if (normalizedOptions.size() != byAttrSlug.size()) {
        throw new ValidationException(
            "variant at index "
                + i
                + " must carry exactly one value for each of the "
                + byAttrSlug.size()
                + " declared attributes");
      }
      if (!seenCombinations.add(String.join(" ", normalizedOptions.values()))) {
        throw new ValidationException(
            "duplicate option combination at index " + i + ": " + normalizedOptions);
      }

      BigDecimal price = v.salesPrice();
      if (price == null) {
        throw new ValidationException("sales_price is required for variant at index " + i);
      }
      if (price.signum() < 0) {
        throw new ValidationException("sales_price must be >= 0 (variant at index " + i + ")");
      }

      String key =
          v.key() == null || v.key().isBlank()
              ? generateKey(normalizedOptions)
              : requireSlug(v.key(), "variant key");
      if (!seenKeys.add(key)) {
        throw new ValidationException("duplicate variant key: " + key);
      }

      String sku = Text.normalizeText(v.sku());
      String barcode = Text.normalizeNumeric(v.barcode());
      normalizedRows.add(
          new NormalizedVariant(
              key, normalizedOptions, price, sku, barcode, v.active() == null || v.active(), i));
    }

    // 3. Apply — one transaction, so any failure below applies nothing at all.
    rootDsl.transaction(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListingRepository listingRepo = listingRepoFactory.create(txDsl);
          ProductVariantRepository variantRepo = variantRepoFactory.create(txDsl);
          ProductRepository productRepo = productRepoFactory.create(txDsl);

          ProductListing listing =
              listingRepo
                  .findById(orgId, listingId)
                  .orElseThrow(() -> new NotFoundException("ProductListing", listingId));
          String defaultLocale = defaultLocale(orgId, txDsl);

          // 3a. Upsert the org-level axes and values, collecting slug → id for the option maps.
          Map<String, UUID> attributeIdBySlug = new LinkedHashMap<>();
          Map<String, Map<String, UUID>> valueIdBySlug = new LinkedHashMap<>();
          for (AttributeInput a : byAttrSlug.values()) {
            UUID attributeId = variantRepo.upsertAttribute(orgId, a.slug());
            attributeIdBySlug.put(a.slug(), attributeId);
            for (String language : LANGUAGES) {
              variantRepo.upsertAttributeName(attributeId, language, a.names().get(language));
            }
            Map<String, UUID> valueIds = new LinkedHashMap<>();
            for (ValueInput value : a.values()) {
              UUID valueId = variantRepo.upsertAttributeValue(attributeId, value.slug());
              valueIds.put(value.slug(), valueId);
              for (String language : LANGUAGES) {
                variantRepo.upsertAttributeValueName(
                    valueId, language, value.names().get(language));
              }
            }
            valueIdBySlug.put(a.slug(), valueIds);
          }

          Map<String, ProductVariant> existingByKey = new HashMap<>();
          for (ProductVariant existing : variantRepo.findByListing(orgId, listingId)) {
            existingByKey.put(existing.variantKey(), existing);
          }

          // 3b. Upsert each row of the intended set.
          for (NormalizedVariant row : normalizedRows) {
            List<UUID> valueIds = new ArrayList<>(row.options().size());
            row.options()
                .forEach(
                    (attrSlug, valueSlug) ->
                        valueIds.add(valueIdBySlug.get(attrSlug).get(valueSlug)));
            String label = composeLabel(row.options(), byAttrSlug, defaultLocale);
            String childName = listing.getTitle() + " — " + label;

            ProductVariant existing = existingByKey.get(row.key());
            UUID variantId;
            if (existing == null) {
              if (row.sku() == null) {
                throw new ValidationException(
                    "sku is required for new variant '" + row.key() + "'");
              }
              UUID childProductId =
                  mintChildProduct(
                      productRepo, orgId, childName, row.sku(), row.barcode(), row.salesPrice());
              variantId =
                  variantRepo
                      .insertVariant(
                          new ProductVariant(
                              null,
                              orgId,
                              listingId,
                              childProductId,
                              row.key(),
                              row.salesPrice(),
                              row.sortOrder(),
                              row.active()))
                      .id();
            } else {
              variantId = existing.id();
              variantRepo.updateVariant(variantId, row.salesPrice(), row.sortOrder(), row.active());
              updateChildProduct(
                  productRepo, orgId, existing.productId(), childName, row.sku(), row.barcode());
            }
            variantRepo.replaceVariantValues(variantId, valueIds);
          }

          // 3c. Anything absent from the payload is deactivated — never deleted (§2 lifecycle).
          variantRepo.deactivateVariantsNotIn(
              orgId, listingId, normalizedRows.stream().map(NormalizedVariant::key).toList());

          log.info(
              "Replaced variant set on listing id={} orgId={} attributes={} variants={}",
              listingId,
              orgId,
              byAttrSlug.size(),
              normalizedRows.size());
        });

    return getVariants(orgId, listingId);
  }

  /**
   * Mint the child {@code product} behind a new variant: an ordinary org product, which is what
   * makes its stock, restock, ledger, and barcode scan work with no new inventory code. SKU is
   * org-unique (409) and the optional barcode rides the V16 partial unique (409).
   */
  private UUID mintChildProduct(
      ProductRepository productRepo,
      UUID orgId,
      String name,
      String sku,
      String barcode,
      BigDecimal salesPrice) {
    if (productRepo.existsBySku(orgId, sku)) {
      throw new ConflictException("SKU already exists: " + sku);
    }
    if (barcode != null && productRepo.existsByBarcode(orgId, barcode)) {
      throw new ConflictException("Barcode already exists: " + barcode);
    }
    Product child = new Product();
    child.setOrgId(orgId);
    child.setName(name);
    child.setBasePrice(salesPrice);
    child.setSku(sku);
    child.setBarcode(barcode);
    return productRepo.insert(child).getId();
  }

  /**
   * Keep an existing variant's child product in step with the rail: the composed name follows the
   * listing title and option label, and an edited SKU/barcode is applied (with the same org-unique
   * 409s). A payload that omits the SKU leaves the stored one alone — omission is "unchanged",
   * never "blank it".
   */
  private void updateChildProduct(
      ProductRepository productRepo,
      UUID orgId,
      UUID childProductId,
      String name,
      String sku,
      String barcode) {
    Product child =
        productRepo
            .findById(orgId, childProductId)
            .orElseThrow(() -> new NotFoundException("Product", childProductId));
    String targetSku = sku == null ? child.getSku() : sku;
    if (!targetSku.equals(child.getSku())
        && productRepo.existsBySkuAndIdNot(orgId, targetSku, childProductId)) {
      throw new ConflictException("SKU already used by another product: " + targetSku);
    }
    if (barcode != null
        && !barcode.equals(child.getBarcode())
        && productRepo.existsByBarcodeAndIdNot(orgId, barcode, childProductId)) {
      throw new ConflictException("Barcode already used by another product: " + barcode);
    }
    child.setName(name);
    child.setSku(targetSku);
    if (barcode != null) {
      child.setBarcode(barcode);
    }
    productRepo.update(child);
  }

  /**
   * The human label of an option combination — "Red / M" — resolved in the org's default locale,
   * falling back to the other language and then to the raw slug. This is what composes the child
   * product's name and (in VG2) the order line's frozen description.
   */
  private static String composeLabel(
      Map<String, String> options, Map<String, AttributeInput> byAttrSlug, String defaultLocale) {
    List<String> parts = new ArrayList<>(options.size());
    options.forEach(
        (attrSlug, valueSlug) -> {
          AttributeInput attribute = byAttrSlug.get(attrSlug);
          String label = valueSlug;
          if (attribute != null) {
            for (ValueInput value : attribute.values()) {
              if (value.slug().equals(valueSlug)) {
                String named = value.names().get(defaultLocale);
                if (named == null) {
                  named =
                      LANGUAGES.stream()
                          .map(value.names()::get)
                          .filter(java.util.Objects::nonNull)
                          .findFirst()
                          .orElse(null);
                }
                if (named != null) {
                  label = named;
                }
                break;
              }
            }
          }
          parts.add(label);
        });
    return String.join(" / ", parts);
  }

  /**
   * {@code color=red, size=m → "red-m"} — value slugs in attribute-slug order (options is sorted).
   */
  private static String generateKey(Map<String, String> options) {
    String key = String.join("-", options.values());
    if (key.length() > MAX_SLUG_CHARS) {
      throw new ValidationException(
          "generated variant key exceeds " + MAX_SLUG_CHARS + " characters: " + key);
    }
    return key;
  }

  private static Map<String, String> normalizeNames(Map<String, String> names) {
    Map<String, String> out = new LinkedHashMap<>();
    if (names == null) {
      return out;
    }
    names.forEach(
        (language, name) -> {
          if (language == null) {
            return;
          }
          String lang = language.trim().toLowerCase(Locale.ROOT);
          if (!LANGUAGES.contains(lang)) {
            throw new ValidationException("unsupported language: " + language);
          }
          String normalized = Text.normalizeText(name);
          if (normalized != null) {
            out.put(lang, normalized);
          }
        });
    return out;
  }

  private static String normalizeSlug(String raw) {
    return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
  }

  private static String requireSlug(String raw, String what) {
    String slug = normalizeSlug(raw);
    if (slug == null || slug.isBlank()) {
      throw new ValidationException(what + " is required");
    }
    if (slug.length() > MAX_SLUG_CHARS) {
      throw new ValidationException(what + " exceeds " + MAX_SLUG_CHARS + " characters: " + slug);
    }
    if (!SLUG_PATTERN.matcher(slug).matches()) {
      throw new ValidationException(
          what + " must be lowercase letters, digits and hyphens: " + raw);
    }
    return slug;
  }

  private String defaultLocale(UUID orgId, DSLContext txDsl) {
    OrgRepository orgRepo = orgRepoFactory.create(txDsl);
    Org org = orgRepo.findById(orgId).orElseThrow(() -> new NotFoundException("Org", orgId));
    String locale = org.getDefaultLocale();
    return locale == null || locale.isBlank() ? "ar" : locale.trim().toLowerCase(Locale.ROOT);
  }
}
