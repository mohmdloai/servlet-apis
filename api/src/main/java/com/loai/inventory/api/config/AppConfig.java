package com.loai.inventory.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.DataSourceFactory;
import com.loai.inventory.common.RedisFactory;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.CustomerService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.ProductService;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

/**
 * Composition root — constructs and wires every singleton in the application.
 *
 * <p>Reads {@code JWT_SECRET} from the environment (required, base64, ≥32 bytes after decode).
 * Reads {@code JWT_ACCESS_TTL_MILLIS} (optional, default 900000 = 15 min). Reads {@code
 * COOKIE_SECURE} (optional, default false in dev, must be true behind HTTPS).
 */
public class AppConfig {

  private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
  private static final long DEFAULT_ACCESS_TTL_MILLIS = 15 * 60 * 1000L;

  // ── Infrastructure ────────────────────────────────────────────
  public final HikariDataSource dataSource;
  public final JedisPool jedisPool;
  public final DSLContext dsl;
  public final ObjectMapper objectMapper;
  public final JwtUtil jwtUtil;
  public final boolean secureCookies;

  // ── Repositories (domain interface type, not the impl) ────────
  public final ProductRepository productRepository;
  public final UserRepository userRepository;
  public final CustomerRepositoryFactory customerRepositoryFactory;
  public final InventoryRepositoryFactory inventoryRepositoryFactory;
  public final InventoryLogRepositoryFactory inventoryLogRepositoryFactory;
  public final UserRepositoryFactory userRepositoryFactory;
  public final OrgRepositoryFactory orgRepositoryFactory;

  // ── Services ──────────────────────────────────────────────────
  public final RefreshTokenStore refreshTokenStore;
  public final AuthService authService;
  public final OrgService orgService;
  public final ProductService productService;
  public final CustomerService customerService;
  public final InventoryService inventoryService;

  public AppConfig() {
    log.info("Initialising application context...");

    String jwtSecret = System.getenv("JWT_SECRET");
    if (jwtSecret == null || jwtSecret.isBlank()) {
      throw new RuntimeException(
          "JWT_SECRET env var is required and must be at least 32 bytes after Base64 decode");
    }
    long accessTtl = parseLong(System.getenv("JWT_ACCESS_TTL_MILLIS"), DEFAULT_ACCESS_TTL_MILLIS);
    this.secureCookies = Boolean.parseBoolean(System.getenv("COOKIE_SECURE"));

    this.dataSource = DataSourceFactory.build();
    runMigrations();
    this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    this.objectMapper = ObjectMapperProvider.build();

    this.jedisPool = RedisFactory.build();
    this.jwtUtil = new JwtUtil(jwtSecret, accessTtl);

    this.productRepository = new ProductRepositoryImpl(dsl);
    this.userRepository = new UserRepositoryImpl(dsl);
    this.customerRepositoryFactory = new CustomerRepositoryFactoryImpl();
    this.inventoryRepositoryFactory = new InventoryRepositoryFactoryImpl();
    this.inventoryLogRepositoryFactory = new InventoryLogRepositoryFactoryImpl();
    this.userRepositoryFactory = new UserRepositoryFactoryImpl();
    this.orgRepositoryFactory = new OrgRepositoryFactoryImpl();

    this.refreshTokenStore = new RefreshTokenStore(jedisPool);
    this.authService = new AuthService(userRepository, refreshTokenStore, jwtUtil);
    this.orgService = new OrgService(dsl, orgRepositoryFactory, userRepositoryFactory);
    this.productService = new ProductService(productRepository, dsl);
    this.customerService = new CustomerService(dsl, customerRepositoryFactory);
    this.inventoryService =
        new InventoryService(dsl, inventoryRepositoryFactory, inventoryLogRepositoryFactory);
    log.info("Application context ready.");
  }

  public void shutdown() {
    log.info("Shutting down application context...");
    if (jedisPool != null) jedisPool.close();
    if (dataSource != null) dataSource.close();
  }

  private void runMigrations() {
    log.info("Running Flyway migrations...");
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .load()
        .migrate();
    log.info("Flyway migrations complete.");
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
