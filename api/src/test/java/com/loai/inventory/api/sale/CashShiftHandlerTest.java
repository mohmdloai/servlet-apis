package com.loai.inventory.api.sale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.dto.ApiError;
import com.loai.inventory.api.dto.ApiErrors;
import com.loai.inventory.api.servlet.handler.CashShiftHandler;
import com.loai.inventory.common.exception.ShiftRequiredException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.CashMovementKind;
import com.loai.inventory.domain.model.CashShift;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.repository.CashShiftRepository.Totals;
import com.loai.inventory.service.CashShiftService;
import com.loai.inventory.service.CashShiftService.ShiftView;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.DocumentRenderService.RenderedDocument;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@code /shifts} at the handler ({@code stories/cash_shift.md}): the role per route, the actor and
 * the manager-authority boolean handed to the service (which decides "own"), the 204 on no open
 * shift, the kind parsing, the 409 envelope kind, and the slip's octet-stream.
 */
class CashShiftHandlerTest {

  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final UUID ORG = UUID.randomUUID();
  private static final UUID SHIFT = UUID.randomUUID();
  private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

  private static ShiftView aView(UUID openedBy) {
    CashShift s = CashShift.open(SHIFT, ORG, openedBy, new BigDecimal("300.00"), true, null, NOW);
    Totals t =
        new Totals(
            new BigDecimal("210.00"),
            new BigDecimal("10.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            2,
            BigDecimal.ZERO);
    return new ShiftView(
        s, t, new BigDecimal("500.00"), new CashShiftService.Person(openedBy, "Nadia"), null);
  }

  @Test
  void list_viewerGetsThePage_withDefaults() throws IOException {
    CashShiftService service = Mockito.mock(CashShiftService.class);
    when(service.list(ORG, 0, CashShiftService.DEFAULT_PAGE_SIZE))
        .thenReturn(new CashShiftService.ShiftPage(List.of(aView(UUID.randomUUID())), 1));
    Resp resp = new Resp();

    handler(service)
        .handle("GET", reqWith(ctx(ORG, OrgRole.VIEWER), null, null), resp.mock, ORG, "");

    assertEquals(200, resp.status);
    String body = resp.body.toString(StandardCharsets.UTF_8);
    assertTrue(body.contains("\"total\":1"), body);
    assertTrue(body.contains("\"status\":\"OPEN\""), body);
    assertTrue(body.contains("\"expected_cash\":500.00"), body);
    assertTrue(body.contains("\"auto_opened\":true"), body);
  }

  @Test
  void current_is204WhenNothingIsOpen_and200Otherwise() throws IOException {
    CashShiftService service = Mockito.mock(CashShiftService.class);
    when(service.current(ORG)).thenReturn(Optional.empty());
    Resp resp = new Resp();
    handler(service)
        .handle("GET", reqWith(ctx(ORG, OrgRole.STAFF), null, null), resp.mock, ORG, "/current");
    assertEquals(204, resp.status);

    when(service.current(ORG)).thenReturn(Optional.of(aView(UUID.randomUUID())));
    Resp resp2 = new Resp();
    handler(service)
        .handle("GET", reqWith(ctx(ORG, OrgRole.STAFF), null, null), resp2.mock, ORG, "/current");
    assertEquals(200, resp2.status);
    assertTrue(resp2.body.toString(StandardCharsets.UTF_8).contains("\"opened_by\":{\"id\""));
  }

  @Test
  void open_staffPostsTheFloat_actorIdReachesTheService() throws IOException {
    CashShiftService service = Mockito.mock(CashShiftService.class);
    SecurityContext sc = ctx(ORG, OrgRole.STAFF);
    when(service.open(eq(ORG), eq(new BigDecimal("300.00")), eq("morning"), eq(sc.actorId())))
        .thenReturn(aView(sc.actorId()));
    Resp resp = new Resp();

    handler(service)
        .handle(
            "POST",
            reqWith(sc, "{\"starting_cash\":300.00,\"note\":\"morning\"}", null),
            resp.mock,
            ORG,
            "");

    assertEquals(201, resp.status);
    verify(service).open(ORG, new BigDecimal("300.00"), "morning", sc.actorId());
  }

  @Test
  void open_viewerIs403_nothingCalled() throws IOException {
    CashShiftService service = Mockito.mock(CashShiftService.class);
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(ctx(ORG, OrgRole.VIEWER), "{\"starting_cash\":1}", null),
            resp.mock,
            ORG,
            "");
    assertEquals(403, resp.status);
    verify(service, never()).open(any(), any(), any(), any());
  }

  @Test
  void close_managerAuthorityIsPassed_staffFalse_managerTrue() throws IOException {
    CashShiftService service = Mockito.mock(CashShiftService.class);
    when(service.close(eq(ORG), eq(SHIFT), any(), any(), any(), Mockito.anyBoolean()))
        .thenReturn(aView(UUID.randomUUID()));

    SecurityContext staff = ctx(ORG, OrgRole.STAFF);
    handler(service)
        .handle(
            "POST",
            reqWith(staff, "{\"counted_cash\":495}", null),
            new Resp().mock,
            ORG,
            "/" + SHIFT + "/close");
    verify(service).close(ORG, SHIFT, new BigDecimal("495"), null, staff.actorId(), false);

    SecurityContext manager = ctx(ORG, OrgRole.MANAGER);
    handler(service)
        .handle(
            "POST",
            reqWith(manager, "{\"counted_cash\":495,\"note\":\"ok\"}", null),
            new Resp().mock,
            ORG,
            "/" + SHIFT + "/close");
    verify(service).close(ORG, SHIFT, new BigDecimal("495"), "ok", manager.actorId(), true);
  }

  @Test
  void movement_kindIsParsedCaseInsensitively_andRefusedWhenUnknown() throws IOException {
    CashShiftService service = Mockito.mock(CashShiftService.class);
    SecurityContext sc = ctx(ORG, OrgRole.OWNER);
    CashShift s = CashShift.open(SHIFT, ORG, sc.actorId(), BigDecimal.ZERO, true, null, NOW);
    when(service.addMovement(
            eq(ORG),
            eq(SHIFT),
            eq(CashMovementKind.PAY_OUT),
            eq(new BigDecimal("20")),
            eq("Bank"),
            eq(sc.actorId()),
            eq(true)))
        .thenReturn(
            new CashShiftService.MovementView(
                com.loai.inventory.domain.model.CashMovement.create(
                    UUID.randomUUID(),
                    ORG,
                    s.getId(),
                    CashMovementKind.PAY_OUT,
                    new BigDecimal("20"),
                    "Bank",
                    sc.actorId(),
                    NOW),
                new CashShiftService.Person(sc.actorId(), "Owner")));
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(sc, "{\"kind\":\"pay_out\",\"amount\":20,\"reason\":\"Bank\"}", null),
            resp.mock,
            ORG,
            "/" + SHIFT + "/movements");
    assertEquals(201, resp.status);
    assertTrue(resp.body.toString(StandardCharsets.UTF_8).contains("\"kind\":\"PAY_OUT\""));

    Resp bad = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(sc, "{\"kind\":\"steal\",\"amount\":20,\"reason\":\"x\"}", null),
            bad.mock,
            ORG,
            "/" + SHIFT + "/movements");
    assertEquals(400, bad.status);
  }

  @Test
  void shiftRequired_rides409WithItsKind() {
    ApiError body = ApiErrors.body(new ShiftRequiredException());
    assertEquals(409, body.getStatus());
    assertEquals("SHIFT_REQUIRED", body.getKind());
  }

  @Test
  void slip_isAnOctetStream_atTheRequestedWidth() throws IOException {
    CashShiftService service = Mockito.mock(CashShiftService.class);
    DocumentRenderService render = Mockito.mock(DocumentRenderService.class);
    CashShiftService.Detail detail =
        new CashShiftService.Detail(aView(UUID.randomUUID()), List.of());
    when(service.get(ORG, SHIFT)).thenReturn(detail);
    byte[] slip = {0x1B, 0x40, 0x1D, 0x56, 0x42, 0x00};
    when(render.renderShiftSlipEscpos(ORG, detail, 384))
        .thenReturn(new RenderedDocument("SHIFT-2026-09-05.escpos", slip));
    Resp resp = new Resp();

    new CashShiftHandler(
            service, render, com.loai.inventory.api.config.ObjectMapperProvider.build())
        .handle(
            "GET",
            reqWith(ctx(ORG, OrgRole.VIEWER), null, "384"),
            resp.mock,
            ORG,
            "/" + SHIFT + "/slip.escpos");

    assertEquals(200, resp.status);
    verify(resp.mock).setContentType("application/octet-stream");
    assertArrayEquals(slip, resp.body.toByteArray());
  }

  // harness

  private static CashShiftHandler handler(CashShiftService service) {
    return new CashShiftHandler(
        service,
        Mockito.mock(DocumentRenderService.class),
        com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  private static SecurityContext ctx(UUID orgId, OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(orgId, Set.of(role)), Set.of(), 0);
  }

  private static HttpServletRequest reqWith(SecurityContext sc, String jsonBody, String width)
      throws IOException {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(sc);
    when(req.getParameter("width")).thenReturn(width);
    when(req.getParameter("page")).thenReturn(null);
    when(req.getParameter("size")).thenReturn(null);
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
          .setStatus(anyInt());
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
