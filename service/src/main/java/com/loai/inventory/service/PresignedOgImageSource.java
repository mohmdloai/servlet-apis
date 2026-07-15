package com.loai.inventory.service;

import com.loai.inventory.common.storage.ObjectStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The production {@link OgImageSource}: presigns a GET for the object key (an offline HMAC — {@link
 * ObjectStorage} never talks to the store) and fetches the bytes over HTTP with tight timeouts,
 * reading the stored content type off the response header. Any non-200, timeout, or error degrades
 * to {@link Optional#empty()} — a slow or missing object store costs at most the timeout, never a
 * hanging crawler request (epic §6). Mirrors {@code PresignedLogoSource}.
 */
public final class PresignedOgImageSource implements OgImageSource {

  private static final Logger log = LoggerFactory.getLogger(PresignedOgImageSource.class);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);
  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

  private final ObjectStorage storage;
  private final HttpClient http;

  public PresignedOgImageSource(ObjectStorage storage) {
    this.storage = storage;
    this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  }

  @Override
  public Optional<Fetched> fetch(String objectKey) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(storage.presignGet(objectKey)))
              .timeout(REQUEST_TIMEOUT)
              .GET()
              .build();
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        log.debug("og-image fetch returned HTTP {} for key {}", response.statusCode(), objectKey);
        return Optional.empty();
      }
      String contentType =
          response.headers().firstValue("content-type").orElse(DEFAULT_CONTENT_TYPE);
      return Optional.of(new Fetched(response.body(), contentType));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (Exception e) {
      log.debug("og-image fetch failed for key {}: {}", objectKey, e.toString());
      return Optional.empty();
    }
  }
}
