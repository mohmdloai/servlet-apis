package com.loai.inventory.api.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.CouponHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Coupon;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.CouponService;
import com.loai.inventory.service.CouponService.CouponView;
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
import org.mockito.Mockito;

/**
 * Role matrix for {@code /coupons} (roadmap item 9): VIEWER reads, <b>MANAGER</b> writes. Unlike
 * the STAFF merchandising surfaces, every coupon write changes what the store charges — so it takes
 * the money-shaped authority, and a STAFF caller is refused before the service is ever consulted.
 */
class CouponHandlerAuthTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID ID = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final String CREATE_BODY =
      "{\"code\":\"RAMADAN10\",\"type\":\"PERCENT\",\"value\":10}";

  private SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private CouponHandler handler(CouponService service) {
    return new CouponHandler(service, ObjectMapperProvider.build());
  }

  private static CouponView aView() {
    return new CouponView(
        new Coupon(
            ID,
            ORG,
            "RAMADAN10",
            CouponType.PERCENT,
            new BigDecimal("10.00"),
            null,
            null,
            null,
            null,
            true,
            OffsetDateTime.now(),
            OffsetDateTime.now()),
        0L);
  }

  @Test
  void list_allowedForViewer() throws IOException {
    CouponService service = Mockito.mock(CouponService.class);
    when(service.getAll(ORG, 0, 20)).thenReturn(List.of());
    when(service.count(ORG)).thenReturn(0L);
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(ctxWith(OrgRole.VIEWER), ""), resp.mock, ORG, "");
    assertEquals(200, resp.status);
    verify(service).getAll(ORG, 0, 20);
  }

  @Test
  void create_forbiddenForStaff_allowedForManager() throws IOException {
    CouponService denied = Mockito.mock(CouponService.class);
    Resp deniedResp = new Resp();
    handler(denied)
        .handle("POST", reqWith(ctxWith(OrgRole.STAFF), CREATE_BODY), deniedResp.mock, ORG, "");
    assertEquals(403, deniedResp.status);
    verify(denied, never()).create(any(), any());

    CouponService allowed = Mockito.mock(CouponService.class);
    when(allowed.create(any(), any())).thenReturn(aView().coupon());
    when(allowed.getById(ORG, ID)).thenReturn(aView());
    Resp okResp = new Resp();
    handler(allowed)
        .handle("POST", reqWith(ctxWith(OrgRole.MANAGER), CREATE_BODY), okResp.mock, ORG, "");
    assertEquals(201, okResp.status);
    verify(allowed).create(any(), any());
  }

  @Test
  void patchAndDelete_forbiddenForStaff_allowedForManager() throws IOException {
    CouponService denied = Mockito.mock(CouponService.class);
    Resp patchDenied = new Resp();
    handler(denied)
        .handle(
            "PATCH",
            reqWith(ctxWith(OrgRole.STAFF), "{\"active\":false}"),
            patchDenied.mock,
            ORG,
            "/" + ID);
    assertEquals(403, patchDenied.status);
    verify(denied, never()).patch(any(), any(), any());

    Resp deleteDenied = new Resp();
    handler(denied)
        .handle("DELETE", reqWith(ctxWith(OrgRole.STAFF), ""), deleteDenied.mock, ORG, "/" + ID);
    assertEquals(403, deleteDenied.status);
    verify(denied, never()).delete(any(), any());

    CouponService allowed = Mockito.mock(CouponService.class);
    when(allowed.patch(any(), any(), any())).thenReturn(aView().coupon());
    when(allowed.getById(ORG, ID)).thenReturn(aView());
    Resp patchOk = new Resp();
    handler(allowed)
        .handle(
            "PATCH",
            reqWith(ctxWith(OrgRole.MANAGER), "{\"active\":false}"),
            patchOk.mock,
            ORG,
            "/" + ID);
    assertEquals(200, patchOk.status);

    Resp deleteOk = new Resp();
    handler(allowed)
        .handle("DELETE", reqWith(ctxWith(OrgRole.MANAGER), ""), deleteOk.mock, ORG, "/" + ID);
    assertEquals(204, deleteOk.status);
    verify(allowed).delete(ORG, ID);
  }

  @Test
  void aBadTypeIs400_andTheServiceIsNeverCalled() throws IOException {
    CouponService service = Mockito.mock(CouponService.class);
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.MANAGER), "{\"code\":\"X\",\"type\":\"BOGO\",\"value\":1}"),
            resp.mock,
            ORG,
            "");
    assertEquals(400, resp.status);
    verify(service, never()).create(any(), any());
  }

  @Test
  void putIsNotAllowed_theArithmeticIsNotReplaceable() throws IOException {
    CouponService service = Mockito.mock(CouponService.class);
    Resp resp = new Resp();
    handler(service).handle("PUT", reqWith(ctxWith(OrgRole.OWNER), "{}"), resp.mock, ORG, "/" + ID);
    assertEquals(405, resp.status);
  }

  // ─────────────── harness ───────────────

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

  private HttpServletRequest reqWith(SecurityContext ctx, String jsonBody) throws IOException {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    byte[] bytes = (jsonBody == null ? "" : jsonBody).getBytes(StandardCharsets.UTF_8);
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
}
