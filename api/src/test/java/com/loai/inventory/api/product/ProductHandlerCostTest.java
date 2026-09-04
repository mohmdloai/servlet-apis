package com.loai.inventory.api.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.api.servlet.handler.ProductHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.service.ProductService;
import com.loai.inventory.service.ProductService.CostPriceChange;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Cost is MANAGER-plane data (stories/product_cost_and_margin.md). These pin the handler's two
 * halves of that rule: a {@code cost_price} in the body is refused from below MANAGER before the
 * service is touched (and mapped tri-state otherwise), and the key is written to the response only
 * with manager authority and a costed product — asserted on the JSON tree, so "absent" means the
 * key is not there, not that its value is null.
 */
class ProductHandlerCostTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID ID = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final ObjectMapper JSON = ObjectMapperProvider.build();

  private static SecurityContext member(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private static SecurityContext platformAdmin() {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(SystemRole.ADMIN), Map.of(), Set.of(), 0);
  }

  private static Product costed(BigDecimal cost) {
    Product p =
        new Product(
            ID,
            ORG,
            "Notebook A5",
            null,
            new BigDecimal("50.00"),
            "NB-A5",
            null,
            OffsetDateTime.now(),
            OffsetDateTime.now());
    p.setCostPrice(cost);
    return p;
  }

  private static final String PUT_WITH_COST =
      "{\"name\":\"Notebook A5\",\"sku\":\"NB-A5\",\"base_price\":50,\"cost_price\":30}";
  private static final String PUT_WITHOUT_COST =
      "{\"name\":\"Notebook A5\",\"sku\":\"NB-A5\",\"base_price\":50}";
  private static final String PUT_CLEARING_COST =
      "{\"name\":\"Notebook A5\",\"sku\":\"NB-A5\",\"base_price\":50,\"cost_price\":null}";

  // Writes

  @Test
  void staffPutWithCostPriceIs403BeforeAnyWrite() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Resp resp = new Resp();
    handler(service)
        .handle("PUT", req(member(OrgRole.STAFF), PUT_WITH_COST), resp.mock, ORG, "/" + ID);
    assertEquals(403, resp.status);
    assertTrue(resp.text().contains("cost_price"), "the refusal names the field: " + resp.text());
    verify(service, never())
        .update(any(), any(), any(), any(), any(), any(), any(), any(CostPriceChange.class), any());
  }

  @Test
  void staffPostWithCostPriceIs403BeforeAnyWrite() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Resp resp = new Resp();
    handler(service).handle("POST", req(member(OrgRole.STAFF), PUT_WITH_COST), resp.mock, ORG, "");
    assertEquals(403, resp.status);
    verify(service, never())
        .create(any(), any(), any(), any(), any(), any(), any(CostPriceChange.class), any());
  }

  @Test
  void staffPutWithoutCostPriceIsUnchanged200() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Mockito.when(
            service.update(
                eq(ORG),
                eq(ID),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(CostPriceChange.class),
                any()))
        .thenReturn(costed(new BigDecimal("30.00")));
    Resp resp = new Resp();
    handler(service)
        .handle("PUT", req(member(OrgRole.STAFF), PUT_WITHOUT_COST), resp.mock, ORG, "/" + ID);
    assertEquals(200, resp.status);
    ArgumentCaptor<CostPriceChange> change = ArgumentCaptor.forClass(CostPriceChange.class);
    verify(service)
        .update(eq(ORG), eq(ID), any(), any(), any(), any(), any(), change.capture(), any());
    assertFalse(change.getValue().present(), "absent key → unchanged");
    // And the staff echo carries no cost even though the stored product is costed.
    assertFalse(resp.json().has("cost_price"));
  }

  @Test
  void managerPutMapsTriState_valueSets_nullClears() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Mockito.when(
            service.update(
                eq(ORG),
                eq(ID),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(CostPriceChange.class),
                any()))
        .thenReturn(costed(new BigDecimal("30.00")));
    ArgumentCaptor<CostPriceChange> change = ArgumentCaptor.forClass(CostPriceChange.class);

    Resp set = new Resp();
    handler(service)
        .handle("PUT", req(member(OrgRole.MANAGER), PUT_WITH_COST), set.mock, ORG, "/" + ID);
    assertEquals(200, set.status);

    Resp clear = new Resp();
    handler(service)
        .handle("PUT", req(member(OrgRole.MANAGER), PUT_CLEARING_COST), clear.mock, ORG, "/" + ID);
    assertEquals(200, clear.status);

    verify(service, Mockito.times(2))
        .update(eq(ORG), eq(ID), any(), any(), any(), any(), any(), change.capture(), any());
    List<CostPriceChange> changes = change.getAllValues();
    assertTrue(changes.get(0).present());
    assertEquals(new BigDecimal("30"), changes.get(0).value());
    assertTrue(changes.get(1).present(), "explicit null is present");
    assertNull(changes.get(1).value(), "explicit null clears");
  }

  // Reads

  @Test
  void managerResponseCarriesCostPrice() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Mockito.when(service.getById(ORG, ID)).thenReturn(costed(new BigDecimal("30.00")));
    Resp resp = new Resp();
    handler(service).handle("GET", req(member(OrgRole.MANAGER), ""), resp.mock, ORG, "/" + ID);
    assertEquals(200, resp.status);
    assertEquals(
        0, new BigDecimal("30.00").compareTo(resp.json().get("cost_price").decimalValue()));
  }

  @Test
  void ownerAndPlatformAdminSeeCostPrice() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Mockito.when(service.getById(ORG, ID)).thenReturn(costed(new BigDecimal("30.00")));
    for (SecurityContext ctx : List.of(member(OrgRole.OWNER), platformAdmin())) {
      Resp resp = new Resp();
      handler(service).handle("GET", req(ctx, ""), resp.mock, ORG, "/" + ID);
      assertEquals(200, resp.status);
      assertTrue(resp.json().has("cost_price"));
    }
  }

  @Test
  void staffAndViewerResponseHasNoCostPriceKey() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Mockito.when(service.getById(ORG, ID)).thenReturn(costed(new BigDecimal("30.00")));
    Mockito.when(service.getAll(eq(ORG), any(), Mockito.anyInt(), Mockito.anyInt()))
        .thenReturn(List.of(costed(new BigDecimal("30.00"))));
    for (OrgRole role : List.of(OrgRole.STAFF, OrgRole.VIEWER)) {
      Resp one = new Resp();
      handler(service).handle("GET", req(member(role), ""), one.mock, ORG, "/" + ID);
      assertEquals(200, one.status);
      assertFalse(one.json().has("cost_price"), role + " must not see the key on the detail");
      assertTrue(one.json().has("base_price"), "the price is still there");

      Resp list = new Resp();
      handler(service).handle("GET", req(member(role), ""), list.mock, ORG, "");
      assertEquals(200, list.status);
      assertFalse(
          list.json().get("data").get(0).has("cost_price"),
          role + " must not see the key on a list row");
    }
  }

  @Test
  void uncostedProductHasNoCostPriceKeyEvenForManager() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Mockito.when(service.getById(ORG, ID)).thenReturn(costed(null));
    Resp resp = new Resp();
    handler(service).handle("GET", req(member(OrgRole.MANAGER), ""), resp.mock, ORG, "/" + ID);
    assertEquals(200, resp.status);
    assertFalse(resp.json().has("cost_price"), "not costed → no key, never null or 0");
  }

  // The promoted predicate

  @Test
  void hasManagerAuthority_ownerAndManagerPass_staffAndViewerFail_adminBypasses() {
    assertTrue(AuthzHelper.hasManagerAuthority(member(OrgRole.OWNER), ORG));
    assertTrue(AuthzHelper.hasManagerAuthority(member(OrgRole.MANAGER), ORG));
    assertFalse(AuthzHelper.hasManagerAuthority(member(OrgRole.STAFF), ORG));
    assertFalse(AuthzHelper.hasManagerAuthority(member(OrgRole.VIEWER), ORG));
    assertTrue(AuthzHelper.hasManagerAuthority(platformAdmin(), ORG));
    // A manager elsewhere is nobody here.
    assertFalse(AuthzHelper.hasManagerAuthority(member(OrgRole.MANAGER), UUID.randomUUID()));
    assertFalse(AuthzHelper.hasManagerAuthority(null, ORG));
  }

  // Harness

  private ProductHandler handler(ProductService service) {
    return new ProductHandler(service, JSON);
  }

  private static final class Resp {
    final HttpServletResponse mock;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    int status = 200;

    Resp() throws IOException {
      mock = Mockito.mock(HttpServletResponse.class);
      Mockito.doAnswer(
              inv -> {
                status = inv.getArgument(0);
                return null;
              })
          .when(mock)
          .setStatus(Mockito.anyInt());
      Mockito.when(mock.getOutputStream())
          .thenReturn(
              new ServletOutputStream() {
                @Override
                public void write(int b) {
                  body.write(b);
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
              });
    }

    String text() {
      return body.toString(StandardCharsets.UTF_8);
    }

    JsonNode json() throws IOException {
      return JSON.readTree(body.toByteArray());
    }
  }

  private static HttpServletRequest req(SecurityContext ctx, String body) throws IOException {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    Mockito.when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    Mockito.when(req.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              final ByteArrayInputStream in = new ByteArrayInputStream(bytes);

              @Override
              public int read() {
                return in.read();
              }

              @Override
              public boolean isFinished() {
                return in.available() == 0;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener listener) {}
            });
    return req;
  }
}
