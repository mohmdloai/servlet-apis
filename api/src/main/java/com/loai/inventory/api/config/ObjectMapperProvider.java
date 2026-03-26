package com.loai.inventory.api.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Produces the single shared ObjectMapper for the application.
 *
 * <p>Why one instance? ObjectMapper is thread-safe after configuration and expensive to build.
 * Creating one per request is a common performance mistake.
 */
public class ObjectMapperProvider {

  private ObjectMapperProvider() {}

  public static ObjectMapper build() {
    ObjectMapper mapper = new ObjectMapper();

    // ── Date/time ─────────────────────────────────────────
    // Required for OffsetDateTime, LocalDate etc. (jOOQ returns these)
    mapper.registerModule(new JavaTimeModule());
    // Serialize dates as ISO-8601 strings, not numeric timestamps
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // ── Field naming ──────────────────────────────────────
    // Java: basePrice → JSON: base_price
    mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    // ── Null handling ─────────────────────────────────────
    // Omit null fields from responses (e.g. description is optional)
    mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // ── Resilience ────────────────────────────────────────
    // Ignore unknown JSON fields in requests — allows API evolution
    // without breaking old clients.
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    return mapper;
  }
}
