package com.loai.inventory.common.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds the {@link ObjectStorage} presigner from {@code storage.properties} (connection info,
 * committed dev defaults) plus {@code S3_ACCESS_KEY} / {@code S3_SECRET_KEY} env vars (credentials;
 * dev fallback {@code minioadmin}). Mirrors {@code DataSourceFactory} / {@code RedisFactory}.
 */
public final class ObjectStorageFactory {
  private static final Logger log = LoggerFactory.getLogger(ObjectStorageFactory.class);

  private ObjectStorageFactory() {}

  public static ObjectStorage build() {
    Properties props = new Properties();
    try (InputStream is =
        ObjectStorageFactory.class.getClassLoader().getResourceAsStream("storage.properties")) {
      if (is == null) {
        throw new RuntimeException("storage.properties not found on classpath");
      }
      props.load(is);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load storage.properties", e);
    }

    String endpoint = props.getProperty("storage.endpoint");
    String region = props.getProperty("storage.region", "us-east-1");
    String bucket = props.getProperty("storage.bucket");
    boolean pathStyle =
        Boolean.parseBoolean(props.getProperty("storage.path-style-access", "true"));
    long ttlSeconds = parseLong(props.getProperty("storage.presign-ttl-seconds"), 900L);

    String accessKey = getenvOrDefault("S3_ACCESS_KEY", "minioadmin");
    String secretKey = getenvOrDefault("S3_SECRET_KEY", "minioadmin");

    log.info("Initialising object storage presigner → endpoint={} bucket={}", endpoint, bucket);

    S3Presigner.Builder builder =
        S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }

    return new ObjectStorage(builder.build(), bucket, Duration.ofSeconds(ttlSeconds));
  }

  private static String getenvOrDefault(String key, String defaultValue) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }

  private static long parseLong(String s, long defaultValue) {
    if (s == null || s.isBlank()) return defaultValue;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
