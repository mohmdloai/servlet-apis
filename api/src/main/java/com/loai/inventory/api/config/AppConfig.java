package com.loai.inventory.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.job.NotificationDeliverySweeperJob;
import com.loai.inventory.api.job.OrderTtlSweeperJob;
import com.loai.inventory.api.job.UnverifiedAccountPurgeJob;
import com.loai.inventory.api.servlet.AuthzHelper;
import com.loai.inventory.common.DataSourceFactory;
import com.loai.inventory.common.RedisFactory;
import com.loai.inventory.common.crypto.SecretBox;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.common.storage.ObjectStorageFactory;
import com.loai.inventory.domain.repository.AppUserMagicTokenRepositoryFactory;
import com.loai.inventory.domain.repository.CategoryRepositoryFactory;
import com.loai.inventory.domain.repository.CollectionRepositoryFactory;
import com.loai.inventory.domain.repository.CouponRepositoryFactory;
import com.loai.inventory.domain.repository.CreditNoteRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerAddressRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerMagicTokenRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerWishlistRepositoryFactory;
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
import com.loai.inventory.domain.repository.OrgMilestoneRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.OrgTimelineRepositoryFactory;
import com.loai.inventory.domain.repository.OrgWhatsAppConfigRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.domain.repository.PlatformAuditRepositoryFactory;
import com.loai.inventory.domain.repository.PlatformFunnelRepositoryFactory;
import com.loai.inventory.domain.repository.PlatformQueueRepositoryFactory;
import com.loai.inventory.domain.repository.PlatformSearchRepositoryFactory;
import com.loai.inventory.domain.repository.PlatformStatsRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import com.loai.inventory.domain.repository.ProductRepository;
import com.loai.inventory.domain.repository.ProductRepositoryFactory;
import com.loai.inventory.domain.repository.ProductVariantRepositoryFactory;
import com.loai.inventory.domain.repository.PushSubscriptionRepositoryFactory;
import com.loai.inventory.domain.repository.RefundAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.RefundRepositoryFactory;
import com.loai.inventory.domain.repository.ReportRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontBannerRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontCrawlRepositoryFactory;
import com.loai.inventory.domain.repository.StorefrontPageRepositoryFactory;
import com.loai.inventory.domain.repository.UserRepository;
import com.loai.inventory.domain.repository.UserRepositoryFactory;
import com.loai.inventory.repository.AppUserMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CashMovementRepositoryFactoryImpl;
import com.loai.inventory.repository.CashShiftRepositoryFactoryImpl;
import com.loai.inventory.repository.CategoryRepositoryFactoryImpl;
import com.loai.inventory.repository.CollectionRepositoryFactoryImpl;
import com.loai.inventory.repository.CouponRepositoryFactoryImpl;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerWishlistRepositoryFactoryImpl;
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
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgTimelineRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgWhatsAppConfigRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformFunnelRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformQueueRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformSearchRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformStatsRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductListingRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryFactoryImpl;
import com.loai.inventory.repository.ProductRepositoryImpl;
import com.loai.inventory.repository.ProductVariantRepositoryFactoryImpl;
import com.loai.inventory.repository.PushSubscriptionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.ReportRepositoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontBannerRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontCrawlRepositoryFactoryImpl;
import com.loai.inventory.repository.StorefrontPageRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.CashShiftService;
import com.loai.inventory.service.CategoryService;
import com.loai.inventory.service.CollectionService;
import com.loai.inventory.service.CounterReturnService;
import com.loai.inventory.service.CouponService;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.CustomerService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InventoryService;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.ListingCommentService;
import com.loai.inventory.service.ListingReviewService;
import com.loai.inventory.service.LowStockNotifier;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.MemberService;
import com.loai.inventory.service.NotificationService;
import com.loai.inventory.service.NumberSequenceReconciliationService;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.OrderExpiryService;
import com.loai.inventory.service.OrgHealthService;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.OrgWhatsAppService;
import com.loai.inventory.service.PaymentDisputeService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PresignedOgImageSource;
import com.loai.inventory.service.ProductListingService;
import com.loai.inventory.service.ProductService;
import com.loai.inventory.service.ProductVariantService;
import com.loai.inventory.service.PushSubscriptionService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReportService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.StorefrontBannerService;
import com.loai.inventory.service.StorefrontPageService;
import com.loai.inventory.service.StorefrontService;
import com.loai.inventory.service.WishlistService;
import com.loai.inventory.service.auth.AccountService;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.CredentialTokenService;
import com.loai.inventory.service.auth.CustomerAuthService;
import com.loai.inventory.service.auth.CustomerOtpStore;
import com.loai.inventory.service.auth.CustomerSessionStore;
import com.loai.inventory.service.auth.LoginThrottle;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.PresignedLogoSource;
import com.loai.inventory.service.email.CachingMxResolver;
import com.loai.inventory.service.email.DnsJavaMxResolver;
import com.loai.inventory.service.email.EmailGate;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.email.EmailSenderFactory;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.loai.inventory.service.platform.OrgStatusService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformFunnelService;
import com.loai.inventory.service.platform.PlatformGrowthService;
import com.loai.inventory.service.platform.PlatformOrgService;
import com.loai.inventory.service.platform.PlatformOrgTimelineService;
import com.loai.inventory.service.platform.PlatformOverviewService;
import com.loai.inventory.service.platform.PlatformQueueService;
import com.loai.inventory.service.platform.PlatformSearchService;
import com.loai.inventory.service.platform.UserAdminService;
import com.loai.inventory.service.push.WebPushConfig;
import com.loai.inventory.service.push.WebPushSender;
import com.loai.inventory.service.push.WebPushSenderFactory;
import com.loai.inventory.service.whatsapp.WhatsAppSender;
import com.loai.inventory.service.whatsapp.WhatsAppSenderFactory;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.jobrunr.configuration.JobRunr;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.scheduling.cron.CronExpression;
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

  /**
   * The recurring-job ids this application registers with JobRunr. Named constants because the
   * platform overview reads job health back by exactly these ids — a typo on either side would show
   * an operator a silently empty job panel.
   */
  public static final String JOB_ORDER_TTL_SWEEPER = "order-ttl-sweeper";

  public static final String JOB_NOTIFICATION_DELIVERY_SWEEPER = "notification-delivery-sweeper";
  public static final String JOB_UNVERIFIED_ACCOUNT_PURGE = "unverified-account-purge";

  // Infrastructure
  public final HikariDataSource dataSource;
  public final JedisPool jedisPool;
  public final DSLContext dsl;
  public final ObjectMapper objectMapper;

  /**
   * When this composition root was constructed — i.e. when this process came up. The platform
   * overview's build tile reports it, so an operator can tell a restart from a hang.
   */
  public final Instant startedAt;

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
  public final CollectionRepositoryFactory collectionRepositoryFactory;
  public final StorefrontCrawlRepositoryFactory storefrontCrawlRepositoryFactory;
  public final CouponRepositoryFactory couponRepositoryFactory;
  public final NotificationRepositoryFactory notificationRepositoryFactory;
  public final NotificationPreferenceRepositoryFactory notificationPreferenceRepositoryFactory;
  public final CustomerMagicTokenRepositoryFactory customerMagicTokenRepositoryFactory;
  public final AppUserMagicTokenRepositoryFactory appUserMagicTokenRepositoryFactory;
  public final ProductListingRepositoryFactory productListingRepositoryFactory;
  public final ProductVariantRepositoryFactory productVariantRepositoryFactory;
  public final ProductRepositoryFactory productRepositoryFactory;
  public final StorefrontBannerRepositoryFactory storefrontBannerRepositoryFactory;
  public final StorefrontPageRepositoryFactory storefrontPageRepositoryFactory;
  public final CustomerRepositoryFactory customerRepositoryFactory;
  public final CustomerAddressRepositoryFactory customerAddressRepositoryFactory;
  public final ListingReviewRepositoryFactory listingReviewRepositoryFactory;
  public final CustomerWishlistRepositoryFactory customerWishlistRepositoryFactory;
  public final ListingCommentRepositoryFactory listingCommentRepositoryFactory;
  public final InventoryRepositoryFactory inventoryRepositoryFactory;
  public final InventoryLogRepositoryFactory inventoryLogRepositoryFactory;
  public final UserRepositoryFactory userRepositoryFactory;
  public final OrgRepositoryFactory orgRepositoryFactory;
  public final OrgHealthRepository orgHealthRepository;
  public final ReportRepository reportRepository;
  public final OrgTimelineRepositoryFactory orgTimelineRepositoryFactory;
  public final PlatformAuditRepositoryFactory platformAuditRepositoryFactory;
  public final PlatformStatsRepositoryFactory platformStatsRepositoryFactory;
  public final PlatformQueueRepositoryFactory platformQueueRepositoryFactory;
  public final PlatformSearchRepositoryFactory platformSearchRepositoryFactory;
  public final OrgMilestoneRepositoryFactory orgMilestoneRepositoryFactory;
  public final PlatformFunnelRepositoryFactory platformFunnelRepositoryFactory;
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
  public final PlatformOrgTimelineService platformOrgTimelineService;
  public final PlatformOverviewService platformOverviewService;
  public final PlatformQueueService platformQueueService;
  public final PlatformSearchService platformSearchService;
  public final OrgMilestoneService orgMilestoneService;
  public final PlatformFunnelService platformFunnelService;
  public final PlatformGrowthService platformGrowthService;
  public final UserAdminService userAdminService;
  public final ProductService productService;
  public final CategoryService categoryService;
  public final CollectionService collectionService;
  public final CouponService couponService;
  public final NotificationService notificationService;

  /**
   * Platform key sealing per-merchant WhatsApp tokens; disabled when WHATSAPP_TOKEN_KEY is unset.
   */
  public final SecretBox whatsAppSecretBox;

  public final OrgWhatsAppConfigRepositoryFactory orgWhatsAppConfigRepositoryFactory;

  public final OrgWhatsAppService orgWhatsAppService;

  /**
   * Web Push (V96): the VAPID identity from {@code WEB_PUSH_VAPID_*}, disabled when unset; the
   * user's own device subscriptions behind {@code /api/me/push-subscriptions}.
   */
  public final WebPushConfig webPushConfig;

  public final PushSubscriptionService pushSubscriptionService;
  public final MagicLinkService magicLinkService;
  public final CustomerOtpStore customerOtpStore;
  public final CustomerSessionStore customerSessionStore;
  public final CustomerAuthService customerAuthService;
  public final CustomerPortalService customerPortalService;
  public final ListingReviewService listingReviewService;
  public final WishlistService wishlistService;
  public final ListingCommentService listingCommentService;
  public final ProductListingService productListingService;
  public final ProductVariantService productVariantService;
  public final StorefrontBannerService storefrontBannerService;
  public final StorefrontPageService storefrontPageService;
  public final StorefrontService storefrontService;
  public final CustomerService customerService;
  public final InventoryService inventoryService;
  public final ReservationService reservationService;
  public final SalesOrderService salesOrderService;
  public final CashShiftService cashShiftService;
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
  public final CounterReturnService counterReturnService;
  public final OrderCancellationService orderCancellationService;
  public final DocumentRenderService documentRenderService;

  // Background JobRunr jobs + their lifecycle flag.
  public final OrderTtlSweeperJob orderTtlSweeperJob;
  public final NotificationDeliverySweeperJob notificationDeliverySweeperJob;
  public final UnverifiedAccountPurgeJob unverifiedAccountPurgeJob;
  private final boolean jobRunrStarted;

  public AppConfig() {
    log.info("Initialising application context...");
    this.startedAt = Instant.now();

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
    requireDistinctSigningSecrets(jwtSecret, customerJwtSecret);
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
    this.collectionRepositoryFactory = new CollectionRepositoryFactoryImpl();
    this.storefrontCrawlRepositoryFactory = new StorefrontCrawlRepositoryFactoryImpl();
    this.couponRepositoryFactory = new CouponRepositoryFactoryImpl();
    this.notificationRepositoryFactory = new NotificationRepositoryFactoryImpl();
    this.notificationPreferenceRepositoryFactory =
        new NotificationPreferenceRepositoryFactoryImpl();
    this.customerMagicTokenRepositoryFactory = new CustomerMagicTokenRepositoryFactoryImpl();
    this.appUserMagicTokenRepositoryFactory = new AppUserMagicTokenRepositoryFactoryImpl();
    this.productListingRepositoryFactory = new ProductListingRepositoryFactoryImpl();
    this.productVariantRepositoryFactory = new ProductVariantRepositoryFactoryImpl();
    this.productRepositoryFactory = new ProductRepositoryFactoryImpl();
    this.storefrontBannerRepositoryFactory = new StorefrontBannerRepositoryFactoryImpl();
    this.storefrontPageRepositoryFactory = new StorefrontPageRepositoryFactoryImpl();
    this.customerRepositoryFactory = new CustomerRepositoryFactoryImpl();
    this.customerAddressRepositoryFactory = new CustomerAddressRepositoryFactoryImpl();
    this.listingReviewRepositoryFactory = new ListingReviewRepositoryFactoryImpl();
    this.customerWishlistRepositoryFactory = new CustomerWishlistRepositoryFactoryImpl();
    this.listingCommentRepositoryFactory = new ListingCommentRepositoryFactoryImpl();
    this.inventoryRepositoryFactory = new InventoryRepositoryFactoryImpl();
    this.inventoryLogRepositoryFactory = new InventoryLogRepositoryFactoryImpl();
    this.userRepositoryFactory = new UserRepositoryFactoryImpl();
    this.orgRepositoryFactory = new OrgRepositoryFactoryImpl();
    this.orgHealthRepository = new OrgHealthRepositoryImpl(dsl);
    this.reportRepository = new ReportRepositoryImpl(dsl);
    this.orgTimelineRepositoryFactory = new OrgTimelineRepositoryFactoryImpl();
    this.platformAuditRepositoryFactory = new PlatformAuditRepositoryFactoryImpl();
    this.platformStatsRepositoryFactory = new PlatformStatsRepositoryFactoryImpl();
    this.platformQueueRepositoryFactory = new PlatformQueueRepositoryFactoryImpl();
    this.platformSearchRepositoryFactory = new PlatformSearchRepositoryFactoryImpl();
    this.orgMilestoneRepositoryFactory = new OrgMilestoneRepositoryFactoryImpl();
    this.platformFunnelRepositoryFactory = new PlatformFunnelRepositoryFactoryImpl();
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
    // Ahead of accountService/platformOrgService/productListingService/salesOrderService below —
    // all five milestone-writing services take this as a required collaborator (slice 7,
    // stories/platform_tenant_funnel.md — never best-effort, so it is not optional).
    this.orgMilestoneService = new OrgMilestoneService(orgMilestoneRepositoryFactory);

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
            impersonationTtl,
            // Per-account failed-login lockout — the half of login throttling the per-IP filter
            // cannot cover (a distributed attack on one account never fills one IP's bucket).
            new LoginThrottle(jedisPool));
    // Base URL for emailed CUSTOMER links (order view, unsubscribe — MagicLinkService below):
    // the storefront app in prod.
    String publicBaseUrl = getenvOrDefault("PUBLIC_BASE_URL", "http://localhost:8080");
    // Base URL for emailed CREDENTIAL links (reset / activate / verify-email): those pages live in
    // the ADMIN app (admin.<domain> in prod, story 89) — a link built on the storefront base 404s.
    // Falls back to PUBLIC_BASE_URL so single-host dev setups need nothing.
    String adminBaseUrl = getenvOrDefault("ADMIN_BASE_URL", publicBaseUrl);
    long resetTtlMinutes = parseLong(System.getenv("PASSWORD_RESET_TTL_MINUTES"), 120L);
    long inviteTtlDays = parseLong(System.getenv("INVITE_TTL_DAYS"), 7L);
    long verifyTtlHours = parseLong(System.getenv("EMAIL_VERIFY_TTL_HOURS"), 48L);
    this.credentialTokenService =
        new CredentialTokenService(
            dsl,
            appUserMagicTokenRepositoryFactory,
            adminBaseUrl,
            Duration.ofMinutes(resetTtlMinutes),
            Duration.ofDays(inviteTtlDays),
            Duration.ofHours(verifyTtlHours));
    this.authMailer = new AuthMailer(emailSender);
    // Email quality gate (story 87): vendored disposable-domain blocklist (EMAIL_BLOCKLIST_PATH
    // overrides the classpath snapshot) + a Redis-cached dnsjava MX check. The MX leg ships dark
    // (EMAIL_MX_CHECK_ENABLED default false); every failure mode passes rather than blocks.
    boolean mxCheckEnabled =
        Boolean.parseBoolean(getenvOrDefault("EMAIL_MX_CHECK_ENABLED", "false"));
    int mxTimeoutMs = (int) parseLong(System.getenv("EMAIL_MX_TIMEOUT_MS"), 2000L);
    EmailGate emailGate =
        new EmailGate(
            EmailGate.loadBlocklist(System.getenv("EMAIL_BLOCKLIST_PATH")),
            new CachingMxResolver(new DnsJavaMxResolver(mxTimeoutMs), jedisPool),
            mxCheckEnabled);
    // Before accountService — verifyEmail invalidates the org-status mirror on activation (89).
    this.orgStatusService = new OrgStatusService(jedisPool, dsl, orgRepositoryFactory);
    // Enforce org suspension on the hot authorization path, backed by the Redis-mirrored gate.
    AuthzHelper.configureOrgStatusGate(orgStatusService::isActive);
    this.accountService =
        new AccountService(
            dsl,
            userRepositoryFactory,
            orgRepositoryFactory,
            credentialTokenService,
            authMailer,
            authService,
            emailGate,
            orgStatusService,
            orgMilestoneService);
    this.orgService =
        new OrgService(dsl, orgRepositoryFactory, userRepositoryFactory, objectStorage);
    this.orgHealthService = new OrgHealthService(orgHealthRepository);
    this.reportService = new ReportService(reportRepository);
    this.memberService = new MemberService(dsl, userRepositoryFactory, authService);
    this.platformAuditService = new PlatformAuditService(dsl, platformAuditRepositoryFactory);
    this.platformOrgService =
        new PlatformOrgService(
            dsl,
            orgRepositoryFactory,
            userRepositoryFactory,
            orgHealthRepository,
            platformAuditService,
            orgStatusService,
            credentialTokenService,
            authMailer,
            orgMilestoneService);
    this.platformOrgTimelineService =
        new PlatformOrgTimelineService(dsl, orgTimelineRepositoryFactory, orgRepositoryFactory);
    this.userAdminService =
        new UserAdminService(
            dsl,
            userRepositoryFactory,
            orgRepositoryFactory,
            authService,
            platformAuditService,
            credentialTokenService,
            authMailer);
    this.productService =
        new ProductService(productRepository, productVariantRepositoryFactory, dsl);
    this.categoryService =
        new CategoryService(dsl, categoryRepositoryFactory, orgRepositoryFactory, objectStorage);
    // MagicLinkService is built before NotificationService — the producer mints an unsubscribe
    // link for every customer email through it. (publicBaseUrl was resolved above for auth links.)
    long magicTtlDays = parseLong(System.getenv("MAGIC_LINK_TTL_DAYS"), 30L);
    this.magicLinkService =
        new MagicLinkService(
            dsl,
            customerMagicTokenRepositoryFactory,
            orgRepositoryFactory,
            customerRepositoryFactory,
            publicBaseUrl,
            Duration.ofDays(magicTtlDays));
    int emailMaxAttempts =
        (int)
            parseLong(
                System.getenv("EMAIL_MAX_ATTEMPTS"),
                NotificationService.DEFAULT_EMAIL_MAX_ATTEMPTS);
    // Per-merchant WhatsApp (slice B). The SecretBox key is a PLATFORM secret; the tokens it seals
    // are per-tenant. With no key configured the factory returns LoggingWhatsAppSender, so the
    // whole pipeline still runs in dev/CI — the same property EmailSenderFactory has.
    OrgWhatsAppConfigRepositoryFactory orgWhatsAppConfigRepositoryFactory =
        new OrgWhatsAppConfigRepositoryFactoryImpl();
    this.whatsAppSecretBox = SecretBox.fromBase64Key(System.getenv("WHATSAPP_TOKEN_KEY"));
    WhatsAppSender whatsAppSender = WhatsAppSenderFactory.build(whatsAppSecretBox);
    this.orgWhatsAppConfigRepositoryFactory = orgWhatsAppConfigRepositoryFactory;
    this.orgWhatsAppService =
        new OrgWhatsAppService(dsl, orgWhatsAppConfigRepositoryFactory, whatsAppSecretBox);
    // Web Push (stories/web_push_channel.md). The SecretBox rule: both keys unset → disabled (the
    // logging sender, and GET /api/me/push/config says enabled:false); a key present but malformed
    // → startup failure. The push leg draws on the email attempt budget unless told otherwise.
    this.webPushConfig =
        WebPushConfig.fromEnv(
            System.getenv("WEB_PUSH_VAPID_PUBLIC_KEY"),
            System.getenv("WEB_PUSH_VAPID_PRIVATE_KEY"),
            System.getenv("WEB_PUSH_SUBJECT"));
    WebPushSender webPushSender = WebPushSenderFactory.build(webPushConfig);
    PushSubscriptionRepositoryFactory pushSubscriptionRepositoryFactory =
        new PushSubscriptionRepositoryFactoryImpl();
    int pushMaxAttempts =
        (int) parseLong(System.getenv("WEB_PUSH_MAX_ATTEMPTS"), (long) emailMaxAttempts);
    this.pushSubscriptionService =
        new PushSubscriptionService(
            dsl, pushSubscriptionRepositoryFactory, userRepositoryFactory, webPushConfig);
    this.notificationService =
        new NotificationService(
            dsl,
            notificationRepositoryFactory,
            userRepositoryFactory,
            customerRepositoryFactory,
            notificationPreferenceRepositoryFactory,
            orgRepositoryFactory,
            orgWhatsAppConfigRepositoryFactory,
            emailSender,
            magicLinkService,
            whatsAppSender,
            emailMaxAttempts,
            pushSubscriptionRepositoryFactory,
            webPushSender,
            pushMaxAttempts);
    // The reorder-point check the two sale sites run (stories/reorder_point.md): reads the
    // product, calls the one staff fan-out above — composition, not a second alerting path.
    LowStockNotifier lowStockNotifier =
        new LowStockNotifier(productRepositoryFactory, notificationService);
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
            emailGate,
            portalOtpRequestLimit,
            60);
    this.productListingService =
        new ProductListingService(
            dsl,
            productListingRepositoryFactory,
            orgRepositoryFactory,
            productVariantRepositoryFactory,
            objectStorage,
            orgMilestoneService);
    // After productListingService — the curation read reuses its enrichment (thumbnails, status
    // badges) so a collection row renders exactly like a featured one.
    // Before salesOrderService — the placement resolves a coupon inside its own transaction.
    this.couponService = new CouponService(dsl, couponRepositoryFactory);
    this.collectionService =
        new CollectionService(
            dsl,
            collectionRepositoryFactory,
            productListingRepositoryFactory,
            orgRepositoryFactory,
            productListingService,
            objectStorage);
    this.productVariantService =
        new ProductVariantService(
            dsl,
            productVariantRepositoryFactory,
            productListingRepositoryFactory,
            productRepositoryFactory,
            orgRepositoryFactory);
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
    this.customerService =
        new CustomerService(dsl, customerRepositoryFactory, salesOrderRepositoryFactory);
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
            inventoryLogRepositoryFactory,
            lowStockNotifier);
    this.orderExpiryService =
        new OrderExpiryService(
            dsl,
            salesOrderRepositoryFactory,
            reservationService,
            paymentTransactionRepositoryFactory);
    this.numberSequenceReconciliationService =
        new NumberSequenceReconciliationService(dsl, numberSequenceReconciliationRepositoryFactory);
    // NotificationService + MagicLinkService are both constructed above — the ORDER_PAID producer
    // (stories/notify_order_paid.md) mints a fresh order-view link per customer email.
    // stories/cash_shift.md: the drawer-day every counter tender, change and cash refund is
    // stamped with. Built before the money services so both take it as their stamper.
    this.cashShiftService =
        new CashShiftService(
            dsl,
            new CashShiftRepositoryFactoryImpl(),
            new CashMovementRepositoryFactoryImpl(),
            orgRepositoryFactory,
            userRepositoryFactory);
    this.paymentService =
        new PaymentService(
            dsl,
            paymentRepositoryFactory,
            salesOrderRepositoryFactory,
            paymentTransactionRepositoryFactory,
            refundRepositoryFactory,
            inventoryReservationRepositoryFactory,
            notificationService,
            magicLinkService,
            orgMilestoneService,
            cashShiftService);
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
            salesOrderRepositoryFactory,
            cashShiftService);
    this.paymentTransactionService =
        new PaymentTransactionService(
            dsl,
            paymentTransactionRepositoryFactory,
            paymentRepositoryFactory,
            paymentService,
            refundService,
            objectStorage,
            customerRepositoryFactory,
            userRepositoryFactory);
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
            reservationService,
            notificationService,
            magicLinkService,
            lowStockNotifier);
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
            magicLinkService,
            emailGate,
            couponService,
            orgMilestoneService);
    this.storefrontService =
        new StorefrontService(
            dsl,
            orgRepositoryFactory,
            productListingRepositoryFactory,
            categoryRepositoryFactory,
            inventoryRepositoryFactory,
            storefrontBannerRepositoryFactory,
            listingReviewRepositoryFactory,
            collectionRepositoryFactory,
            orgWhatsAppConfigRepositoryFactory,
            storefrontCrawlRepositoryFactory,
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
    // Wishlist (roadmap item 3): storage + the PUBLISHED-only read, which it borrows from the
    // storefront service so a saved item renders as exactly the same card as a catalog one.
    this.wishlistService =
        new WishlistService(
            dsl,
            customerWishlistRepositoryFactory,
            productListingRepositoryFactory,
            storefrontService);
    this.creditNoteService =
        new CreditNoteService(
            dsl,
            creditNoteRepositoryFactory,
            salesInvoiceRepositoryFactory,
            refundRepositoryFactory,
            orgRepositoryFactory);
    // The counter return composes credit-note issuance, the refund and the restock in one txn
    // (stories/counter_return.md); built after both services it delegates to.
    this.counterReturnService =
        new CounterReturnService(
            dsl,
            salesOrderRepositoryFactory,
            salesInvoiceRepositoryFactory,
            creditNoteRepositoryFactory,
            refundRepositoryFactory,
            paymentRepositoryFactory,
            paymentTransactionRepositoryFactory,
            inventoryRepositoryFactory,
            inventoryLogRepositoryFactory,
            creditNoteService,
            refundService);
    this.orderCancellationService =
        new OrderCancellationService(
            dsl,
            salesOrderRepositoryFactory,
            paymentRepositoryFactory,
            fulfillmentRepositoryFactory,
            reservationService,
            refundService,
            notificationService,
            magicLinkService,
            paymentTransactionRepositoryFactory);
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

    // Never-verified account GC (story 88): grace window well beyond the 48h verify-token TTL, and
    // a live token always shields its account regardless (the repository query excludes it).
    long purgeGraceDays = parseLong(System.getenv("UNVERIFIED_PURGE_GRACE_DAYS"), 7L);
    int purgeBatchLimit = (int) parseLong(System.getenv("UNVERIFIED_PURGE_BATCH_LIMIT"), 200L);
    this.unverifiedAccountPurgeJob =
        new UnverifiedAccountPurgeJob(
            accountService, Duration.ofDays(purgeGraceDays), purgeBatchLimit);

    // The background scheduler is gated so tests (and any deployment that wants to drive expiry
    // only through POST /api/admin/sweep) can keep expiry deterministic. Default: enabled.
    boolean enableSweeper =
        !"false".equalsIgnoreCase(System.getenv("ORDER_SWEEPER_BACKGROUND_ENABLED"));
    // One resolution of the crons, shared by the scheduler that registers the jobs and the overview
    // that judges them. Reading the same env vars in two places is how a re-tuned interval would
    // silently start reporting a healthy job as stale.
    Map<String, String> jobCrons = resolveJobCrons();
    this.jobRunrStarted = enableSweeper && startSweeperScheduler(jobCrons);

    this.platformOverviewService =
        new PlatformOverviewService(
            dsl,
            platformStatsRepositoryFactory,
            enableSweeper,
            jobCrons.entrySet().stream()
                .map(
                    e ->
                        new PlatformOverviewService.JobConfig(e.getKey(), cronPeriod(e.getValue())))
                .toList(),
            System.getenv("BUILD_COMMIT"),
            startedAt);
    // The rows behind the overview's five backlog tiles (slice 2). A separate repository from
    // platformStatsRepositoryFactory on purpose — see PlatformQueueRepository's Javadoc.
    this.platformQueueService = new PlatformQueueService(dsl, platformQueueRepositoryFactory);
    // Cross-org identifier search (slice 3). A third sibling for the same reason: the queue
    // repository's per-kind row whitelist is what makes it reviewable, and search results are a
    // different whitelist — see PlatformSearchRepository's Javadoc.
    this.platformSearchService = new PlatformSearchService(dsl, platformSearchRepositoryFactory);
    // The tenant lifecycle funnel (slice 7) — a fourth sibling of platformStatsRepositoryFactory,
    // platformQueueRepositoryFactory and platformSearchRepositoryFactory; see
    // PlatformFunnelRepository's Javadoc for why it is a new sibling rather than a method on one of
    // the three above.
    this.platformFunnelService = new PlatformFunnelService(dsl, platformFunnelRepositoryFactory);
    // The growth series (slice 8) — deliberately the SAME repository as the funnel, not a fifth
    // sibling: same table, same counts-only contract, same gate, and the same cohortCondition
    // definition for the self-serve/provisioned split. See PlatformFunnelRepository's Javadoc.
    this.platformGrowthService = new PlatformGrowthService(dsl, platformFunnelRepositoryFactory);

    log.info("Application context ready.");
  }

  /**
   * The recurring jobs this app registers, id → cron, read from the same env vars (with the same
   * defaults) that {@link #startSweeperScheduler} schedules them with.
   */
  private static Map<String, String> resolveJobCrons() {
    Map<String, String> crons = new LinkedHashMap<>();
    crons.put(JOB_ORDER_TTL_SWEEPER, getenvOrDefault("ORDER_SWEEPER_INTERVAL", "*/30 * * * * *"));
    crons.put(
        JOB_NOTIFICATION_DELIVERY_SWEEPER,
        getenvOrDefault("NOTIFICATION_SWEEPER_INTERVAL", "*/10 * * * * *"));
    // Daily is plenty — the login gate already neutralizes unverified accounts; this only GCs rows.
    crons.put(
        JOB_UNVERIFIED_ACCOUNT_PURGE, getenvOrDefault("UNVERIFIED_PURGE_INTERVAL", "0 0 4 * * *"));
    return crons;
  }

  /**
   * The interval a cron implies, measured as the gap between its next two firings. The platform
   * overview uses it to size the staleness window per job, so a 10s sweeper and a daily purge are
   * each judged against their own cadence instead of one arbitrary wall-clock window.
   *
   * <p>Returns {@code null} for an unparseable cron rather than guessing — downstream that reads as
   * {@code UNKNOWN}, which is the honest answer when we cannot say what "on time" means.
   */
  private static Duration cronPeriod(String cron) {
    try {
      CronExpression expression = new CronExpression(cron);
      Instant now = Instant.now();
      Instant first = expression.next(now, now, ZoneOffset.UTC);
      Instant second = expression.next(now, first, ZoneOffset.UTC);
      if (!second.isAfter(first)) {
        second = expression.next(now, first.plusMillis(1), ZoneOffset.UTC);
      }
      Duration period = Duration.between(first, second);
      return period.isZero() || period.isNegative() ? null : period;
    } catch (RuntimeException e) {
      log.warn("Cannot derive a period from cron '{}'; job health will read UNKNOWN", cron, e);
      return null;
    }
  }

  /**
   * Configure JobRunr against the shared datasource ({@link DatabaseOptions#SKIP_CREATE} — the
   * jobrunr_* tables are owned by Flyway V29, not auto-created), start its {@code
   * BackgroundJobServer}, and register the recurring {@code order-ttl-sweeper}. The custom {@link
   * JobActivator} hands JobRunr our pre-wired {@link OrderTtlSweeperJob} so it keeps its injected
   * service. Returns {@code true} if the scheduler started.
   */
  private boolean startSweeperScheduler(Map<String, String> jobCrons) {
    String cron = jobCrons.get(JOB_ORDER_TTL_SWEEPER);
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
            if (type.isInstance(unverifiedAccountPurgeJob)) {
              return type.cast(unverifiedAccountPurgeJob);
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
        JOB_ORDER_TTL_SWEEPER, cron, OrderTtlSweeperJob::run);
    log.info("Order-TTL sweeper scheduled (cron='{}')", cron);

    String notifyCron = jobCrons.get(JOB_NOTIFICATION_DELIVERY_SWEEPER);
    scheduler.<NotificationDeliverySweeperJob>scheduleRecurrently(
        JOB_NOTIFICATION_DELIVERY_SWEEPER, notifyCron, NotificationDeliverySweeperJob::run);
    log.info("Notification-delivery sweeper scheduled (cron='{}')", notifyCron);

    String purgeCron = jobCrons.get(JOB_UNVERIFIED_ACCOUNT_PURGE);
    scheduler.<UnverifiedAccountPurgeJob>scheduleRecurrently(
        JOB_UNVERIFIED_ACCOUNT_PURGE, purgeCron, UnverifiedAccountPurgeJob::run);
    log.info("Unverified-account purge scheduled (cron='{}')", purgeCron);
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
   * Fail fast when the two auth planes are signed with the <em>same</em> key. Presence and length
   * are already enforced (here and in {@link JwtUtil}); this is the third property the
   * customer/staff isolation rests on — a {@code CUSTOMER_JWT_SECRET} copy-pasted from {@code
   * JWT_SECRET} silently collapses the cryptographic half of that separation, leaving only the
   * {@code aud} claim between a customer token and the staff plane. Defense in depth, and one
   * {@code if}.
   *
   * <p>Compared on the <b>decoded bytes</b>, not the strings: two Base64 spellings of one key (a
   * padding or line-break variant) are the same key and must be rejected the same way. An
   * undecodable value is left alone — {@link JwtUtil}'s constructor owns that message.
   */
  static void requireDistinctSigningSecrets(String staffSecret, String customerSecret) {
    byte[] staff = decodeOrNull(staffSecret);
    byte[] customer = decodeOrNull(customerSecret);
    if (staff == null || customer == null) {
      return;
    }
    if (java.security.MessageDigest.isEqual(staff, customer)) {
      throw new IllegalStateException(
          "CUSTOMER_JWT_SECRET must differ from JWT_SECRET — the customer portal and the staff"
              + " plane must not share a signing key");
    }
  }

  private static byte[] decodeOrNull(String base64) {
    try {
      return java.util.Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * The one CORS_ALLOWED_ORIGINS allowlist. Consumed by <em>both</em> the portal's CSRF {@code
   * Origin} check and {@link com.loai.inventory.api.filter.CorsFilter} — which used to re-parse the
   * env var itself, untrimmed, and so disagreed with this set on any value written with a space
   * after the comma.
   */
  private static java.util.Set<String> resolveAllowedOrigins() {
    return parseAllowedOrigins(System.getenv("CORS_ALLOWED_ORIGINS"));
  }

  /**
   * Split a comma-separated origin list, trimming each entry and dropping empties; a blank/absent
   * value yields the dev default. Pure (no environment access) so the parsing rule is testable.
   */
  public static java.util.Set<String> parseAllowedOrigins(String env) {
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
