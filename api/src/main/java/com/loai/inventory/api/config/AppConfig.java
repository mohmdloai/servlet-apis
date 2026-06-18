package com.loai.inventory.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.job.OrderTtlSweeperJob;
import com.loai.inventory.common.DataSourceFactory;
import com.loai.inventory.common.RedisFactory;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.FulfillmentRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryReservationRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.CustomerService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.OrderExpiryService;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.ProductService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jobrunr.configuration.JobRunr;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.server.JobActivator;
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions;
import org.jobrunr.storage.sql.common.SqlStorageProviderFactory;
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

  // Infrastructure
  public final HikariDataSource dataSource;
  public final JedisPool jedisPool;
  public final DSLContext dsl;
  public final ObjectMapper objectMapper;
  public final JwtUtil jwtUtil;
  public final boolean secureCookies;

  // Repositories (domain interface type, not the impl)
  public final ProductRepository productRepository;
  public final UserRepository userRepository;
  public final CustomerRepositoryFactory customerRepositoryFactory;
  public final InventoryRepositoryFactory inventoryRepositoryFactory;
  public final InventoryLogRepositoryFactory inventoryLogRepositoryFactory;
  public final UserRepositoryFactory userRepositoryFactory;
  public final OrgRepositoryFactory orgRepositoryFactory;
  public final SalesOrderRepositoryFactory salesOrderRepositoryFactory;
  public final InventoryReservationRepositoryFactory inventoryReservationRepositoryFactory;
  public final PaymentTransactionRepositoryFactory paymentTransactionRepositoryFactory;
  public final PaymentRepositoryFactory paymentRepositoryFactory;
  public final FulfillmentRepositoryFactory fulfillmentRepositoryFactory;

  // Services
  public final RefreshTokenStore refreshTokenStore;
  public final AuthService authService;
  public final OrgService orgService;
  public final ProductService productService;
  public final CustomerService customerService;
  public final InventoryService inventoryService;
  public final ReservationService reservationService;
  public final SalesOrderService salesOrderService;
  public final OrderExpiryService orderExpiryService;
  public final PaymentService paymentService;
  public final PaymentTransactionService paymentTransactionService;
  public final FulfillmentService fulfillmentService;

  // Order-TTL sweeper job + its JobRunr lifecycle flag.
  public final OrderTtlSweeperJob orderTtlSweeperJob;
  private final boolean jobRunrStarted;

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
    this.salesOrderRepositoryFactory = new SalesOrderRepositoryFactoryImpl();
    this.inventoryReservationRepositoryFactory = new InventoryReservationRepositoryFactoryImpl();
    this.paymentTransactionRepositoryFactory = new PaymentTransactionRepositoryFactoryImpl();
    this.paymentRepositoryFactory = new PaymentRepositoryFactoryImpl();
    this.fulfillmentRepositoryFactory = new FulfillmentRepositoryFactoryImpl();

    this.refreshTokenStore = new RefreshTokenStore(jedisPool);
    this.authService = new AuthService(userRepository, refreshTokenStore, jwtUtil);
    this.orgService = new OrgService(dsl, orgRepositoryFactory, userRepositoryFactory);
    this.productService = new ProductService(productRepository, dsl);
    this.customerService = new CustomerService(dsl, customerRepositoryFactory);
    this.inventoryService =
        new InventoryService(dsl, inventoryRepositoryFactory, inventoryLogRepositoryFactory);
    this.reservationService =
        new ReservationService(
            inventoryRepositoryFactory,
            inventoryReservationRepositoryFactory,
            inventoryLogRepositoryFactory);
    this.salesOrderService =
        new SalesOrderService(dsl, salesOrderRepositoryFactory, reservationService);
    this.orderExpiryService =
        new OrderExpiryService(
            dsl,
            salesOrderRepositoryFactory,
            inventoryRepositoryFactory,
            inventoryReservationRepositoryFactory,
            inventoryLogRepositoryFactory);
    this.paymentService = new PaymentService(paymentRepositoryFactory, salesOrderRepositoryFactory);
    this.paymentTransactionService =
        new PaymentTransactionService(
            dsl, paymentTransactionRepositoryFactory, paymentRepositoryFactory, paymentService);
    this.fulfillmentService =
        new FulfillmentService(
            dsl,
            fulfillmentRepositoryFactory,
            salesOrderRepositoryFactory,
            inventoryRepositoryFactory,
            inventoryReservationRepositoryFactory,
            inventoryLogRepositoryFactory);

    int batchLimit = (int) parseLong(System.getenv("ORDER_SWEEPER_BATCH_LIMIT"), 200L);
    this.orderTtlSweeperJob = new OrderTtlSweeperJob(orderExpiryService, batchLimit);

    // The background scheduler is gated so tests (and any deployment that wants to drive expiry
    // only through POST /api/admin/sweep) can keep expiry deterministic. Default: enabled.
    boolean enableSweeper =
        !"false".equalsIgnoreCase(System.getenv("ORDER_SWEEPER_BACKGROUND_ENABLED"));
    this.jobRunrStarted = enableSweeper && startSweeperScheduler();

    log.info("Application context ready.");
  }

  /**
   * Configure JobRunr against the shared datasource ({@link DatabaseOptions#SKIP_CREATE} — the
   * jobrunr_* tables are owned by Flyway V29, not auto-created), start its {@code
   * BackgroundJobServer}, and register the recurring {@code order-ttl-sweeper}. The custom {@link
   * JobActivator} hands JobRunr our pre-wired {@link OrderTtlSweeperJob} so it keeps its injected
   * service. Returns {@code true} if the scheduler started.
   */
  private boolean startSweeperScheduler() {
    String cron = getenvOrDefault("ORDER_SWEEPER_INTERVAL", "*/30 * * * * *");
    JobActivator activator =
        new JobActivator() {
          @Override
          public <T> T activateJob(Class<T> type) {
            if (type.isInstance(orderTtlSweeperJob)) {
              return type.cast(orderTtlSweeperJob);
            }
            throw new IllegalArgumentException("No JobRunr bean for " + type.getName());
          }
        };

    JobScheduler scheduler =
        JobRunr.configure()
            .useJobActivator(activator)
            .useStorageProvider(
                SqlStorageProviderFactory.using(dataSource, null, DatabaseOptions.SKIP_CREATE))
            .useBackgroundJobServer()
            .initialize()
            .getJobScheduler();

    scheduler.<OrderTtlSweeperJob>scheduleRecurrently(
        "order-ttl-sweeper", cron, OrderTtlSweeperJob::run);
    log.info("Order-TTL sweeper scheduled (cron='{}')", cron);
    return true;
  }

  public void shutdown() {
    log.info("Shutting down application context...");
    // Stop JobRunr BEFORE the connection pool closes — it holds DB connections for its leases.
    if (jobRunrStarted) {
      try {
        JobRunr.destroy();
      } catch (RuntimeException e) {
        log.warn("Error stopping JobRunr scheduler", e);
      }
    }
    if (jedisPool != null) jedisPool.close();
    if (dataSource != null) dataSource.close();
  }

  private static String getenvOrDefault(String key, String defaultValue) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? defaultValue : v;
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
