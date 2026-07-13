package com.loai.inventory.service.document;

import com.loai.inventory.common.storage.ObjectStorage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The production {@link DocumentRenderService.LogoSource}: presigns a GET for the org's {@code
 * logo_object_key} (an offline HMAC — {@link ObjectStorage} never talks to the store) and fetches
 * the bytes over HTTP with tight timeouts. Errors propagate to the renderer, which logs and falls
 * back to the text-only header — a slow or missing object store must cost at most the timeout,
 * never the document.
 */
public final class PresignedLogoSource implements DocumentRenderService.LogoSource {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

  private final ObjectStorage storage;
  private final HttpClient http;

  public PresignedLogoSource(ObjectStorage storage) {
    this.storage = storage;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  @Override
  public byte[] fetch(String objectKey) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(storage.presignGet(objectKey)))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw new IOException("logo fetch returned HTTP " + response.statusCode());
    }
    return response.body();
  }
}
