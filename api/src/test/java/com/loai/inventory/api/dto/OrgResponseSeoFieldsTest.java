package com.loai.inventory.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.domain.model.Org;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The owner-facing org DTO ({@code GET/PUT /api/orgs/{orgId}}) must echo the V54 storefront SEO
 * fields — {@code meta_title}, {@code meta_description}, {@code og_image_object_key} — so the
 * Settings → Storefront form re-hydrates them on load. When they were dropped, the meta fields
 * silently failed to repopulate after a reload and the admin og-image preview couldn't distinguish
 * a stored image from the logo fallback (it fell back to the logo on every remount). Serialized
 * with the app's SNAKE_CASE mapper so the assertion is over the real wire shape.
 */
class OrgResponseSeoFieldsTest {

  private static final ObjectMapper MAPPER = ObjectMapperProvider.build();

  @Test
  void seoFieldsSurfaceOnTheWire() throws Exception {
    Org org = new Org();
    org.setId(UUID.randomUUID());
    org.setName("Acme");
    org.setSlug("acme");
    org.setMetaTitle("Acme — Best Prices");
    org.setMetaDescription("Shop the latest at Acme");
    org.setOgImageObjectKey("acme/og/social-card.png");

    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(OrgResponse.from(org)));

    assertEquals("Acme — Best Prices", json.get("meta_title").asText());
    assertEquals("Shop the latest at Acme", json.get("meta_description").asText());
    assertEquals("acme/og/social-card.png", json.get("og_image_object_key").asText());
  }

  @Test
  void nullSeoFieldsAreOmitted() throws Exception {
    Org org = new Org();
    org.setId(UUID.randomUUID());
    org.setName("Bare");
    org.setSlug("bare");

    JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(OrgResponse.from(org)));

    // NON_NULL: an unset og key is absent (not a JSON null), so the frontend reads it as "no stored
    // og image" and correctly falls back to the logo — rather than pointing the stream at nothing.
    assertFalse(json.has("meta_title"));
    assertFalse(json.has("meta_description"));
    assertFalse(json.has("og_image_object_key"));
  }
}
