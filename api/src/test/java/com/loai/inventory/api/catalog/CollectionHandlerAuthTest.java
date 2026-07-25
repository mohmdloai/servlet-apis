package com.loai.inventory.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.CollectionHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Collection;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.CollectionService;
import com.loai.inventory.service.CollectionService.CollectionView;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Role matrix for {@code /collections} (roadmap item 8): VIEWER reads, STAFF curates, MANAGER
 * deletes. A lower role gets a 403 and the service is never called — the guard runs before any
 * work, so an unauthorized caller can't even learn whether a collection exists.
 */
class CollectionHandlerAuthTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID ID = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private CollectionHandler handler(CollectionService service) {
    return new CollectionHandler(service, ObjectMapperProvider.build());
  }

  private static CollectionView aView() {
    return new CollectionView(
        new Collection(ID, ORG, "picks", "Picks", 0, OffsetDateTime.now(), OffsetDateTime.now()),
        List.of(),
        0L);
  }

  @Test
  void list_allowedForViewer() throws IOException {
    CollectionService service = Mockito.mock(CollectionService.class);
    when(service.getAll(ORG)).thenReturn(List.of());
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(ctxWith(OrgRole.VIEWER), ""), resp.mock, ORG, "");
    assertEquals(200, resp.status);
    verify(service).getAll(ORG);
  }

  @Test
  void create_forbiddenForViewer_allowedForStaff() throws IOException {
    CollectionService denied = Mockito.mock(CollectionService.class);
    Resp deniedResp = new Resp();
    handler(denied)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.VIEWER), "{\"slug\":\"picks\",\"name_ar\":\"م\"}"),
            deniedResp.mock,
            ORG,
            "");
    assertEquals(403, deniedResp.status);
    verify(denied, never()).create(any(), any(), any(), any(), any());

    CollectionService allowed = Mockito.mock(CollectionService.class);
    when(allowed.create(any(), any(), any(), any(), any())).thenReturn(aView().collection());
    when(allowed.getById(ORG, ID)).thenReturn(aView());
    Resp okResp = new Resp();
    handler(allowed)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), "{\"slug\":\"picks\",\"name_ar\":\"م\"}"),
            okResp.mock,
            ORG,
            "");
    assertEquals(201, okResp.status);
    verify(allowed).create(any(), any(), any(), any(), any());
  }

  @Test
  void setListings_forbiddenForViewer_allowedForStaff() throws IOException {
    CollectionService denied = Mockito.mock(CollectionService.class);
    Resp deniedResp = new Resp();
    handler(denied)
        .handle(
            "PUT",
            reqWith(ctxWith(OrgRole.VIEWER), "{\"listing_ids\":[]}"),
            deniedResp.mock,
            ORG,
            "/" + ID + "/listings");
    assertEquals(403, deniedResp.status);
    verify(denied, never()).setListings(any(), any(), any());

    CollectionService allowed = Mockito.mock(CollectionService.class);
    when(allowed.setListings(any(), any(), any())).thenReturn(List.of());
    Resp okResp = new Resp();
    handler(allowed)
        .handle(
            "PUT",
            reqWith(ctxWith(OrgRole.STAFF), "{\"listing_ids\":[]}"),
            okResp.mock,
            ORG,
            "/" + ID + "/listings");
    assertEquals(200, okResp.status);
    verify(allowed).setListings(any(), any(), any());
  }

  @Test
  void delete_forbiddenForStaff_allowedForManager() throws IOException {
    CollectionService denied = Mockito.mock(CollectionService.class);
    Resp deniedResp = new Resp();
    handler(denied)
        .handle("DELETE", reqWith(ctxWith(OrgRole.STAFF), ""), deniedResp.mock, ORG, "/" + ID);
    assertEquals(403, deniedResp.status);
    verify(denied, never()).delete(any(), any());

    CollectionService allowed = Mockito.mock(CollectionService.class);
    Resp okResp = new Resp();
    handler(allowed)
        .handle("DELETE", reqWith(ctxWith(OrgRole.MANAGER), ""), okResp.mock, ORG, "/" + ID);
    assertEquals(204, okResp.status);
    verify(allowed).delete(ORG, ID);
  }

  @Test
  void unknownSubRoute_is400() throws IOException {
    CollectionService service = Mockito.mock(CollectionService.class);
    Resp resp = new Resp();
    handler(service)
        .handle("GET", reqWith(ctxWith(OrgRole.OWNER), ""), resp.mock, ORG, "/" + ID + "/ghost");
    assertEquals(400, resp.status);
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
