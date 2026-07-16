package com.loai.inventory.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataSourceFactory {
  private static final Logger log = LoggerFactory.getLogger(DataSourceFactory.class);

  private DataSourceFactory() {}

  public static HikariDataSource build() {
    // Connection info comes from env vars first (DB_URL / DB_USER / DB_PASSWORD), falling back to
    // db.properties on the classpath. In prod (container) the env vars point Hikari at the Compose
    // `db` service; in dev the committed db.properties supplies localhost defaults. db.properties
    // is
    // gitignored and therefore absent in CI/prod, so it is optional — the env vars must supply the
    // values there. Mirrors ObjectStorageFactory / RedisFactory.
    Properties props = new Properties();
    try (InputStream is =
        DataSourceFactory.class.getClassLoader().getResourceAsStream("db.properties")) {
      if (is != null) props.load(is);
    } catch (Exception e) {
      throw new RuntimeException("Failed to load db.properties", e);
    }

    String url = getenvOrDefault("DB_URL", props.getProperty("db.url"));
    String user = getenvOrDefault("DB_USER", props.getProperty("db.user"));
    String password = getenvOrDefault("DB_PASSWORD", props.getProperty("db.password"));
    if (url == null || url.isBlank()) {
      throw new RuntimeException(
          "No database URL configured — set the DB_URL env var (or provide db.properties)");
    }
    log.info("Initialising connection pool → {}", url);

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername(user);
    config.setPassword(password);
    config.setDriverClassName("org.postgresql.Driver");

    config.setMaximumPoolSize(10);
    config.setMinimumIdle(2);
    config.setConnectionTimeout(30_000);
    config.setIdleTimeout(600_000);
    config.setMaxLifetime(1_800_000);

    config.setPoolName("inventory-pool");
    config.setLeakDetectionThreshold(5_000);

    config.addDataSourceProperty("reWriteBatchedInserts", "true");
    config.addDataSourceProperty("ApplicationName", "inventory-system");

    return new HikariDataSource(config);
  }

  private static String getenvOrDefault(String key, String defaultValue) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }
}
