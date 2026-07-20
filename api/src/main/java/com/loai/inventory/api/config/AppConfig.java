package com.loai.inventory.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.job.NotificationDeliverySweeperJob;
import com.loai.inventory.api.job.OrderTtlSweeperJob;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.DataSourceFactory;
import com.loai.inventory.common.RedisFactory;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.repository.AppUserMagicTokenRepositoryFactory;
import com.loai.inventory.domain.repository.CategoryRepositoryFactory;
import com.loai.inventory.domain.repository.CreditNoteRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerAddressRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerMagicTokenRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.FulfillmentRepositoryFactory;
import com.loai.inventory.domain.repository.ImpersonationEventRepository;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryReservationRepositoryFactory;
import com.loai.inventory.domain.repository.ListingCommentRepositoryFactory;
import com.loai.inventory.domain.repository.ListingReviewRepositoryFactory;
import com.loai.inventory.domain.repository.NotificationPreferenceRepositoryFactory;
import com.loai.inventory.domain.repository.NotificationRepositoryFactory;
import com.loai.inventory.domain.repository.NumberSequenceReconciliationRepositoryFactory;
import com.loai.inventory.domain.repository.OrgHealthRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.domain.repository.PlatformAuditRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.RefundAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.RefundRepositoryFactory;
import com.loai.inventory.domain.repository.ReportRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontBannerRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontPageRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.repository.AppUserMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.ImpersonationEventRepositoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingCommentRepositoryFactoryImpl;
import com.loai.inventory.repository.ListingReviewRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationPreferenceRepositoryFactoryImpl;
import com.loai.inventory.repository.NotificationRepositoryFactoryImpl;
import com.loai.inventory.repository.NumberSequenceReconciliationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.ReportRepositoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontPageRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.CategoryService;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.CustomerService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.ListingCommentService;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.MemberService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.NumberSequenceReconciliationService;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.OrderExpiryService;
import com.loai.inventory.service.OrgHealthService;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.PaymentDisputeService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PresignedOgImageSource;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReportService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.StorefrontBannerService;
import com.loai.inventory.service.StorefrontPageService;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.auth.AccountService;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.CredentialTokenService;
import com.loai.inventory.service.auth.CustomerAuthService;
import com.loai.inventory.service.auth.CustomerOtpStore;
import com.loai.inventory.service.auth.CustomerSessionStore;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.PresignedLogoSource;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.email.EmailSenderFactory;
import com.loai.inventory.service.platform.OrgStatusService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformOrgService;
import com.loai.inventory.service.platform.UserAdminService;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
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
  private static final long DEFAULT_IMPERSONATION_TTL_MILLIS = 5 * 60 * 1000L;

  // Infrastructure
  public final HikariDataSource dataSource;
  public final JedisPool jedisPool;
  public final DSLContext dsl;
  public final ObjectMapper objectMapper;
  public final JwtUtil jwtUtil;
  public final JwtUtil customerJwtUtil;
  public final ObjectStorage objectStorage;
  public final EmailSender emailSender;
  public final boolean secureCookies;
  public final int customerRefreshMaxAgeSeconds;

  /** Allowlisted origins (CORS_ALLOWED_ORIGINS) — reused by the portal CSRF Origin check. */
  public final java.util.Set<String> corsAllowedOrigins;

  // Repositories (domain interface type, not the impl)
  public final ProductRepository productRepository;
  public final UserRepository userRepository;
  public final ImpersonationEventRepository impersonationEventRepository;
  public final CategoryRepositoryFactory categoryRepositoryFactory;
  public final NotificationRepositoryFactory notificationRepositoryFactory;
  public final NotificationPreferenceRepositoryFactory notificationPreferenceRepositoryFactory;
  public final CustomerMagicTokenRepositoryFactory customerMagicTokenRepositoryFactory;
  public final AppUserMagicTokenRepositoryFactory appUserMagicTokenRepositoryFactory;
  public final ProductListingRepositoryFactory productListingRepositoryFactory;
  public final StorefrontBannerRepositoryFactory storefrontBannerRepositoryFactory;
  public final StorefrontPageRepositoryFactory storefrontPageRepositoryFactory;
  public final CustomerRepositoryFactory customerRepositoryFactory;
  public final CustomerAddressRepositoryFactory customerAddressRepositoryFactory;
  public final ListingReviewRepositoryFactory listingReviewRepositoryFactory;
  public final ListingCommentRepositoryFactory listingCommentRepositoryFactory;
  public final InventoryRepositoryFactory inventoryRepositoryFactory;
  public final InventoryLogRepositoryFactory inventoryLogRepositoryFactory;
  public final UserRepositoryFactory userRepositoryFactory;
  public final OrgRepositoryFactory orgRepositoryFactory;
  public final OrgHealthRepository orgHealthRepository;
  public final ReportRepository reportRepository;
  public final PlatformAuditRepositoryFactory platformAuditRepositoryFactory;
  public final SalesOrderRepositoryFactory salesOrderRepositoryFactory;
  public final InventoryReservationRepositoryFactory inventoryReservationRepositoryFactory;
  public final PaymentTransactionRepositoryFactory paymentTransactionRepositoryFactory;
  public final PaymentRepositoryFactory paymentRepositoryFactory;
  public final FulfillmentRepositoryFactory fulfillmentRepositoryFactory;
  public final SalesInvoiceRepositoryFactory salesInvoiceRepositoryFactory;
  public final PaymentAllocationRepositoryFactory paymentAllocationRepositoryFactory;
  public final CreditNoteRepositoryFactory creditNoteRepositoryFactory;
  public final RefundRepositoryFactory refundRepositoryFactory;
  public final RefundAllocationRepositoryFactory refundAllocationRepositoryFactory;
  public final NumberSequenceReconciliationRepositoryFactory
      numberSequenceReconciliationRepositoryFactory;

  // Services
  public final RefreshTokenStore refreshTokenStore;
  public final AuthService authService;
  public final CredentialTokenService credentialTokenService;
  public final AuthMailer authMailer;
  public final AccountService accountService;
  public final OrgService orgService;
  public final OrgHealthService orgHealthService;
  public final ReportService reportService;
  public final MemberService memberService;
  public final PlatformAuditService platformAuditService;
  public final OrgStatusService orgStatusService;
  public final PlatformOrgService platformOrgService;
  public final UserAdminService userAdminService;
  public final ProductService productService;
  public final CategoryService categoryService;
  public final NotificationService notificationService;
  public final MagicLinkService magicLinkService;
  public final CustomerOtpStore customerOtpStore;
  public final CustomerSessionStore customerSessionStore;
  public final CustomerAuthService customerAuthService;
  public final CustomerPortalService customerPortalService;
  public final ListingReviewService listingReviewService;
  public final ListingCommentService listingCommentService;
  public final ProductListingService productListingService;
  public final StorefrontBannerService storefrontBannerService;
  public final StorefrontPageService storefrontPageService;
  public final StorefrontService storefrontService;
  public final CustomerService customerService;
  public final InventoryService inventoryService;
  public final ReservationService reservationService;
  public final SalesOrderService salesOrderService;
  public final OrderExpiryService orderExpiryService;
  public final NumberSequenceReconciliationService numberSequenceReconciliationService;
  public final PaymentService paymentService;
  public final PaymentDisputeService paymentDisputeService;
  public final PaymentTransactionService paymentTransactionService;
  public final InvoiceService invoiceService;
  public final InvoiceAdminService invoiceAdminService;
  public final FulfillmentService fulfillmentService;
  public final CreditNoteService creditNoteService;
  public final RefundService refundService;
  public final OrderCancellationService orderCancellationService;
  public final DocumentRenderService documentRenderService;

  // Background JobRunr jobs + their lifecycle flag.
  public final OrderTtlSweeperJob orderTtlSweeperJob;
  public final NotificationDeliverySweeperJob notificationDeliverySweeperJob;
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

    // Customer-portal plane: a SECOND signing key (never the staff JWT_SECRET), so a customer token
    // cannot verify on the staff plane and a leaked customer secret leaves staff untouched.
    String customerJwtSecret = System.getenv("CUSTOMER_JWT_SECRET");
    if (customerJwtSecret == null || customerJwtSecret.isBlank()) {
      throw new RuntimeException(
          "CUSTOMER_JWT_SECRET env var is required and must be at least 32 bytes after Base64"
              + " decode");
    }
    long customerRefreshTtlDays = parseLong(System.getenv("CUSTOMER_REFRESH_TTL_DAYS"), 30L);
    this.customerRefreshMaxAgeSeconds = (int) (customerRefreshTtlDays * 24 * 3600);
    this.corsAllowedOrigins = resolveAllowedOrigins();

    this.dataSource = DataSourceFactory.build();
    runMigrations();
    this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    this.objectMapper = ObjectMapperProvider.build();

    this.jedisPool = RedisFactory.build();
    // Staff hardening: stamp aud="staff" on every staff token going forward (the JwtAuthFilter
    // accepts staff or a missing aud, rejects "customer").
    this.jwtUtil = new JwtUtil(jwtSecret, accessTtl, "staff");
    this.customerJwtUtil = new JwtUtil(customerJwtSecret, accessTtl, "customer");
    this.objectStorage = ObjectStorageFactory.build();
    this.emailSender = EmailSenderFactory.build();

    this.productRepository = new ProductRepositoryImpl(dsl);
    this.userRepository = new UserRepositoryImpl(dsl);
    this.impersonationEventRepository = new ImpersonationEventRepositoryImpl(dsl);
    this.categoryRepositoryFactory = new CategoryRepositoryFactoryImpl();
    this.notificationRepositoryFactory = new NotificationRepositoryFactoryImpl();
    this.notificationPreferenceRepositoryFactory =
        new NotificationPreferenceRepositoryFactoryImpl();
    this.customerMagicTokenRepositoryFactory = new CustomerMagicTokenRepositoryFactoryImpl();
    this.appUserMagicTokenRepositoryFactory = new AppUserMagicTokenRepositoryFactoryImpl();
    this.productListingRepositoryFactory = new ProductListingRepositoryFactoryImpl();
    this.storefrontBannerRepositoryFactory = new StorefrontBannerRepositoryFactoryImpl();
    this.storefrontPageRepositoryFactory = new StorefrontPageRepositoryFactoryImpl();
    this.customerRepositoryFactory = new CustomerRepositoryFactoryImpl();
    this.customerAddressRepositoryFactory = new CustomerAddressRepositoryFactoryImpl();
    this.listingReviewRepositoryFactory = new ListingReviewRepositoryFactoryImpl();
    this.listingCommentRepositoryFactory = new ListingCommentRepositoryFactoryImpl();
    this.inventoryRepositoryFactory = new InventoryRepositoryFactoryImpl();
    this.inventoryLogRepositoryFactory = new InventoryLogRepositoryFactoryImpl();
    this.userRepositoryFactory = new UserRepositoryFactoryImpl();
    this.orgRepositoryFactory = new OrgRepositoryFactoryImpl();
    this.orgHealthRepository = new OrgHealthRepositoryImpl(dsl);
    this.reportRepository = new ReportRepositoryImpl(dsl);
    this.platformAuditRepositoryFactory = new PlatformAuditRepositoryFactoryImpl();
    this.salesOrderRepositoryFactory = new SalesOrderRepositoryFactoryImpl();
    this.inventoryReservationRepositoryFactory = new InventoryReservationRepositoryFactoryImpl();
    this.paymentTransactionRepositoryFactory = new PaymentTransactionRepositoryFactoryImpl();
    this.paymentRepositoryFactory = new PaymentRepositoryFactoryImpl();
    this.fulfillmentRepositoryFactory = new FulfillmentRepositoryFactoryImpl();
    this.salesInvoiceRepositoryFactory = new SalesInvoiceRepositoryFactoryImpl();
    this.paymentAllocationRepositoryFactory = new PaymentAllocationRepositoryFactoryImpl();
    this.creditNoteRepositoryFactory = new CreditNoteRepositoryFactoryImpl();
    this.refundRepositoryFactory = new RefundRepositoryFactoryImpl();
    this.refundAllocationRepositoryFactory = new RefundAllocationRepositoryFactoryImpl();
    this.numberSequenceReconciliationRepositoryFactory =
        new NumberSequenceReconciliationRepositoryFactoryImpl();

    long impersonationTtl =
        parseLong(System.getenv("IMPERSONATION_TTL_MILLIS"), DEFAULT_IMPERSONATION_TTL_MILLIS);
    this.refreshTokenStore = new RefreshTokenStore(jedisPool);
    // Customer-portal stores — an isolated Redis namespace mirroring the staff session store. The
    // OTP challenge and customer sessions both self-expire in Redis (no DB tables).
    this.customerOtpStore = new CustomerOtpStore(jedisPool);
    this.customerSessionStore =
        new CustomerSessionStore(jedisPool, customerRefreshTtlDays * 24 * 3600);
    this.authService =
        new AuthService(
            userRepository,
            refreshTokenStore,
            jwtUtil,
            impersonationEventRepository,
            impersonationTtl);
    // Base URL for emailed auth links (shared with MagicLinkService below).
    String publicBaseUrl = getenvOrDefault("PUBLIC_BASE_URL", "http://localhost:8080");
    long resetTtlMinutes = parseLong(System.getenv("PASSWORD_RESET_TTL_MINUTES"), 120L);
    long inviteTtlDays = parseLong(System.getenv("INVITE_TTL_DAYS"), 7L);
    this.credentialTokenService =
        new CredentialTokenService(
            dsl,
            appUserMagicTokenRepositoryFactory,
            publicBaseUrl,
            Duration.ofMinutes(resetTtlMinutes),
            Duration.ofDays(inviteTtlDays));
    this.authMailer = new AuthMailer(emailSender);
    this.accountService =
        new AccountService(
            dsl,
            userRepositoryFactory,
            orgRepositoryFactory,
            credentialTokenService,
            authMailer,
            authService);
    this.orgService =
        new OrgService(dsl, orgRepositoryFactory, userRepositoryFactory, objectStorage);
    this.orgHealthService = new OrgHealthService(orgHealthRepository);
    this.reportService = new ReportService(reportRepository);
    this.memberService = new MemberService(dsl, userRepositoryFactory, authService);
    this.platformAuditService = new PlatformAuditService(dsl, platformAuditRepositoryFactory);
    this.orgStatusService = new OrgStatusService(jedisPool, dsl, orgRepositoryFactory);
    // Enforce org suspension on the hot authorization path, backed by the Redis-mirrored gate.
    AuthzHelper.configureOrgStatusGate(orgStatusService::isActive);
    this.platformOrgService =
        new PlatformOrgService(
            dsl,
            orgRepositoryFactory,
            userRepositoryFactory,
            orgHealthRepository,
            platformAuditService,
            orgStatusService,
            credentialTokenService,
            authMailer);
    this.userAdminService =
        new UserAdminService(
            dsl, userRepositoryFactory, orgRepositoryFactory, authService, platformAuditService);
    this.productService = new ProductService(productRepository, dsl);
    this.categoryService =
        new CategoryService(dsl, categoryRepositoryFactory, orgRepositoryFactory);
    // MagicLinkService is built before NotificationService — the producer mints an unsubscribe
    // link for every customer email through it. (publicBaseUrl was resolved above for auth links.)
    long magicTtlDays = parseLong(System.getenv("MAGIC_LINK_TTL_DAYS"), 30L);
    this.magicLinkService =
        new MagicLinkService(
            dsl,
            customerMagicTokenRepositoryFactory,
            orgRepositoryFactory,
            publicBaseUrl,
            Duration.ofDays(magicTtlDays));
    int emailMaxAttempts =
        (int)
            parseLong(
                System.getenv("EMAIL_MAX_ATTEMPTS"),
                NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS);
    this.notificationService =
        new NotificationService(
            dsl,
            notificationRepositoryFactory,
            userRepositoryFactory,
            customerRepositoryFactory,
            notificationPreferenceRepositoryFactory,
            emailSender,
            magicLinkService,
            emailMaxAttempts);
    // Portal auth service — the OTP code is sent SYNCHRONOUSLY through the EmailSender (not the
    // NotificationService pipeline): a login code must not be suppressible by a customer's email
    // opt-out, nor wait on the delivery sweeper. The per-email OTP-send throttle shares the
    // PORTAL_OTP_REQUEST_LIMIT budget over a 60s window (the per-IP twin lives in RateLimitFilter).
    int portalOtpRequestLimit = (int) parseLong(System.getenv("PORTAL_OTP_REQUEST_LIMIT"), 5L);
    this.customerAuthService =
        new CustomerAuthService(
            dsl,
            customerRepositoryFactory,
            orgRepositoryFactory,
            customerOtpStore,
            customerSessionStore,
            customerJwtUtil,
            emailSender,
            portalOtpRequestLimit,
            60);
    this.productListingService =
        new ProductListingService(
            dsl, productListingRepositoryFactory, orgRepositoryFactory, objectStorage);
    this.storefrontBannerService =
        new StorefrontBannerService(
            dsl,
            storefrontBannerRepositoryFactory,
            orgRepositoryFactory,
            categoryRepositoryFactory,
            productListingRepositoryFactory,
            objectStorage);
    this.storefrontPageService =
        new StorefrontPageService(dsl, storefrontPageRepositoryFactory, orgRepositoryFactory);
    // storefrontService is constructed after salesOrderService below — its anonymous checkout
    // (public_checkout.md) delegates to SalesOrderService.placeStorefrontOrder.
    this.customerService = new CustomerService(dsl, customerRepositoryFactory);
    this.inventoryService =
        new InventoryService(
            dsl,
            inventoryRepositoryFactory,
            inventoryLogRepositoryFactory,
            inventoryReservationRepositoryFactory,
            productRepository,
            salesOrderRepositoryFactory);
    this.reservationService =
        new ReservationService(
            inventoryRepositoryFactory,
            inventoryReservationRepositoryFactory,
            inventoryLogRepositoryFactory);
    this.orderExpiryService =
        new OrderExpiryService(dsl, salesOrderRepositoryFactory, reservationService);
    this.numberSequenceReconciliationService =
        new NumberSequenceReconciliationService(dsl, numberSequenceReconciliationRepositoryFactory);
    // NotificationService + MagicLinkService are both constructed above — the ORDER_PAID producer
    // (stories/notify_order_paid.md) mints a fresh order-view link per customer email.
    this.paymentService =
        new PaymentService(
            dsl,
            paymentRepositoryFactory,
            salesOrderRepositoryFactory,
            paymentTransactionRepositoryFactory,
            refundRepositoryFactory,
            notificationService,
            magicLinkService);
    this.paymentDisputeService =
        new PaymentDisputeService(
            dsl,
            paymentRepositoryFactory,
            refundAllocationRepositoryFactory,
            paymentAllocationRepositoryFactory,
            salesInvoiceRepositoryFactory);
    // RefundService is built before PaymentTransactionService and FulfillmentService: the
    // orphan-refund path (PaymentTransactionService.refundOrphan) and the failed-fulfillment
    // refund path (FulfillmentService.refundFailed) both reuse
    // RefundService.createDirectPendingInTx.
    this.refundService =
        new RefundService(
            dsl,
            refundRepositoryFactory,
            refundAllocationRepositoryFactory,
            creditNoteRepositoryFactory,
            paymentRepositoryFactory,
            paymentAllocationRepositoryFactory,
            paymentTransactionRepositoryFactory,
            orgRepositoryFactory,
            salesOrderRepositoryFactory);
    this.paymentTransactionService =
        new PaymentTransactionService(
            dsl,
            paymentTransactionRepositoryFactory,
            paymentRepositoryFactory,
            paymentService,
            refundService);
    this.invoiceService =
        new InvoiceService(
            salesInvoiceRepositoryFactory,
            paymentRepositoryFactory,
            paymentAllocationRepositoryFactory);
    this.invoiceAdminService =
        new InvoiceAdminService(
            dsl,
            salesInvoiceRepositoryFactory,
            paymentAllocationRepositoryFactory,
            creditNoteRepositoryFactory,
            salesOrderRepositoryFactory,
            customerRepositoryFactory,
            invoiceService);
    this.fulfillmentService =
        new FulfillmentService(
            dsl,
            fulfillmentRepositoryFactory,
            salesOrderRepositoryFactory,
            inventoryRepositoryFactory,
            inventoryReservationRepositoryFactory,
            inventoryLogRepositoryFactory,
            paymentRepositoryFactory,
            invoiceService,
            refundService,
            reservationService);
    this.salesOrderService =
        new SalesOrderService(
            dsl,
            salesOrderRepositoryFactory,
            orgRepositoryFactory,
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            notificationService,
            magicLinkService);
    this.storefrontService =
        new StorefrontService(
            dsl,
            orgRepositoryFactory,
            productListingRepositoryFactory,
            categoryRepositoryFactory,
            inventoryRepositoryFactory,
            storefrontBannerRepositoryFactory,
            listingReviewRepositoryFactory,
            objectStorage,
            salesOrderService,
            new PresignedOgImageSource(objectStorage));
    // After salesOrderService — the P6 authenticated checkout delegates placement to it.
    this.customerPortalService =
        new CustomerPortalService(
            dsl,
            customerRepositoryFactory,
            salesOrderRepositoryFactory,
            salesInvoiceRepositoryFactory,
            customerAddressRepositoryFactory,
            productListingRepositoryFactory,
            orgRepositoryFactory,
            fulfillmentRepositoryFactory,
            salesOrderService);
    // Reviews (slice R1): portal write + staff moderation + public read, one service.
    this.listingReviewService =
        new ListingReviewService(
            dsl,
            listingReviewRepositoryFactory,
            productListingRepositoryFactory,
            customerRepositoryFactory,
            orgRepositoryFactory);
    // Comments (slice R2): asks + the answer worklist; the reply notifies through P5 in-txn.
    this.listingCommentService =
        new ListingCommentService(
            dsl,
            listingCommentRepositoryFactory,
            productListingRepositoryFactory,
            customerRepositoryFactory,
            orgRepositoryFactory,
            notificationService);
    this.creditNoteService =
        new CreditNoteService(
            dsl,
            creditNoteRepositoryFactory,
            salesInvoiceRepositoryFactory,
            refundRepositoryFactory,
            orgRepositoryFactory);
    this.orderCancellationService =
        new OrderCancellationService(
            dsl,
            salesOrderRepositoryFactory,
            paymentRepositoryFactory,
            fulfillmentRepositoryFactory,
            reservationService,
            refundService);
    this.documentRenderService =
        new DocumentRenderService(
            orgService,
            invoiceAdminService,
            creditNoteService,
            paymentService,
            new PresignedLogoSource(objectStorage));

    int batchLimit = (int) parseLong(System.getenv("ORDER_SWEEPER_BATCH_LIMIT"), 200L);
    this.orderTtlSweeperJob = new OrderTtlSweeperJob(orderExpiryService, batchLimit);

    int notifyBatchLimit = (int) parseLong(System.getenv("NOTIFICATION_SWEEPER_BATCH_LIMIT"), 200L);
    this.notificationDeliverySweeperJob =
        new NotificationDeliverySweeperJob(notificationService, notifyBatchLimit);

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
            if (type.isInstance(notificationDeliverySweeperJob)) {
              return type.cast(notificationDeliverySweeperJob);
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

    String notifyCron = getenvOrDefault("NOTIFICATION_SWEEPER_INTERVAL", "*/10 * * * * *");
    scheduler.<NotificationDeliverySweeperJob>scheduleRecurrently(
        "notification-delivery-sweeper", notifyCron, NotificationDeliverySweeperJob::run);
    log.info("Notification-delivery sweeper scheduled (cron='{}')", notifyCron);
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
    if (objectStorage != null) objectStorage.close();
    if (jedisPool != null) jedisPool.close();
    if (dataSource != null) dataSource.close();
  }

  private static String getenvOrDefault(String key, String defaultValue) {
    String v = System.getenv(key);
    return (v == null || v.isBlank()) ? defaultValue : v;
  }

  /**
   * The CORS_ALLOWED_ORIGINS allowlist (same default as CorsFilter) — trimmed, for the portal CSRF
   * Origin check.
   */
  private static java.util.Set<String> resolveAllowedOrigins() {
    String env = System.getenv("CORS_ALLOWED_ORIGINS");
    if (env == null || env.isBlank()) {
      return java.util.Set.of("http://localhost:3000", "http://localhost:5173");
    }
    java.util.Set<String> origins = new java.util.HashSet<>();
    for (String o : env.split(",")) {
      String t = o.trim();
      if (!t.isEmpty()) {
        origins.add(t);
      }
    }
    return java.util.Set.copyOf(origins);
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
