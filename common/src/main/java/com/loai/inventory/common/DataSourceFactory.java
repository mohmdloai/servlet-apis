package com.loai.inventory.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Properties;

public class DataSourceFactory {
    private static final Logger log = LoggerFactory.getLogger(DataSourceFactory.class);

    private DataSourceFactory() {}

    public static HikariDataSource build() {
        // Load connection info from db.properties on the classpath :resources/db.properties
        Properties props = new Properties();
        try (InputStream is = DataSourceFactory.class.getClassLoader()
                .getResourceAsStream("db.properties")) {
            if (is == null) throw new RuntimeException("db.properties not found on classpath");
            props.load(is);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load db.properties", e);
        }

        String url      = props.getProperty("db.url");
        String user     = props.getProperty("db.user");
        String password = props.getProperty("db.password");
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
        config.addDataSourceProperty("ApplicationName",       "inventory-system");

        return new HikariDataSource(config);
    }
}
