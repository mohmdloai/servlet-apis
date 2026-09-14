package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.ORG_PAYMOB_CONFIG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.repository.OrgPaymobConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentIntentRepositoryFactoryImpl;
import com.loai.inventory.service.OrgPaymobService;
import com.loai.inventory.service.OrgPaymobService.ConnectionStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Base64;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Epic slice 1 — connecting a Paymob merchant account ({@code stories/paymob_connect.md}): the
 * credential-storage invariants that are the whole reason the slice is split out on its own —
 * encryption at rest, upsert-not-partial-update, and fail-closed with no key configured.
 */
@Testcontainers
class OrgPaymobServiceIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SecretBox secretBox;
  static OrgPaymobService service;
  static OrgPaymobService serviceNoKey;

  @BeforeAll
  static void startInfra() {
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(PG.getJdbcUrl());
    cfg.setUsername(PG.getUsername());
    cfg.setPassword(PG.getPassword());
    cfg.setMaximumPoolSize(8);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

    secretBox = SecretBox.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
    service =
        new OrgPaymobService(
            dsl,
            new OrgPaymobConfigRepositoryFactoryImpl(),
            new PaymentIntentRepositoryFactoryImpl(),
            secretBox);
    serviceNoKey =
        new OrgPaymobService(
            dsl,
            new OrgPaymobConfigRepositoryFactoryImpl(),
            new PaymentIntentRepositoryFactoryImpl(),
            SecretBox.fromBase64Key(null));
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE payment_intent, sales_order, org_paymob_config, org RESTART IDENTITY CASCADE");
  }

  @Test
  void connect_storesEncryptedSecrets_andTheResponseCarriesNeitherSecret() {
    UUID org = createOrg();

    ConnectionStatus status =
        service.connect(org, "pk_test_1", "sk_test_1", "hmac_1", "ak", 123, "EGYPT");

    assertTrue(status.connected());
    assertEquals(OrgPaymobConfig.Status.ACTIVE, status.status());
    assertEquals("pk_test_1", status.publicKey());
    assertEquals(123, status.cardIntegrationId());
    assertEquals("EGYPT", status.region());
    // ConnectionStatus structurally carries no secret field at all — nothing to assert absent.

    String secretEncrypted =
        dsl.select(ORG_PAYMOB_CONFIG.SECRET_KEY_ENCRYPTED)
            .from(ORG_PAYMOB_CONFIG)
            .where(ORG_PAYMOB_CONFIG.ORG_ID.eq(org))
            .fetchOne(ORG_PAYMOB_CONFIG.SECRET_KEY_ENCRYPTED);
    String hmacEncrypted =
        dsl.select(ORG_PAYMOB_CONFIG.HMAC_SECRET_ENCRYPTED)
            .from(ORG_PAYMOB_CONFIG)
            .where(ORG_PAYMOB_CONFIG.ORG_ID.eq(org))
            .fetchOne(ORG_PAYMOB_CONFIG.HMAC_SECRET_ENCRYPTED);
    assertNotEquals("sk_test_1", secretEncrypted, "must not be stored in the clear");
    assertNotEquals("hmac_1", hmacEncrypted, "must not be stored in the clear");
    assertEquals("sk_test_1", secretBox.decrypt(secretEncrypted));
    assertEquals("hmac_1", secretBox.decrypt(hmacEncrypted));

    // Slice 3 (V100): the legacy API key rides with them, sealed the same way, and the status says
    // the poller can inquire.
    String apiKeyEncrypted =
        dsl.select(ORG_PAYMOB_CONFIG.API_KEY_ENCRYPTED)
            .from(ORG_PAYMOB_CONFIG)
            .where(ORG_PAYMOB_CONFIG.ORG_ID.eq(org))
            .fetchOne(ORG_PAYMOB_CONFIG.API_KEY_ENCRYPTED);
    assertNotEquals("ak", apiKeyEncrypted, "must not be stored in the clear");
    assertEquals("ak", secretBox.decrypt(apiKeyEncrypted));
    assertEquals(Boolean.TRUE, status.inquiryEnabled());
  }

  @Test
  void connect_blankApiKey_is400_sinceSliceThree() {
    UUID org = createOrg();
    assertThrows(
        ValidationException.class, () -> service.connect(org, "pk", "sk", "hmac", " ", 1, "EGYPT"));
    assertThrows(
        ValidationException.class,
        () -> service.connect(org, "pk", "sk", "hmac", null, 1, "EGYPT"));
  }

  @Test
  void rowConnectedBeforeV100_reportsInquiryDisabled() {
    UUID org = createOrg();
    service.connect(org, "pk", "sk", "hmac", "ak", 1, "EGYPT");
    dsl.update(ORG_PAYMOB_CONFIG)
        .setNull(ORG_PAYMOB_CONFIG.API_KEY_ENCRYPTED)
        .where(ORG_PAYMOB_CONFIG.ORG_ID.eq(org))
        .execute();

    assertEquals(Boolean.FALSE, service.status(org).inquiryEnabled());
    assertTrue(service.status(org).connected(), "still offers card; only the poller is blind");
  }

  @Test
  void status_disconnectedUntilConnected_connectedAfterConnect_disconnectedAfterDisconnect() {
    UUID org = createOrg();

    assertFalse(service.status(org).connected());

    service.connect(org, "pk", "sk", "hmac", "ak", 1, "EGYPT");
    assertTrue(service.status(org).connected());

    service.disconnect(org);
    assertFalse(service.status(org).connected());
  }

  @Test
  void reconnect_replacesAllFourCredentials_bumpsUpdatedAt_exactlyOneRow() throws Exception {
    UUID org = createOrg();

    ConnectionStatus first = service.connect(org, "pk-1", "sk-1", "hmac-1", "ak", 111, "EGYPT");
    // The first row's updated_at is the DB's own DEFAULT now() (insert branch); the re-connect
    // explicitly stamps the JVM clock's now() (the ON CONFLICT DO UPDATE branch, same asymmetry
    // as OrgWhatsAppConfigRepositoryImpl). A generous sleep keeps the comparison robust to any
    // skew between the app host's clock and the Testcontainers Postgres container's clock.
    Thread.sleep(1000);
    ConnectionStatus second = service.connect(org, "pk-2", "sk-2", "hmac-2", "ak", 222, "EGYPT");

    assertEquals("pk-2", second.publicKey());
    assertEquals(222, second.cardIntegrationId());
    assertTrue(second.updatedAt().isAfter(first.updatedAt()));

    long rowCount =
        dsl.fetchCount(dsl.selectFrom(ORG_PAYMOB_CONFIG).where(ORG_PAYMOB_CONFIG.ORG_ID.eq(org)));
    assertEquals(1, rowCount, "re-connecting must upsert, never insert a second row");

    String secretEncrypted =
        dsl.select(ORG_PAYMOB_CONFIG.SECRET_KEY_ENCRYPTED)
            .from(ORG_PAYMOB_CONFIG)
            .where(ORG_PAYMOB_CONFIG.ORG_ID.eq(org))
            .fetchOne(ORG_PAYMOB_CONFIG.SECRET_KEY_ENCRYPTED);
    assertEquals(
        "sk-2", secretBox.decrypt(secretEncrypted), "the old secret must be fully replaced");
  }

  @Test
  void disconnect_isIdempotent() {
    UUID org = createOrg();
    service.disconnect(org); // never connected — still a no-op success

    service.connect(org, "pk", "sk", "hmac", "ak", 1, "EGYPT");
    service.disconnect(org);
    service.disconnect(org); // already gone — still a no-op success

    assertFalse(service.status(org).connected());
  }

  @Test
  void connect_withNoEncryptionKeyConfigured_is409_andNothingIsWritten() {
    UUID org = createOrg();

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> serviceNoKey.connect(org, "pk", "sk", "hmac", "ak", 1, "EGYPT"));
    assertEquals(409, e.getStatusCode());

    long rowCount = dsl.fetchCount(dsl.selectFrom(ORG_PAYMOB_CONFIG));
    assertEquals(0, rowCount, "a fail-open here would store a card secret in the clear");
  }

  @Test
  void connect_blankCredentials_is400() {
    UUID org = createOrg();
    assertThrows(
        ValidationException.class, () -> service.connect(org, "", "sk", "hmac", "ak", 1, "EGYPT"));
    assertThrows(
        ValidationException.class, () -> service.connect(org, "pk", " ", "hmac", "ak", 1, "EGYPT"));
    assertThrows(
        ValidationException.class, () -> service.connect(org, "pk", "sk", null, "ak", 1, "EGYPT"));
  }

  @Test
  void connect_nonPositiveOrMissingCardIntegrationId_is400() {
    UUID org = createOrg();
    assertThrows(
        ValidationException.class,
        () -> service.connect(org, "pk", "sk", "hmac", "ak", 0, "EGYPT"));
    assertThrows(
        ValidationException.class,
        () -> service.connect(org, "pk", "sk", "hmac", "ak", -1, "EGYPT"));
    assertThrows(
        ValidationException.class,
        () -> service.connect(org, "pk", "sk", "hmac", "ak", null, "EGYPT"));
  }

  @Test
  void disconnect_withExistingPaymobCardTransactions_leavesTheLedgerUntouched() {
    UUID org = createOrg();
    service.connect(org, "pk", "sk", "hmac", "ak", 1, "EGYPT");

    // payment_transaction carries no relationship to org_paymob_config at all (no FK, no
    // cascade) — this pins that on purpose: disconnecting a gateway must never touch money that
    // already moved.
    dsl.execute(
        "insert into payment_transaction (id, org_id, provider, provider_ref, direction, amount,"
            + " verification_status, occurred_at) values (gen_random_uuid(), ?,"
            + " 'paymob_card'::payment_provider, 'txn-ref-1', 'CREDIT', 50.00, 'VERIFIED', now())",
        org);

    service.disconnect(org);

    assertFalse(service.status(org).connected());
    Integer txnCount =
        dsl.fetchOne("select count(*) from payment_transaction where org_id = ?", org)
            .get(0, Integer.class);
    assertEquals(1, txnCount, "the ledger row must survive disconnect");
  }

  @Test
  void v98_addedPaymobCardToThePaymentProviderEnum() {
    // Guards V98's `ADD VALUE IF NOT EXISTS` against a silent no-op — a pg_enum lookup, not a
    // Java-side check, because the domain PaymentProvider enum deliberately carries no constant
    // for it yet (slice 2 adds that when something first writes it).
    Boolean exists =
        dsl.fetchOne(
                "select exists (select 1 from pg_enum e join pg_type t on"
                    + " e.enumtypid = t.oid where t.typname = 'payment_provider' and"
                    + " e.enumlabel = 'paymob_card')")
            .get(0, Boolean.class);
    assertTrue(exists);
  }

  @Test
  void connect_unsupportedRegion_is400() {
    UUID org = createOrg();
    assertThrows(
        ValidationException.class, () -> service.connect(org, "pk", "sk", "hmac", "ak", 1, "KSA"));
  }

  private UUID createOrg() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, "acme").set(ORG.SLUG, "acme-" + id).execute();
    return id;
  }
}
