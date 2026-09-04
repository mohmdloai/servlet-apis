package com.loai.inventory.api.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.InventoryHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.service.InventoryService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@code POST /inventory/{productId}/adjust} after {@code stories/stocktake_count.md}: the optional
 * {@code reason} is parsed here against the pair the service allows (absent ⇒ {@code ADJUSTMENT};
 * anything outside the pair ⇒ 400 that never reaches the service), the {@code Idempotency-Key}
 * header is threaded through, STAFF is still the gate, and the other actions keep reading the
 * shared qty body.
 */
class InventoryAdjustHandlerTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID PRODUCT = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  @Test
  void reasonAbsent_serviceCalledWithAdjustment_noKey() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    when(service.adjust(any(), any(), anyInt(), any(), any(), any())).thenReturn(inventory(8));
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctx(OrgRole.STAFF), "{\"qty\":-2}", null),
            resp.mock,
            ORG,
            "/" + PRODUCT + "/adjust");

    assertEquals(200, resp.status);
    verify(service)
        .adjust(eq(ORG), eq(PRODUCT), eq(-2), eq(StockReason.ADJUSTMENT), any(), eq(null));
    assertTrue(resp.body.toString(StandardCharsets.UTF_8).contains("\"stock_qty\":8"));
  }

  @Test
  void stocktakeReason_andHeader_threadedThrough() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    when(service.adjust(any(), any(), anyInt(), any(), any(), any())).thenReturn(inventory(30));
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctx(OrgRole.STAFF), "{\"qty\":-2,\"reason\":\"STOCKTAKE\"}", "stocktake:s1:p"),
            resp.mock,
            ORG,
            "/" + PRODUCT + "/adjust");

    assertEquals(200, resp.status);
    verify(service)
        .adjust(
            eq(ORG), eq(PRODUCT), eq(-2), eq(StockReason.STOCKTAKE), any(), eq("stocktake:s1:p"));
  }

  @Test
  void reasonOutsideThePair_is400_serviceNeverCalled() throws IOException {
    for (String bad : new String[] {"RESTOCK", "SOLD", "RESERVED", "shrug", "stocktake"}) {
      InventoryService service = Mockito.mock(InventoryService.class);
      Resp resp = new Resp();

      handler(service)
          .handle(
              "POST",
              reqWith(ctx(OrgRole.STAFF), "{\"qty\":-2,\"reason\":\"" + bad + "\"}", null),
              resp.mock,
              ORG,
              "/" + PRODUCT + "/adjust");

      assertEquals(400, resp.status, "reason " + bad + " must be refused");
      assertTrue(
          resp.body.toString(StandardCharsets.UTF_8).contains("ADJUSTMENT, STOCKTAKE"),
          "the 400 names the pair");
      verify(service, never()).adjust(any(), any(), anyInt(), any(), any(), any());
    }
  }

  @Test
  void viewer_is403_serviceNeverCalled() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(ctx(OrgRole.VIEWER), "{\"qty\":-2,\"reason\":\"STOCKTAKE\"}", "k"),
            resp.mock,
            ORG,
            "/" + PRODUCT + "/adjust");

    assertEquals(403, resp.status);
    verify(service, never()).adjust(any(), any(), anyInt(), any(), any(), any());
  }

  @Test
  void restock_stillReadsTheSharedBody_andIgnoresReason() throws IOException {
    InventoryService service = Mockito.mock(InventoryService.class);
    when(service.restock(any(), any(), anyInt(), any(), any())).thenReturn(inventory(12));
    Resp resp = new Resp();

    // A reason on a restock body is an unknown property — ignored, as every other unknown is.
    handler(service)
        .handle(
            "POST",
            reqWith(ctx(OrgRole.STAFF), "{\"qty\":2,\"reason\":\"STOCKTAKE\"}", "r1"),
            resp.mock,
            ORG,
            "/" + PRODUCT + "/restock");

    assertEquals(200, resp.status);
    verify(service).restock(eq(ORG), eq(PRODUCT), eq(2), any(), eq("r1"));
    verify(service, never()).adjust(any(), any(), anyInt(), any(), any(), any());
  }

  // harness

  private static Inventory inventory(int stock) {
    Inventory inv = new Inventory();
    inv.setOrgId(ORG);
    inv.setProductId(PRODUCT);
    inv.setStockQty(stock);
    inv.setReservedQty(0);
    return inv;
  }

  private static SecurityContext ctx(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private static InventoryHandler handler(InventoryService service) {
    var listings = Mockito.mock(com.loai.inventory.service.ProductListingService.class);
    return new InventoryHandler(
        service, listings, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private static HttpServletRequest reqWith(SecurityContext ctx, String body, String key)
      throws IOException {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    when(req.getHeader("Idempotency-Key")).thenReturn(key);
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    when(req.getInputStream())
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
      when(mock.getOutputStream())
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
  }
}
