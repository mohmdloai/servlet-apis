package com.loai.inventory.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.api.servlet.handler.ProductListingHandler;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductListingService.ImageView;
import com.loai.inventory.service.ProductListingService.PresignResult;
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
 * Role matrix for {@code /product-listings}: VIEWER read, STAFF write + lifecycle + images, MANAGER
 * delete. Lower roles get 403 and the service is never called.
 */
class ProductListingHandlerAuthTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final UUID ORG = UUID.randomUUID();
  private static final UUID ID = UUID.randomUUID();
  private static final UUID IMG = UUID.randomUUID();
  private static final String SECURITY_CONTEXT_ATTR = "securityContext";

  private SecurityContext ctxWith(OrgRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(), Map.of(ORG, Set.of(role)), Set.of(), 0);
  }

  private ProductListing aListing() {
    return new ProductListing(
        ID,
        ORG,
        UUID.randomUUID(),
        "Title",
        null,
        "slug",
        new BigDecimal("9.99"),
        ListingStatus.DRAFT,
        null,
        OffsetDateTime.now(),
        OffsetDateTime.now());
  }

  private ProductListingHandler handler(ProductListingService service) {
    return new ProductListingHandler(
        service, com.loai.inventory.api.config.ObjectMapperProvider.build());
  }

  @Test
  void list_allowedForViewer() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    when(service.getAll(ORG, null, 0, 10)).thenReturn(List.of());
    when(service.count(ORG, null)).thenReturn(0L);
    Resp resp = new Resp();
    handler(service).handle("GET", reqWith(ctxWith(OrgRole.VIEWER), ""), resp.mock, ORG, "");
    assertEquals(200, resp.status);
    verify(service).getAll(ORG, null, 0, 10);
  }

  @Test
  void create_forbiddenForViewer() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(
                ctxWith(OrgRole.VIEWER),
                "{\"product_id\":\""
                    + UUID.randomUUID()
                    + "\",\"title\":\"T\",\"slug\":\"s\",\"sales_price\":10}"),
            resp.mock,
            ORG,
            "");
    assertEquals(403, resp.status);
    verify(service, never()).create(any(), any(), any(), any(), any(), any());
  }

  @Test
  void create_allowedForStaff() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    when(service.create(any(), any(), any(), any(), any(), any())).thenReturn(aListing());
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(
                ctxWith(OrgRole.STAFF),
                "{\"product_id\":\""
                    + UUID.randomUUID()
                    + "\",\"title\":\"T\",\"slug\":\"s\",\"sales_price\":10}"),
            resp.mock,
            ORG,
            "");
    assertEquals(201, resp.status);
    verify(service).create(any(), any(), any(), any(), any(), any());
  }

  @Test
  void publish_forbiddenForViewer() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST", reqWith(ctxWith(OrgRole.VIEWER), ""), resp.mock, ORG, "/" + ID + "/publish");
    assertEquals(403, resp.status);
    verify(service, never()).publish(any(), any());
  }

  @Test
  void publish_allowedForStaff() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    when(service.publish(ORG, ID)).thenReturn(aListing());
    Resp resp = new Resp();
    handler(service)
        .handle("POST", reqWith(ctxWith(OrgRole.STAFF), ""), resp.mock, ORG, "/" + ID + "/publish");
    assertEquals(200, resp.status);
    verify(service).publish(ORG, ID);
  }

  @Test
  void setCategories_allowedForStaff() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    when(service.setCategories(any(), any(), any())).thenReturn(List.of());
    Resp resp = new Resp();
    handler(service)
        .handle(
            "PUT",
            reqWith(ctxWith(OrgRole.STAFF), "{\"category_ids\":[]}"),
            resp.mock,
            ORG,
            "/" + ID + "/categories");
    assertEquals(200, resp.status);
    verify(service).setCategories(any(), any(), any());
  }

  @Test
  void presignImage_allowedForStaff() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    when(service.presignImageUpload(any(), any(), any(), any()))
        .thenReturn(new PresignResult("http://x/y", "k", 900));
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), "{\"filename\":\"a.png\"}"),
            resp.mock,
            ORG,
            "/" + ID + "/images/presign");
    assertEquals(200, resp.status);
    verify(service).presignImageUpload(any(), any(), any(), any());
  }

  @Test
  void attachImage_forbiddenForViewer() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.VIEWER), "{\"object_key\":\"k\"}"),
            resp.mock,
            ORG,
            "/" + ID + "/images");
    assertEquals(403, resp.status);
    verify(service, never()).attachImage(any(), any(), any(), any(), any());
  }

  @Test
  void attachImage_allowedForStaff() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    when(service.attachImage(any(), any(), any(), any(), any()))
        .thenReturn(new ImageView(IMG, "http://x/y", null, 0));
    Resp resp = new Resp();
    handler(service)
        .handle(
            "POST",
            reqWith(ctxWith(OrgRole.STAFF), "{\"object_key\":\"k\"}"),
            resp.mock,
            ORG,
            "/" + ID + "/images");
    assertEquals(201, resp.status);
    verify(service).attachImage(any(), any(), any(), any(), any());
  }

  @Test
  void deleteListing_forbiddenForStaff() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    Resp resp = new Resp();
    handler(service)
        .handle("DELETE", reqWith(ctxWith(OrgRole.STAFF), ""), resp.mock, ORG, "/" + ID);
    assertEquals(403, resp.status);
    verify(service, never()).delete(any(), any());
  }

  @Test
  void deleteListing_allowedForManager() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    Resp resp = new Resp();
    handler(service)
        .handle("DELETE", reqWith(ctxWith(OrgRole.MANAGER), ""), resp.mock, ORG, "/" + ID);
    assertEquals(204, resp.status);
    verify(service).delete(ORG, ID);
  }

  @Test
  void deleteImage_allowedForStaff() throws IOException {
    ProductListingService service = Mockito.mock(ProductListingService.class);
    Resp resp = new Resp();
    handler(service)
        .handle(
            "DELETE",
            reqWith(ctxWith(OrgRole.STAFF), ""),
            resp.mock,
            ORG,
            "/" + ID + "/images/" + IMG);
    assertEquals(204, resp.status);
    verify(service).removeImage(ORG, ID, IMG);
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
