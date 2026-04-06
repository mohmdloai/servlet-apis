package com.loai.inventory.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.common.DataSourceFactory;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.service.CustomerService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.ProductService;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root — constructs and wires every singleton in the application.
 *
 * <p>This is the ONLY place in the codebase that: - knows about both interfaces and their
 * implementations - imports from both domain and repository modules simultaneously - wires
 * ProductRepository (domain) → ProductRepositoryImpl (repository)
 *
 * <p>Built once by AppBootstrap at Tomcat startup. Held in ServletContext so servlets can retrieve
 * what they need.
 *
 * <p>No DI framework — plain constructor injection throughout. If this wiring grows too large,
 * split into per-domain factory methods.
 */
public class AppConfig {

  private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

  // ── Infrastructure ────────────────────────────────────────────
  public final HikariDataSource dataSource;
  public final DSLContext dsl;
  public final ObjectMapper objectMapper;

  // ── Repositories (domain interface type — not the impl) ───────
  public final ProductRepository productRepository;
  public final CustomerRepositoryFactory customerRepositoryFactory;
  public final InventoryRepositoryFactory inventoryRepositoryFactory;
  public final InventoryLogRepositoryFactory inventoryLogRepositoryFactory;

  // ── Services ──────────────────────────────────────────────────
  public final ProductService productService;
  public final CustomerService customerService;
  public final InventoryService inventoryService;

  public AppConfig() {
    log.info("Initialising application context...");

    // 1. Connection pool
    this.dataSource = DataSourceFactory.build();

    // 2. Run Flyway migrations — schema is always up-to-date on startup
    runMigrations();

    // 3. jOOQ DSLContext wraps the pool
    this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

    // 4. Jackson
    this.objectMapper = ObjectMapperProvider.build();

    // 5. Repositories — impl type assigned to interface variable
    this.productRepository = new ProductRepositoryImpl(dsl);
    this.customerRepositoryFactory = new CustomerRepositoryFactoryImpl();
    this.inventoryRepositoryFactory = new InventoryRepositoryFactoryImpl();
    this.inventoryLogRepositoryFactory = new InventoryLogRepositoryFactoryImpl();

    // 6. Services — receive only the interface, never the impl
    this.productService = new ProductService(productRepository, dsl);
    this.customerService = new CustomerService(dsl, customerRepositoryFactory);
    this.inventoryService =
        new InventoryService(dsl, inventoryRepositoryFactory, inventoryLogRepositoryFactory);
    log.info("Application context ready.");
  }

  /** Close the connection pool gracefully when Tomcat undeploys the WAR. */
  public void shutdown() {
    log.info("Shutting down application context...");
    dataSource.close();
  }

  private void runMigrations() {
    log.info("Running Flyway migrations...");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    log.info("Flyway migrations complete.");
  }
}
