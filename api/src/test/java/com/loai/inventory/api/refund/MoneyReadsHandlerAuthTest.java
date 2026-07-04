package com.loai.inventory.api.refund;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.CreditNoteHandler;
import com.loai.inventory.api.servlet.handler.RefundHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.RefundStatus;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.CreditNoteService.InvoiceCreditNotes;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.RefundService.RefundPage;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Runtime auth + param verification for the money-side list reads ({@code stories/money_reads.md}):
 * {@code GET /refunds} and {@code GET /credit-notes?sales_invoice_id=} are reads, so VIEWER
 * suffices; anon 401; an unknown {@code status}, non-integer paging, or a missing/malformed {@code
 * sales_invoice_id} is a 400 that never reaches the service.
 */
class MoneyReadsHandlerAuthTest {

  static {
    // The mocked services are final; the Mockito inline mock-maker needs ByteBuddy experimental
    // mode on Java 25 (mirrors OrderLookupHandlerAuthTest).
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID INVOICE = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private RefundHandler refundHandler(RefundService service) {
    return new RefundHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private CreditNoteHandler creditNoteHandler(CreditNoteService service) {
    return new CreditNoteHandler(
        service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  // GET /refunds

  @Test
  void refundsList_allowedForViewer_serviceCalled() throws IOException {
    RefundService service = Mockito.mock(RefundService.class);
    when(service.list(eq(ORG), eq(RefundStatus.PENDING), anyInt(), anyInt()))
        .thenReturn(new RefundPage(List.of(), 0));
    Resp resp = new Resp();

    refundHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("status", "PENDING")),
            resp.mock,
            ORG,
            "");

    assertEquals(200, resp.status, "VIEWER refunds worklist must succeed");
    verify(service).list(eq(ORG), eq(RefundStatus.PENDING), eq(0), anyInt());
  }

  @Test
  void refundsList_unauthenticated_is401_serviceNeverCalled() throws IOException {
    RefundService service = Mockito.mock(RefundService.class);
    Resp resp = new Resp();

    refundHandler(service).handle("GET", reqWith(null, Map.of()), resp.mock, ORG, "");

    assertEquals(401, resp.status, "missing auth must be 401");
    verify(service, never()).list(any(), any(), anyInt(), anyInt());
  }

  @Test
  void refundsList_unknownStatus_is400_serviceNeverCalled() throws IOException {
    RefundService service = Mockito.mock(RefundService.class);
    Resp resp = new Resp();

    refundHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("status", "SHRUG")),
            resp.mock,
            ORG,
            "");

    assertEquals(400, resp.status, "unknown status must fail loudly");
    verify(service, never()).list(any(), any(), anyInt(), anyInt());
  }

  @Test
  void refundsList_nonIntegerPaging_is400_serviceNeverCalled() throws IOException {
    RefundService service = Mockito.mock(RefundService.class);
    Resp resp = new Resp();

    refundHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("page", "one")),
            resp.mock,
            ORG,
            "");

    assertEquals(400, resp.status);
    verify(service, never()).list(any(), any(), anyInt(), anyInt());
  }

  // GET /credit-notes?sales_invoice_id=

  @Test
  void creditNotesList_allowedForViewer_serviceCalled() throws IOException {
    CreditNoteService service = Mockito.mock(CreditNoteService.class);
    SalesInvoice invoice = Mockito.mock(SalesInvoice.class);
    when(invoice.getStatus()).thenReturn(com.loai.inventory.domain.model.InvoiceStatus.ISSUED);
    when(service.listForInvoice(ORG, INVOICE, null))
        .thenReturn(new InvoiceCreditNotes(invoice, BigDecimal.ZERO, List.of()));
    Resp resp = new Resp();

    creditNoteHandler(service)
        .handle(
            "GET",
            reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("sales_invoice_id", INVOICE.toString())),
            resp.mock,
            ORG,
            "");

    assertEquals(200, resp.status, "VIEWER crediting story must succeed");
    verify(service).listForInvoice(ORG, INVOICE, null);
  }

  @Test
  void creditNotesList_missingInvoiceParam_is400_theRouteStaysReserved() throws IOException {
    CreditNoteService service = Mockito.mock(CreditNoteService.class);
    Resp resp = new Resp();

    creditNoteHandler(service)
        .handle("GET", reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of()), resp.mock, ORG, "");

    assertEquals(400, resp.status, "bare GET /credit-notes is reserved");
    verify(service, never()).listForInvoice(any(), any(), any());
  }

  @Test
  void creditNotesList_malformedInvoiceIdOrUnknownStatus_is400() throws IOException {
    CreditNoteService service = Mockito.mock(CreditNoteService.class);
    Resp badId = new Resp();
    Resp badStatus = new Resp();

    CreditNoteHandler handler = creditNoteHandler(service);
    handler.handle(
        "GET",
        reqWith(ctxWith(ORG, OrgRole.VIEWER), Map.of("sales_invoice_id", "not-a-uuid")),
        badId.mock,
        ORG,
        "");
    handler.handle(
        "GET",
        reqWith(
            ctxWith(ORG, OrgRole.VIEWER),
            Map.of("sales_invoice_id", INVOICE.toString(), "status", "SHRUG")),
        badStatus.mock,
        ORG,
        "");

    assertEquals(400, badId.status);
    assertEquals(400, badStatus.status);
    verify(service, never()).listForInvoice(any(), any(), any());
  }

  @Test
  void creditNotesList_unauthenticated_is401_serviceNeverCalled() throws IOException {
    CreditNoteService service = Mockito.mock(CreditNoteService.class);
    Resp resp = new Resp();

    creditNoteHandler(service)
        .handle(
            "GET",
            reqWith(null, Map.of("sales_invoice_id", INVOICE.toString())),
            resp.mock,
            ORG,
            "");

    assertEquals(401, resp.status, "missing auth must be 401");
    verify(service, never()).listForInvoice(any(), any(), any());
  }

  // harness (mirrors OrderDetailReadsHandlerAuthTest)

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

  private HttpServletRequest reqWith(SecurityContext ctx, Map<String, String> params) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    when(req.getParameter(Mockito.anyString()))
        .thenAnswer(inv -> params.get(inv.<String>getArgument(0)));
    return req;
  }
}
