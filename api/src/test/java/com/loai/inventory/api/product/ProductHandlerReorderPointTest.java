package com.loai.inventory.api.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.ProductHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.ProductService;
import com.loai.inventory.service.ProductService.CostPriceChange;
import com.loai.inventory.service.ProductService.ReorderPointChange;
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
 * {@code reorder_point} on the product write/read surface ({@code stories/reorder_point.md}, V94):
 * a plain nullable integer, full-replace on PUT like {@code barcode} (absent or {@code null}
 * clears), visible to every role, echoed on the response only when set.
 */
class ProductHandlerReorderPointTest {

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

  private static Product product(Integer reorderPoint) {
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
    p.setReorderPoint(reorderPoint);
    return p;
  }

  @Test
  void staffPost_passesTheReorderPoint() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Mockito.when(
            service.create(
                eq(ORG), any(), any(), any(), any(), any(), any(CostPriceChange.class), any()))
        .thenReturn(product(5));
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            req(
                member(OrgRole.STAFF),
                "{\"name\":\"Notebook A5\",\"sku\":\"NB-A5\",\"base_price\":50,\"reorder_point\":5}"),
            resp.mock,
            ORG,
            "");

    assertEquals(201, resp.status);
    verify(service).create(eq(ORG), any(), any(), any(), any(), any(), any(), eq(5));
    assertEquals(5, resp.json().get("reorder_point").asInt());
  }

  @Test
  void put_isFullReplace_valueSets_absentClears_nullClears() throws IOException {
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
                any(ReorderPointChange.class)))
        .thenReturn(product(null));
    ArgumentCaptor<ReorderPointChange> change = ArgumentCaptor.forClass(ReorderPointChange.class);
    String base = "\"name\":\"Notebook A5\",\"sku\":\"NB-A5\",\"base_price\":50";

    for (String body :
        List.of(
            "{" + base + ",\"reorder_point\":12}",
            "{" + base + "}",
            "{" + base + ",\"reorder_point\":null}")) {
      Resp resp = new Resp();
      handler(service).handle("PUT", req(member(OrgRole.STAFF), body), resp.mock, ORG, "/" + ID);
      assertEquals(200, resp.status);
    }

    verify(service, Mockito.times(3))
        .update(eq(ORG), eq(ID), any(), any(), any(), any(), any(), any(), change.capture());
    List<ReorderPointChange> changes = change.getAllValues();
    assertTrue(changes.get(0).present());
    assertEquals(12, changes.get(0).value());
    assertTrue(changes.get(1).present(), "absent on the wire is still a full replace → clears");
    assertNull(changes.get(1).value());
    assertTrue(changes.get(2).present());
    assertNull(changes.get(2).value());
  }

  @Test
  void response_omitsTheKeyWhenUnset_andEveryRoleSeesIt() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Mockito.when(service.getById(ORG, ID)).thenReturn(product(null));
    Resp unset = new Resp();
    handler(service).handle("GET", req(member(OrgRole.VIEWER), ""), unset.mock, ORG, "/" + ID);
    assertEquals(200, unset.status);
    assertFalse(unset.json().has("reorder_point"), "unset → key absent, never null or 0");

    Mockito.when(service.getById(ORG, ID)).thenReturn(product(0));
    Resp zero = new Resp();
    handler(service).handle("GET", req(member(OrgRole.VIEWER), ""), zero.mock, ORG, "/" + ID);
    assertEquals(
        0, zero.json().get("reorder_point").asInt(), "0 is a rule: tell me when it's gone");
  }

  // harness (mirrors ProductHandlerCostTest)

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
