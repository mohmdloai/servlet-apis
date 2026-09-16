package com.loai.inventory.api.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.GoodsReceiptHandler;
import com.loai.inventory.api.servlet.handler.SupplierHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.GoodsReceiptListFilter;
import com.loai.inventory.domain.model.GoodsReceiptStatus;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.GoodsReceiptService;
import com.loai.inventory.service.SupplierService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * The role gates and the parameter 400s for the two inbound resources ({@code
 * stories/supplier_goods_receipt.md} §Tests), without a database.
 *
 * <pre>
 *   /suppliers            VIEWER read · STAFF write · MANAGER delete   (it carries no money)
 *   /suppliers/{id}/receipts                              MANAGER      (it carries costs)
 *   /goods-receipts       MANAGER throughout, key required on POST
 * </pre>
 */
class InboundHandlerAuthTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID ID = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  // /suppliers — the /customers gates

  @Test
  void supplierList_allowedForViewer() throws IOException {
    SupplierService service = Mockito.mock(SupplierService.class);
    when(service.list(eq(ORG), any(), any(), anyInt(), anyInt()))
        .thenReturn(new SupplierService.SupplierPage(List.of(), 0));
    Resp resp = new Resp();

    supplierHandler(service).handle("GET", req(OrgRole.VIEWER, Map.of()), resp.mock, ORG, "");

    assertEquals(200, resp.status);
  }

  @Test
  void supplierWrite_refusedBelowStaff_deleteBelowManager() throws IOException {
    SupplierService service = Mockito.mock(SupplierService.class);
    Resp create = new Resp();
    supplierHandler(service)
        .handle("POST", reqWithBody(OrgRole.VIEWER, "{\"name\":\"Zaki\"}"), create.mock, ORG, "");
    assertEquals(403, create.status);

    Resp delete = new Resp();
    supplierHandler(service)
        .handle("DELETE", req(OrgRole.STAFF, Map.of()), delete.mock, ORG, "/" + ID);
    assertEquals(403, delete.status);

    verify(service, never()).create(any(), any());
    verify(service, never()).delete(any(), any());
  }

  @Test
  void supplierList_unknownActiveValue_is400_beforeTheService() throws IOException {
    SupplierService service = Mockito.mock(SupplierService.class);
    Resp resp = new Resp();

    supplierHandler(service)
        .handle("GET", req(OrgRole.VIEWER, Map.of("active", "maybe")), resp.mock, ORG, "");

    assertEquals(400, resp.status);
    verify(service, never()).list(any(), any(), any(), anyInt(), anyInt());
  }

  @Test
  void supplierReceiptsSubresource_isManager_andReadOnly() throws IOException {
    SupplierService service = Mockito.mock(SupplierService.class);
    when(service.receipts(eq(ORG), eq(ID), anyInt(), anyInt()))
        .thenReturn(new SupplierService.SupplierReceipts(List.of(), Map.of(), 0));

    Resp staff = new Resp();
    supplierHandler(service)
        .handle("GET", req(OrgRole.STAFF, Map.of()), staff.mock, ORG, "/" + ID + "/receipts");
    assertEquals(403, staff.status, "the subresource carries costs");

    Resp manager = new Resp();
    supplierHandler(service)
        .handle("GET", req(OrgRole.MANAGER, Map.of()), manager.mock, ORG, "/" + ID + "/receipts");
    assertEquals(200, manager.status);

    Resp posted = new Resp();
    supplierHandler(service)
        .handle("POST", req(OrgRole.MANAGER, Map.of()), posted.mock, ORG, "/" + ID + "/receipts");
    assertEquals(405, posted.status);
  }

  @Test
  void supplier_unknownSubresource_is404() throws IOException {
    Resp resp = new Resp();
    supplierHandler(Mockito.mock(SupplierService.class))
        .handle("GET", req(OrgRole.VIEWER, Map.of()), resp.mock, ORG, "/" + ID + "/invoices");
    assertEquals(404, resp.status);
  }

  // /goods-receipts — MANAGER throughout

  @Test
  void receipts_refusedBelowManager_onEveryRoute() throws IOException {
    GoodsReceiptService service = Mockito.mock(GoodsReceiptService.class);

    for (String[] call :
        new String[][] {
          {"GET", ""}, {"GET", "/" + ID}, {"POST", ""}, {"POST", "/" + ID + "/void"}
        }) {
      Resp resp = new Resp();
      goodsReceiptHandler(service)
          .handle(call[0], reqWithBody(OrgRole.STAFF, "{}"), resp.mock, ORG, call[1]);
      assertEquals(403, resp.status, call[0] + " " + call[1] + " must be MANAGER");
    }
    Mockito.verifyNoInteractions(service);
  }

  @Test
  void receiptPost_withoutIdempotencyKey_is400_beforeTheService() throws IOException {
    GoodsReceiptService service = Mockito.mock(GoodsReceiptService.class);
    Resp resp = new Resp();

    goodsReceiptHandler(service)
        .handle("POST", reqWithBody(OrgRole.MANAGER, "{}"), resp.mock, ORG, "");

    assertEquals(400, resp.status);
    verify(service, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void receiptList_parsesFilters_andRefusesBadOnes() throws IOException {
    GoodsReceiptService service = Mockito.mock(GoodsReceiptService.class);
    when(service.list(eq(ORG), any(GoodsReceiptListFilter.class), anyInt(), anyInt()))
        .thenReturn(new GoodsReceiptService.ReceiptPage(List.of(), Map.of(), Map.of(), 0));

    Resp ok = new Resp();
    goodsReceiptHandler(service)
        .handle(
            "GET",
            req(
                OrgRole.MANAGER,
                Map.of(
                    "status", "voided",
                    "from", "2026-01-01T00:00:00Z",
                    "to", "2026-02-01T00:00:00Z",
                    "q", "  GRN-2026  ")),
            ok.mock,
            ORG,
            "");
    assertEquals(200, ok.status);
    ArgumentCaptor<GoodsReceiptListFilter> captor =
        ArgumentCaptor.forClass(GoodsReceiptListFilter.class);
    verify(service).list(eq(ORG), captor.capture(), anyInt(), anyInt());
    assertEquals(GoodsReceiptStatus.VOIDED, captor.getValue().status());
    assertEquals("GRN-2026", captor.getValue().q());

    assertEquals(400, statusOfList(service, Map.of("status", "draft")), "unknown status");
    assertEquals(400, statusOfList(service, Map.of("from", "2026-01-01")), "a bare date");
    assertEquals(
        400,
        statusOfList(service, Map.of("from", "2026-02-01T00:00:00Z", "to", "2026-01-01T00:00:00Z")),
        "from >= to");
    assertEquals(400, statusOfList(service, Map.of("supplier_id", "not-a-uuid")));
  }

  @Test
  void receipts_wrongVerbIs405_unknownSubresourceIs404() throws IOException {
    GoodsReceiptService service = Mockito.mock(GoodsReceiptService.class);

    Resp put = new Resp();
    goodsReceiptHandler(service)
        .handle("PUT", req(OrgRole.MANAGER, Map.of()), put.mock, ORG, "/" + ID);
    assertEquals(405, put.status);

    Resp unknown = new Resp();
    goodsReceiptHandler(service)
        .handle("POST", req(OrgRole.MANAGER, Map.of()), unknown.mock, ORG, "/" + ID + "/unvoid");
    assertEquals(404, unknown.status);
  }

  // shims

  private int statusOfList(GoodsReceiptService service, Map<String, String> params)
      throws IOException {
    Resp resp = new Resp();
    goodsReceiptHandler(service).handle("GET", req(OrgRole.MANAGER, params), resp.mock, ORG, "");
    return resp.status;
  }

  private SupplierHandler supplierHandler(SupplierService service) {
    return new SupplierHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private GoodsReceiptHandler goodsReceiptHandler(GoodsReceiptService service) {
    return new GoodsReceiptHandler(
        service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private static SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private HttpServletRequest req(OrgRole role, Map<String, String> params) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctxWith(role));
    when(req.getParameter(Mockito.anyString()))
        .thenAnswer(inv -> params.get(inv.<String>getArgument(0)));
    return req;
  }

  private HttpServletRequest reqWithBody(OrgRole role, String json) throws IOException {
    HttpServletRequest req = req(role, Map.of());
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    when(req.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              private final ByteArrayInputStream in = new ByteArrayInputStream(bytes);

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
