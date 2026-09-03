package com.loai.inventory.api.sale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.CreditNoteHandler;
import com.loai.inventory.api.servlet.handler.SalesOrderHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.DocumentRenderService.RenderedDocument;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The two {@code receipt.escpos} reads ({@code stories/escpos_receipt.md}): VIEWER-gated, raw bytes
 * as an attachment that is never cached, the {@code width} parameter reaching the renderer
 * untouched, and a width the paper cannot be answered with a 400 before anything renders.
 */
class ReceiptEscposHandlerTest {

  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final UUID ORG = UUID.randomUUID();
  private static final UUID ID = UUID.randomUUID();
  private static final byte[] SLIP = {0x1B, 0x40, 0x1D, 0x56, 0x42, 0x00};

  @Test
  void receipt_viewerGetsTheBytesAsAnUncachedAttachment() throws IOException {
    DocumentRenderService render = Mockito.mock(DocumentRenderService.class);
    when(render.renderReceiptEscpos(ORG, ID, 576))
        .thenReturn(new RenderedDocument("SO-2026-000417.escpos", SLIP));
    Resp resp = new Resp();

    orderHandler(render)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), null), resp.mock, ORG, receiptPath());

    assertEquals(200, resp.status);
    verify(resp.mock).setContentType("application/octet-stream");
    verify(resp.mock)
        .setHeader("Content-Disposition", "attachment; filename=\"SO-2026-000417.escpos\"");
    verify(resp.mock).setHeader("Cache-Control", "private, no-store");
    assertArrayEquals(SLIP, resp.body.toByteArray());
  }

  @Test
  void receipt_widthReachesTheRenderer() throws IOException {
    DocumentRenderService render = Mockito.mock(DocumentRenderService.class);
    when(render.renderReceiptEscpos(ORG, ID, 384)).thenReturn(new RenderedDocument("x", SLIP));
    Resp resp = new Resp();

    orderHandler(render)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.STAFF), "384"), resp.mock, ORG, receiptPath());

    assertEquals(200, resp.status);
    verify(render).renderReceiptEscpos(ORG, ID, 384);
  }

  @Test
  void receipt_unknownWidthIs400_nothingRenders() throws IOException {
    DocumentRenderService render = Mockito.mock(DocumentRenderService.class);
    Resp resp = new Resp();

    orderHandler(render)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), "100"), resp.mock, ORG, receiptPath());

    assertEquals(400, resp.status);
    verify(render, never()).renderReceiptEscpos(any(), any(), anyInt());
  }

  @Test
  void receipt_nonMemberIs403_nothingRenders() throws IOException {
    DocumentRenderService render = Mockito.mock(DocumentRenderService.class);
    Resp resp = new Resp();

    orderHandler(render)
        .handle(
            "GET",
            reqWith(ctxWith(UUID.randomUUID(), OrgRole.OWNER), null),
            resp.mock,
            ORG,
            receiptPath());

    assertEquals(403, resp.status);
    verify(render, never()).renderReceiptEscpos(any(), any(), anyInt());
  }

  @Test
  void returnSlip_viewerGetsTheBytes() throws IOException {
    DocumentRenderService render = Mockito.mock(DocumentRenderService.class);
    when(render.renderReturnSlipEscpos(ORG, ID, 576))
        .thenReturn(new RenderedDocument("CN-2026-0003.escpos", SLIP));
    Resp resp = new Resp();

    noteHandler(render)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), null), resp.mock, ORG, receiptPath());

    assertEquals(200, resp.status);
    verify(resp.mock).setContentType("application/octet-stream");
    verify(resp.mock)
        .setHeader("Content-Disposition", "attachment; filename=\"CN-2026-0003.escpos\"");
    assertArrayEquals(SLIP, resp.body.toByteArray());
  }

  @Test
  void returnSlip_unknownWidthIs400() throws IOException {
    DocumentRenderService render = Mockito.mock(DocumentRenderService.class);
    Resp resp = new Resp();

    noteHandler(render)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), "80"), resp.mock, ORG, receiptPath());

    assertEquals(400, resp.status);
    verify(render, never()).renderReturnSlipEscpos(any(), any(), anyInt());
  }

  // harness (mirrors OrderLookupHandlerAuthTest)

  private static String receiptPath() {
    return "/" + ID + "/receipt.escpos";
  }

  private static SalesOrderHandler orderHandler(DocumentRenderService render) {
    return new SalesOrderHandler(
        Mockito.mock(SalesOrderService.class),
        Mockito.mock(OrderCancellationService.class),
        Mockito.mock(PaymentService.class),
        Mockito.mock(FulfillmentService.class),
        Mockito.mock(InvoiceAdminService.class),
        Mockito.mock(InventoryService.class),
        render,
        /* counterReturnService */ null,
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private static CreditNoteHandler noteHandler(DocumentRenderService render) {
    return new CreditNoteHandler(
        Mockito.mock(CreditNoteService.class),
        render,
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private static SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private static HttpServletRequest reqWith(SecurityContext ctx, String width) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    when(req.getParameter("width")).thenReturn(width);
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
