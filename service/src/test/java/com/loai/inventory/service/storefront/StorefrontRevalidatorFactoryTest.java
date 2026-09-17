package com.loai.inventory.service.storefront;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StorefrontRevalidatorFactoryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String URL = "http://storefront:3000/api/revalidate";

  @Test
  void eitherVarUnsetOrBlank_isTheNoop() {
    assertSame(StorefrontRevalidator.NOOP, StorefrontRevalidatorFactory.build(MAPPER, null, null));
    assertSame(StorefrontRevalidator.NOOP, StorefrontRevalidatorFactory.build(MAPPER, URL, null));
    assertSame(StorefrontRevalidator.NOOP, StorefrontRevalidatorFactory.build(MAPPER, null, "s"));
    assertSame(StorefrontRevalidator.NOOP, StorefrontRevalidatorFactory.build(MAPPER, " ", "s"));
    assertSame(StorefrontRevalidator.NOOP, StorefrontRevalidatorFactory.build(MAPPER, URL, "  "));
  }

  @Test
  void bothSet_isTheJdkClient() {
    assertInstanceOf(
        JdkStorefrontRevalidator.class, StorefrontRevalidatorFactory.build(MAPPER, URL, "s"));
  }
}
