package com.loai.inventory.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.CategoryHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Category;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.CategoryService;
import com.loai.inventory.service.CategoryService.TranslatedNameInput;
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

/** Role matrix for {@code /categories}: VIEWER read, STAFF write, MANAGER delete. */
class CategoryHandlerAuthTest {

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

  private Category aCategory() {
    return new Category(
        ID, ORG, null, "Books", "books", OffsetDateTime.now(), OffsetDateTime.now());
  }

  private CategoryHandler handler(CategoryService service) {
    return new CategoryHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void list_unauthenticated_is401() throws IOException {
    CategoryService service = Mockito.mock(CategoryService.class);
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(null, ""), resp.mock, ORG, "");
    assertEquals(401, resp.status);
    verify(service, never()).getAll(any(), anyInt(), anyInt());
  }

  @Test
  void list_allowedForViewer() throws IOException {
    CategoryService service = Mockito.mock(CategoryService.class);
    when(service.getAll(ORG, 0, 10)).thenReturn(List.of());
    when(service.count(ORG)).thenReturn(0L);
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(ctxWith(OrgRole.VIEWER), ""), resp.mock, ORG, "");
    assertEquals(200, resp.status);
    verify(service).getAll(ORG, 0, 10);
  }

  @Test
  void create_forbiddenForViewer_serviceNeverCalled() throws IOException {
    CategoryService service = Mockito.mock(CategoryService.class);
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.VIEWER), "{\"name\":\"Books\",\"slug\":\"books\"}"),
            resp.mock,
            ORG,
            "");
    assertEquals(403, resp.status);
    verify(service, never()).create(any(), any(), any(), any(TranslatedNameInput.class), any());
  }

  @Test
  void create_allowedForStaff() throws IOException {
    CategoryService service = Mockito.mock(CategoryService.class);
    when(service.create(any(), any(), any(), any(TranslatedNameInput.class), any()))
        .thenReturn(aCategory());
    // The handler re-reads for the full-language embed (slice L3).
    when(service.getById(any(), any()))
        .thenReturn(new CategoryService.CategoryView(aCategory(), List.of(), null));
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), "{\"name\":\"Books\",\"slug\":\"books\"}"),
            resp.mock,
            ORG,
            "");
    assertEquals(201, resp.status);
    verify(service).create(any(), any(), any(), any(TranslatedNameInput.class), any());
  }

  @Test
  void delete_forbiddenForStaff_serviceNeverCalled() throws IOException {
    CategoryService service = Mockito.mock(CategoryService.class);
    Resp resp = new Resp();
    handler(service)
        .handle("DELETE", reqWith(ctxWith(OrgRole.STAFF), ""), resp.mock, ORG, "/" + ID);
    assertEquals(403, resp.status);
    verify(service, never()).delete(any(), any());
  }

  @Test
  void delete_allowedForManager() throws IOException {
    CategoryService service = Mockito.mock(CategoryService.class);
    Resp resp = new Resp();
    handler(service)
        .handle("DELETE", reqWith(ctxWith(OrgRole.MANAGER), ""), resp.mock, ORG, "/" + ID);
    assertEquals(204, resp.status);
    verify(service).delete(ORG, ID);
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
