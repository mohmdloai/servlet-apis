package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_INTENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.crypto.PaymobSignature;
import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.model.PaymentIntent;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgPaymobConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentIntentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.OrderExpiryService;
import com.loai.inventory.service.OrgPaymobService;
import com.loai.inventory.service.PaymentIntentService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymobWebhookService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.paymob.PaymobCallback;
import com.loai.inventory.service.paymob.PaymobClient;
import com.loai.inventory.service.platform.OrgMilestoneService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;

/**
 * The slice-2 wiring shared by {@link PaymobWebhookIT} and {@link PaymobPayIT}: every real service
 * against the real jOOQ repositories, a {@link SecretBox} with a test key, a Paymob client the test
 * chooses, and the seeding/signing helpers. No Tomcat/Redis/JWT.
 */
final class PaymobFixture {

  static final String HMAC_SECRET = "test-hmac-secret-0123456789ABCDEF";
  static final String PUBLIC_KEY = "egy_pk_test_fixture";
  static final String SECRET_KEY = "egy_sk_test_fixture";
  static final int INTEGRATION_ID = 4938201;
  static final String PUBLIC_API_URL = "https://api.test.local";
  static final String PUBLIC_BASE_URL = "https://store.test.local";
  static final Duration INTENT_TTL = Duration.ofMinutes(20);

  final DSLContext dsl;
  final ObjectMapper mapper = ObjectMapperProvider.build();
  final SecretBox secretBox =
      SecretBox.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
  final OrgPaymobService orgPaymobService;
  final PaymentService paymentService;
  final PaymentTransactionService paymentTransactionService;
  final PaymobWebhookService webhookService;
  final OrderExpiryService orderExpiryService;
  final PaymentIntentService intentService;
  final FakePaymobClient fakePaymob = new FakePaymobClient();

  private final AtomicInteger orderSeq = new AtomicInteger(1);

  PaymobFixture(DSLContext dsl) {
    this(dsl, null);
  }

  /** {@code client} null → the canned {@link FakePaymobClient}. */
  PaymobFixture(DSLContext dsl, PaymobClient client) {
    this.dsl = dsl;
    OrgPaymobConfigRepositoryFactoryImpl configRepo = new OrgPaymobConfigRepositoryFactoryImpl();
    this.orgPaymobService = new OrgPaymobService(dsl, configRepo, secretBox);
    this.paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            TestWiring.notificationService(dsl),
            TestWiring.magicLinkService(dsl),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    RefundService refundService =
        new RefundService(
            dsl,
            new RefundRepositoryFactoryImpl(),
            new RefundAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl());
    this.paymentTransactionService =
        new PaymentTransactionService(
            dsl,
            new PaymentTransactionRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            paymentService,
            refundService,
            TestWiring.storage(),
            new CustomerRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl());
    this.webhookService =
        new PaymobWebhookService(
            dsl,
            mapper,
            configRepo,
            secretBox,
            new PaymentIntentRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            paymentService);
    this.orderExpiryService =
        new OrderExpiryService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new ReservationService(
                new InventoryRepositoryFactoryImpl(),
                new InventoryReservationRepositoryFactoryImpl(),
                new InventoryLogRepositoryFactoryImpl()),
            new PaymentTransactionRepositoryFactoryImpl());
    this.intentService =
        new PaymentIntentService(
            dsl,
            new PaymentIntentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new CustomerRepositoryFactoryImpl(),
            configRepo,
            secretBox,
            client == null ? fakePaymob : client,
            PUBLIC_API_URL,
            PUBLIC_BASE_URL,
            INTENT_TTL);
  }

  /** Answers every intention with fresh, distinct Paymob handles and remembers the last request. */
  static final class FakePaymobClient implements PaymobClient {
    private final AtomicInteger seq = new AtomicInteger(1);
    volatile IntentionRequest lastRequest;
    volatile String lastSecretKey;

    @Override
    public IntentionResult createIntention(
        OrgPaymobConfig config, String secretKey, IntentionRequest req) {
      lastRequest = req;
      lastSecretKey = secretKey;
      int n = seq.getAndIncrement();
      return new IntentionResult("int_" + n, String.valueOf(555000100 + n), "cs_" + n);
    }
  }

  // seeding

  void truncate() {
    dsl.execute(
        "TRUNCATE payment_intent, payment, payment_transaction, sales_order_line, sales_order,"
            + " customer, product, org_paymob_config, app_user, org RESTART IDENTITY CASCADE");
  }

  UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id.toString().substring(0, 8))
        .execute();
    return id;
  }

  UUID createCustomer(UUID org, String name, String email, String phone) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, name)
        .set(CUSTOMER.EMAIL, email)
        .set(CUSTOMER.PHONE, phone)
        .execute();
    return id;
  }

  void connect(UUID org) {
    orgPaymobService.connect(org, PUBLIC_KEY, SECRET_KEY, HMAC_SECRET, INTEGRATION_ID, "EGYPT");
  }

  record Order(UUID id, String number) {}

  Order seedPendingOrder(UUID orgId, UUID customerId, String grandTotal) {
    return seedOrder(orgId, customerId, grandTotal, "0.00", OrderStatus.PENDING_PAYMENT);
  }

  Order seedOrder(
      UUID orgId, UUID customerId, String grandTotal, String prepaid, OrderStatus status) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-2026-" + String.format("%05d", orderSeq.getAndIncrement());
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.CUSTOMER_ID, customerId)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, status)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.PREPAID_AMOUNT, new BigDecimal(prepaid))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .set(SALES_ORDER.EXPIRES_AT, OffsetDateTime.now(ZoneOffset.UTC).plusHours(1))
        .execute();
    return new Order(orderId, number);
  }

  /** Mint an intent through the real service (with whichever client the fixture was built on). */
  PaymentIntent mintIntent(UUID org, Order order, UUID customerId) {
    return intentService.pay(org, order.id(), customerId, "tok-" + order.number()).intent();
  }

  // the callback

  /** A callback builder: the settled happy path unless a field is overridden. */
  ObjectNode callback(PaymentIntent intent, long txnId) {
    ObjectNode root = mapper.createObjectNode();
    root.put("type", "TRANSACTION");
    ObjectNode obj = root.putObject("obj");
    obj.put("id", txnId);
    obj.put("pending", false);
    obj.put("amount_cents", intent.getAmount().movePointRight(2).longValueExact());
    obj.put("success", true);
    obj.put("is_auth", false);
    obj.put("is_capture", false);
    obj.put("is_standalone_payment", true);
    obj.put("is_voided", false);
    obj.put("is_refunded", false);
    obj.put("is_3d_secure", true);
    obj.put("integration_id", INTEGRATION_ID);
    obj.put("profile_id", 1122);
    obj.put("has_parent_transaction", false);
    ObjectNode order = obj.putObject("order");
    order.put("id", Long.parseLong(intent.getPaymobOrderId()));
    order.put("merchant_order_id", intent.getSpecialReference());
    order.put("amount_cents", intent.getAmount().movePointRight(2).longValueExact());
    order.put("currency", intent.getCurrency());
    obj.put("created_at", "2026-09-14T08:01:02.123456");
    obj.put("currency", intent.getCurrency());
    ObjectNode source = obj.putObject("source_data");
    source.putNull("pan");
    source.put("type", "card");
    source.put("sub_type", "MasterCard");
    obj.put("api_source", "IFRAME");
    obj.put("error_occured", false);
    obj.put("owner", 3344);
    obj.put("is_void", false);
    obj.put("is_refund", false);
    ObjectNode claims = obj.putObject("payment_key_claims");
    ObjectNode extra = claims.putObject("extra");
    extra.put("org_id", intent.getOrgId().toString());
    extra.put("sales_order_id", intent.getSalesOrderId().toString());
    extra.put("intent_id", intent.getId().toString());
    return root;
  }

  String body(ObjectNode callback) {
    try {
      return mapper.writeValueAsString(callback);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  String sign(String body) {
    return sign(body, HMAC_SECRET);
  }

  String sign(String body, String secret) {
    PaymobCallback cb = PaymobCallback.parse(mapper, body);
    return PaymobSignature.sign(PaymobSignature.canonical(cb.signedValues()), secret);
  }

  /** Deliver a signed callback for {@code org}. */
  PaymobWebhookService.Outcome deliver(UUID org, ObjectNode callback) {
    String body = body(callback);
    return webhookService.handle(org, body, sign(body));
  }

  // reads

  String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }

  BigDecimal prepaid(UUID orderId) {
    return dsl.select(SALES_ORDER.PREPAID_AMOUNT)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.PREPAID_AMOUNT);
  }

  int txnCount(UUID org) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_TRANSACTION).where(PAYMENT_TRANSACTION.ORG_ID.eq(org)));
  }

  int paymentCount(UUID orderId) {
    return dsl.fetchCount(dsl.selectFrom(PAYMENT).where(PAYMENT.SALES_ORDER_ID.eq(orderId)));
  }

  int intentCount(UUID orderId) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_INTENT).where(PAYMENT_INTENT.SALES_ORDER_ID.eq(orderId)));
  }

  org.jooq.Record txnByRef(String providerRef) {
    return dsl.selectFrom(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.PROVIDER_REF.eq(providerRef))
        .fetchOne();
  }

  org.jooq.Record intentRow(UUID intentId) {
    return dsl.selectFrom(PAYMENT_INTENT).where(PAYMENT_INTENT.ID.eq(intentId)).fetchOne();
  }

  String intentStatus(UUID intentId) {
    return intentRow(intentId).get(PAYMENT_INTENT.STATUS);
  }
}
