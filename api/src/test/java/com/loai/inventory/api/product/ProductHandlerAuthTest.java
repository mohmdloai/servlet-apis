package com.loai.inventory.api.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.loai.inventory.api.servlet.handler.ProductHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.ProductService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * DELETE {@code /products/{id}} requires MANAGER — the gate that was previously missing entirely.
 * Lower roles get 403 and the service is never called.
 */
class ProductHandlerAuthTest {

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

  private ProductHandler handler(ProductService service) {
    return new ProductHandler(service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void delete_forbiddenForStaff() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Resp resp = new Resp();
    handler(service).handle("DELETE", reqWith(ctxWith(OrgRole.STAFF)), resp.mock, ORG, "/" + ID);
    assertEquals(403, resp.status);
    verify(service, never()).delete(any(), any());
  }

  @Test
  void delete_allowedForManager() throws IOException {
    ProductService service = Mockito.mock(ProductService.class);
    Resp resp = new Resp();
    handler(service).handle("DELETE", reqWith(ctxWith(OrgRole.MANAGER)), resp.mock, ORG, "/" + ID);
    assertEquals(204, resp.status);
    verify(service).delete(ORG, ID);
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
  }

  private HttpServletRequest reqWith(SecurityContext ctx) throws IOException {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    Mockito.when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    byte[] bytes = new byte[0];
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
