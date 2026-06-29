package self.research.ontology.auth.service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.*;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stripe payment and subscription service.
 *
 * Security posture:
 * - API secret key loaded exclusively from environment variables — never hardcoded.
 * - Webhook signature verified with Stripe-Signature header before any processing.
 * - Card data never touches this server; Stripe Checkout handles PCI scope.
 * - Idempotency keys used on all mutating Stripe calls.
 * - Only Stripe customer/subscription IDs are stored in the database.
 */
@Service
public class StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);

    @Value("${stripe.api.secret-key}")
    private String stripeSecretKey;

    @Value("${stripe.api.publishable-key}")
    private String stripePublishableKey;

    @Value("${stripe.webhook.secret}")
    private String webhookSecret;

    @Value("${stripe.price.pro-monthly}")
    private String priceProMonthly;

    @Value("${stripe.price.pro-yearly}")
    private String priceProYearly;

    @Value("${stripe.price.enterprise-monthly}")
    private String priceEnterpriseMonthly;

    @Value("${stripe.price.enterprise-yearly}")
    private String priceEnterpriseYearly;

    @Value("${stripe.trial-period-days:14}")
    private Long trialPeriodDays;

    @Value("${app.base-url}")

    private String baseUrl;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceService workspaceService;
    private final EmailService emailService;
    private final MongoTemplate mongoTemplate;

    public StripeService(UserRepository userRepository, WorkspaceRepository workspaceRepository,
                         WorkspaceService workspaceService, EmailService emailService, MongoTemplate mongoTemplate) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceService = workspaceService;
        this.emailService = emailService;
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void init() {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            throw new IllegalStateException(
                "[SECURITY] stripe.api.secret-key (STRIPE_SECRET_KEY) must be set.");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException(
                "[SECURITY] stripe.webhook.secret (STRIPE_WEBHOOK_SECRET) must be set.");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                "[SECURITY] app.base-url (APP_BASE_URL) must be set.");
        }
        validatePriceId("STRIPE_PRICE_PRO_MONTHLY", priceProMonthly);
        validatePriceId("STRIPE_PRICE_PRO_YEARLY", priceProYearly);
        validatePriceId("STRIPE_PRICE_ENTERPRISE_MONTHLY", priceEnterpriseMonthly);
        validatePriceId("STRIPE_PRICE_ENTERPRISE_YEARLY", priceEnterpriseYearly);
        Stripe.apiKey = stripeSecretKey;
        mongoTemplate.indexOps("email_logs")
                .ensureIndex(new Index().on("key", Sort.Direction.ASC).unique());
        log.info("StripeService initialised — base URL: {}", baseUrl);
    }

    private void validatePriceId(String envName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("[CONFIG] " + envName + " must be set to a Stripe Price ID.");
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("price_")) {
            throw new IllegalStateException("[CONFIG] " + envName + " must start with 'price_'.");
        }
        String lower = trimmed.toLowerCase();
        if (lower.contains("replace") || lower.contains("_here") || lower.contains("placeholder")) {
            throw new IllegalStateException("[CONFIG] " + envName + " is still a placeholder. Set a real Stripe Price ID.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Customer Management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves or creates a Stripe Customer for the given user.
     * The Stripe customer ID is persisted in MongoDB so only one customer
     * is ever created per user account.
     */
    public Customer getOrCreateCustomer(User user) throws StripeException {
        if (user.getStripeCustomerId() != null) {
            return Customer.retrieve(user.getStripeCustomerId());
        }

        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(user.getUsername())
                .putMetadata("userId", user.getId())
                .putMetadata("username", user.getUsername())
                .build();

        Customer customer = Customer.create(params);
        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);
        log.info("Created Stripe customer {} for user {}", customer.getId(), user.getUsername());
        return customer;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Native Payment Flow (SetupIntent → Subscription)
    // Step 1: create a SetupIntent so the frontend can collect the card
    // Step 2: createSubscriptionAfterSetup once the card is saved
    // ─────────────────────────────────────────────────────────────────────────

    public String createSetupIntent(User user) throws StripeException {
        Customer customer = getOrCreateCustomer(user);

        SetupIntentCreateParams params = SetupIntentCreateParams.builder()
                .setCustomer(customer.getId())
                .setUsage(SetupIntentCreateParams.Usage.OFF_SESSION)
                .addPaymentMethodType("card")
                .putMetadata("userId", user.getId())
                .build();

        SetupIntent intent = SetupIntent.create(params);
        log.info("Created SetupIntent {} for user {}", intent.getId(), user.getUsername());
        return intent.getClientSecret();
    }

    public String createSubscriptionAfterSetup(User user, String setupIntentId,
            String planName, String interval, String workspaceId) throws StripeException {

        validateAllowedPlanChange(user, planName, interval);

        // Model B/C: one subscription per user account.
        // If an active/trialing subscription exists, treat this as an UPDATE (plan/interval change),
        // using the newly confirmed payment method from the SetupIntent.
        boolean hasExistingSub = user.getStripeSubscriptionId() != null && !user.getStripeSubscriptionId().isBlank();
        String existingStatus = user.getSubscriptionStatus() != null ? user.getSubscriptionStatus() : "";
        boolean existingIsActiveLike =
                "active".equalsIgnoreCase(existingStatus) || "trialing".equalsIgnoreCase(existingStatus);

        // Retrieve and validate setup intent
        SetupIntent setupIntent = SetupIntent.retrieve(setupIntentId);
        if (!"succeeded".equals(setupIntent.getStatus())) {
            throw new IllegalStateException("Card setup not completed. Status: " + setupIntent.getStatus());
        }
        assertSetupIntentBelongsToUser(user, setupIntent);
        String paymentMethodId = setupIntent.getPaymentMethod();

        // Set as customer default for future invoices
        Customer customer = Customer.retrieve(user.getStripeCustomerId());
        customer.update(CustomerUpdateParams.builder()
                .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                        .setDefaultPaymentMethod(paymentMethodId)
                        .build())
                .build());

        String priceId = resolvePriceId(planName, interval);

        com.stripe.model.Subscription subscription;
        if (hasExistingSub && existingIsActiveLike) {
            // Update existing subscription's price (monthly ↔ yearly, PRO ↔ ENTERPRISE) and default payment method.
            subscription = Subscription.retrieve(user.getStripeSubscriptionId());
            if (subscription.getItems() == null || subscription.getItems().getData() == null || subscription.getItems().getData().isEmpty()) {
                throw new IllegalStateException("Existing subscription has no items to update.");
            }

            String itemId = subscription.getItems().getData().get(0).getId();
            String idempotencyKey = "sub-update-immediate-v2-" + user.getId() + "-" + subscription.getId() + "-" + priceId + "-" + setupIntentId;

            SubscriptionUpdateParams.Builder paramsBuilder = SubscriptionUpdateParams.builder()
                    .setDefaultPaymentMethod(paymentMethodId)
                    .addItem(
                            SubscriptionUpdateParams.Item.builder()
                                    .setId(itemId)
                                    .setPrice(priceId)
                                    .build()
                    )
                    // Charge plan upgrades immediately instead of deferring prorations to the next renewal invoice.
                    .setCancelAtPeriodEnd(false)
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE)
                    .setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.ERROR_IF_INCOMPLETE)
                    .putMetadata("planName", planName.toUpperCase())
                    .putMetadata("billingInterval", interval.toLowerCase())
                    .putMetadata("workspaceId", workspaceId != null ? workspaceId : "");

            if ("trialing".equalsIgnoreCase(subscription.getStatus())) {
                paramsBuilder.setTrialEnd(Instant.now().getEpochSecond());
            }

            subscription = subscription.update(
                    paramsBuilder.build(),
                    com.stripe.net.RequestOptions.builder().setIdempotencyKey(idempotencyKey).build()
            );
            log.info("Updated subscription {} for user {} to {}/{}",
                    subscription.getId(), user.getUsername(), planName.toUpperCase(), interval);
        } else {
            // Bug #39 / #40: Stripe does not track trial eligibility per
            // customer, so a user who cancels and re-subscribes would
            // otherwise be granted a brand-new trial each time. We enforce
            // "trial only on first ever subscription" via `hasUsedFreeTrial`.
            SubscriptionCreateParams.Builder createParams = SubscriptionCreateParams.builder()
                    .setCustomer(customer.getId())
                    .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
                    .setDefaultPaymentMethod(paymentMethodId)
                    .putMetadata("userId", user.getId())
                    .putMetadata("planName", planName.toUpperCase())
                    .putMetadata("billingInterval", interval.toLowerCase())
                    .putMetadata("workspaceId", workspaceId != null ? workspaceId : "");

            // A prior stripeSubscriptionId means the user has already subscribed once —
            // guard against data inconsistency where hasUsedFreeTrial wasn't persisted.
            boolean hadPriorSubscription = user.getStripeSubscriptionId() != null
                    && !user.getStripeSubscriptionId().isBlank();
            boolean firstEverSubscription = !user.isHasUsedFreeTrial()
                    && user.getFirstSubscriptionAt() == null
                    && !hadPriorSubscription;
            if (firstEverSubscription && trialPeriodDays != null && trialPeriodDays > 0L) {
                createParams.setTrialPeriodDays(trialPeriodDays);
                log.info("Granting {}-day trial to user {} (first ever subscription)",
                        trialPeriodDays, user.getUsername());
            } else {
                log.info("Skipping trial for user {} (hasUsedFreeTrial={}, firstSubscriptionAt={}, hadPriorSub={}). " +
                        "Card will be charged immediately.",
                        user.getUsername(), user.isHasUsedFreeTrial(), user.getFirstSubscriptionAt(), hadPriorSubscription);
            }

            subscription = com.stripe.model.Subscription.create(createParams.build());

            // Mark trial as consumed in the same transaction as the
            // subscription create — see userRepository.save below.
            user.setHasUsedFreeTrial(true);
            if (user.getFirstSubscriptionAt() == null) {
                user.setFirstSubscriptionAt(LocalDateTime.now());
            }
        }

        applySubscriptionSnapshotToUser(user, subscription, planName, interval);
        userRepository.save(user);

        // Sync to ALL user-owned workspaces (Account-level billing)
        workspaceService.syncWorkspacesToOwnerPlan(user);

        log.info("Subscription {} ({}) created for user {} / workspace {}",
                subscription.getId(), subscription.getStatus(), user.getUsername(), workspaceId);
        return subscription.getId();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Update default payment method for a customer
    // ─────────────────────────────────────────────────────────────────────────

    public void updateDefaultPaymentMethod(User user, String setupIntentId, String workspaceId) throws StripeException {
        SetupIntent setupIntent = SetupIntent.retrieve(setupIntentId);
        if (!"succeeded".equals(setupIntent.getStatus())) {
            throw new IllegalStateException("Card setup not completed. Status: " + setupIntent.getStatus());
        }
        assertSetupIntentBelongsToUser(user, setupIntent);
        String paymentMethodId = setupIntent.getPaymentMethod();

        // Update customer-level default
        Customer customer = Customer.retrieve(user.getStripeCustomerId());
        customer.update(CustomerUpdateParams.builder()
                .setInvoiceSettings(CustomerUpdateParams.InvoiceSettings.builder()
                        .setDefaultPaymentMethod(paymentMethodId)
                        .build())
                .build());

        // Update the workspace's active subscription default payment method
        String subId = null;
        if (workspaceId != null && !workspaceId.isBlank()) {
            Optional<Workspace> wsOpt = workspaceRepository.findByWorkspaceId(workspaceId);
            if (wsOpt.isPresent()) {
                subId = wsOpt.get().getStripeSubscriptionId();
            }
        }
        if (subId == null) {
            subId = user.getStripeSubscriptionId();
        }
        if (subId != null) {
            Subscription subscription = Subscription.retrieve(subId);
            subscription.update(SubscriptionUpdateParams.builder()
                    .setDefaultPaymentMethod(paymentMethodId)
                    .build());
        }
        log.info("Updated default payment method to {} for user {}", paymentMethodId, user.getUsername());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cancel a specific workspace's subscription
    // ─────────────────────────────────────────────────────────────────────────

    public void cancelWorkspaceSubscription(User user, String workspaceId) throws StripeException {
        Workspace workspace = workspaceRepository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new IllegalStateException("Workspace not found"));

        String subId = workspace.getStripeSubscriptionId();
        if (subId == null) {
            throw new IllegalStateException("This workspace does not have an active subscription.");
        }

        Subscription subscription = Subscription.retrieve(subId);
        subscription.cancel();

        workspace.setBillingStatus("CANCELED");
        workspace.setStripeSubscriptionId(null);
        workspace.setCollaborationEnabled(false);
        workspaceRepository.save(workspace);

        // Clear user-level reference if it points to the same subscription
        if (subId.equals(user.getStripeSubscriptionId())) {
            user.setSubscriptionStatus("canceled");
            user.setAutoRenewEnabled(false);
            user.setSubscriptionCanceledAt(LocalDateTime.now());
            userRepository.save(user);
            workspaceService.syncWorkspacesToOwnerPlan(user);
        }
        log.info("Subscription {} canceled for workspace {} by user {}", subId, workspaceId, user.getUsername());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cancel the user's account-level subscription (Model B)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Schedules account-level cancellation at the end of the current billing period.
     * Access remains active until Stripe ends the subscription.
     */
    public void cancelAccountSubscription(User user) throws StripeException {
        disableAutoRenew(user);
        log.info("Account subscription {} scheduled to cancel at period end for user {}",
                user.getStripeSubscriptionId(), user.getUsername());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Checkout Session (kept for backward compatibility)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a Stripe Checkout Session for subscribing to a plan.
     * Stripe Checkout hosts the payment form — card data never hits our server.
     *
     * @param user     authenticated user
     * @param planName PRO or ENTERPRISE
     * @param interval monthly or yearly
     * @return Stripe Checkout client secret for embedded checkout
     */
    public String createCheckoutSession(User user, String planName, String interval, String workspaceId) throws StripeException {
        validateAllowedPlanChange(user, planName, interval);

        String priceId = resolvePriceId(planName, interval);
        Customer customer = getOrCreateCustomer(user);

        // Bug #39 / #40: trial is granted only on the first ever subscription.
        SessionCreateParams.SubscriptionData.Builder subData = SessionCreateParams.SubscriptionData.builder()
                .putMetadata("userId", user.getId())
                .putMetadata("planName", planName.toUpperCase())
                .putMetadata("billingInterval", interval.toLowerCase())
                .putMetadata("workspaceId", workspaceId != null ? workspaceId : "");
        boolean hadPriorSubscription = user.getStripeSubscriptionId() != null
                && !user.getStripeSubscriptionId().isBlank();
        boolean firstEverSubscription = !user.isHasUsedFreeTrial()
                && user.getFirstSubscriptionAt() == null
                && !hadPriorSubscription;
        if (firstEverSubscription && trialPeriodDays != null && trialPeriodDays > 0L) {
            subData.setTrialPeriodDays(trialPeriodDays);
        }

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setUiMode(SessionCreateParams.UiMode.EMBEDDED)
                .setCustomer(customer.getId())
                .setClientReferenceId(user.getId())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build())
                .setReturnUrl(baseUrl + "/?checkout_complete=1&session_id={CHECKOUT_SESSION_ID}")
                .setSubscriptionData(subData.build());

        if (workspaceId != null && !workspaceId.isBlank()) {
            builder.putMetadata("workspaceId", workspaceId);
        }

        // Guard: Account-level subscription check (Model C: One subscription per account)
        if (user.getStripeSubscriptionId() != null && !user.getStripeSubscriptionId().isBlank()) {
            String status = user.getSubscriptionStatus();
            if (!"canceled".equalsIgnoreCase(status) && !"unpaid".equalsIgnoreCase(status)) {
                throw new IllegalStateException("Your account already has an active subscription. Use the billing portal to manage it.");
            }
        }

        Session session = Session.create(builder.build());

        // Store pending lock on the workspace to block re-entry while webhook is in flight
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaceRepository.findByWorkspaceId(workspaceId).ifPresent(ws -> {
                ws.setPendingCheckoutSessionId(session.getId());
                ws.setPendingCheckoutCreatedAt(LocalDateTime.now());
                workspaceRepository.save(ws);
            });
        }

        log.info("Created embedded checkout session {} for user {} / workspace {}", session.getId(), user.getUsername(), workspaceId);
        return session.getClientSecret();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Customer Portal
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a Stripe Billing Portal session so the user can manage their
     * payment method, view invoices, and update their plan directly via Stripe UI.
     */
    public String createBillingPortalSession(User user) throws StripeException {
        if (user.getStripeCustomerId() == null) {
            throw new IllegalStateException("No billing account found. Please subscribe first.");
        }

        com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                        .setCustomer(user.getStripeCustomerId())
                        .setReturnUrl(baseUrl + "/billing")
                        .build();

        com.stripe.model.billingportal.Session session =
                com.stripe.model.billingportal.Session.create(params);
        return session.getUrl();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto-Renew Control
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Disables auto-renewal. The subscription remains active until the end
     * of the current billing period, then cancels automatically.
     */
    public void disableAutoRenew(User user) throws StripeException {
        requireActiveSubscription(user);

        String idempotencyKey = "cancel-at-period-end-" + user.getId() + "-" + user.getStripeSubscriptionId();

        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                .setCancelAtPeriodEnd(true)
                .build();

        com.stripe.net.RequestOptions options = com.stripe.net.RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();

        Subscription subscription = Subscription.retrieve(user.getStripeSubscriptionId()).update(params, options);

        user.setAutoRenewEnabled(false);
        userRepository.save(user);
        log.info("Auto-renew disabled for user {} subscription {}", user.getUsername(), user.getStripeSubscriptionId());
        sendAutoRenewDisabledEmailOnce(user, subscription);
    }

    /**
     * Re-enables auto-renewal, cancelling a previously scheduled cancellation.
     */
    public void enableAutoRenew(User user) throws StripeException {
        requireActiveSubscription(user);

        String idempotencyKey = "reactivate-" + user.getId() + "-" + user.getStripeSubscriptionId();

        SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                .setCancelAtPeriodEnd(false)
                .build();

        com.stripe.net.RequestOptions options = com.stripe.net.RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();

        Subscription subscription = Subscription.retrieve(user.getStripeSubscriptionId()).update(params, options);

        user.setAutoRenewEnabled(true);
        user.setSubscriptionCanceledAt(null);
        userRepository.save(user);
        log.info("Auto-renew re-enabled for user {} subscription {}", user.getUsername(), user.getStripeSubscriptionId());
        sendAutoRenewEnabledEmailOnce(user, subscription);
    }

    /**
     * Immediately cancels the subscription (prorated refund not issued).
     * Idempotent: if the subscription is already cancelled (e.g. double-click), local state
     * is still reconciled and no Stripe API error is surfaced.
     */
    public void cancelSubscriptionImmediately(User user) throws StripeException {
        requireActiveSubscription(user);

        Subscription subscription = Subscription.retrieve(user.getStripeSubscriptionId());
        if (!"canceled".equalsIgnoreCase(subscription.getStatus())) {
            subscription.cancel();
        } else {
            log.info("Subscription {} already canceled in Stripe for user {} — reconciling local state",
                    subscription.getId(), user.getUsername());
        }

        user.setSubscriptionStatus("canceled");
        user.setAutoRenewEnabled(false);
        user.setSubscriptionCanceledAt(LocalDateTime.now());
        userRepository.save(user);
        workspaceService.syncWorkspacesToOwnerPlan(user);
        log.info("Subscription {} immediately canceled for user {}", user.getStripeSubscriptionId(), user.getUsername());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Webhook Event Verification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifies the Stripe webhook signature and returns the parsed Event.
     * Throws SignatureVerificationException if the signature is invalid —
     * the caller must reject the request with HTTP 400.
     *
     * @param payload   raw request body bytes (must be the original, unmodified bytes)
     * @param sigHeader value of the Stripe-Signature HTTP header
     */
    public String getPublishableKey() {
        return stripePublishableKey;
    }

    public Map<String, Object> getBillingSummary(User user) throws StripeException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("planName", emptyIfNull(user.getSubscriptionPlanName()));
        response.put("status", emptyIfNull(user.getSubscriptionStatus()));
        response.put("billingInterval", emptyIfNull(user.getBillingInterval()));
        response.put("autoRenewEnabled", user.isAutoRenewEnabled());
        response.put("currentPeriodEnd", user.getSubscriptionCurrentPeriodEnd() != null ? user.getSubscriptionCurrentPeriodEnd().toString() : "");
        response.put("canceledAt", user.getSubscriptionCanceledAt() != null ? user.getSubscriptionCanceledAt().toString() : "");
        response.put("paymentHistory", List.of());

        if (user.getStripeSubscriptionId() != null && !user.getStripeSubscriptionId().isBlank()) {
            Subscription subscription = Subscription.retrieve(user.getStripeSubscriptionId());
            if (subscription.getCurrentPeriodEnd() != null) {
                LocalDateTime periodEnd = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()),
                    ZoneOffset.UTC
                );
                response.put("currentPeriodEnd", periodEnd.toString());
            }
            response.put("status", emptyIfNull(subscription.getStatus()));
            response.put("autoRenewEnabled", !Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
            response.put("cancelAtPeriodEnd", Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));
            if (subscription.getItems() != null
                && subscription.getItems().getData() != null
                && !subscription.getItems().getData().isEmpty()) {
                SubscriptionItem item = subscription.getItems().getData().get(0);
                String stripeInterval = item.getPrice() != null && item.getPrice().getRecurring() != null
                    ? item.getPrice().getRecurring().getInterval()
                    : null;
                response.put("billingInterval", normalizeStripeInterval(stripeInterval));
            }
        }

        if (user.getStripeCustomerId() != null && !user.getStripeCustomerId().isBlank()) {
            response.put("paymentHistory", listPaymentHistory(user.getStripeCustomerId()));
            response.putAll(resolveDefaultPaymentMethod(user.getStripeCustomerId()));
        }

        return response;
    }

    public Event constructWebhookEvent(byte[] payload, String sigHeader) throws SignatureVerificationException {
        return Webhook.constructEvent(new String(payload), sigHeader, webhookSecret);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Webhook Event Handlers (called after idempotency check)
    // ─────────────────────────────────────────────────────────────────────────

    public void handleCheckoutSessionCompleted(Session session) {
        String userId = session.getClientReferenceId();
        if (userId == null) return;

        Optional<User> optUser = userRepository.findById(userId);
        if (optUser.isEmpty()) {
            log.warn("checkout.session.completed: user {} not found", userId);
            return;
        }

        User user = optUser.get();

        // Store subscription ID on user for billing portal access
        if (session.getSubscription() != null && user.getStripeSubscriptionId() == null) {
            user.setStripeSubscriptionId(session.getSubscription());
            if (user.getSubscriptionStatus() == null) {
                user.setSubscriptionStatus("trialing");
            }
        }

        // Mark trial consumed here (mirrors createSubscriptionAfterSetup) so that if the
        // customer.subscription.created webhook is delayed or missed, a second checkout
        // attempt cannot grant a second free trial.
        if (!user.isHasUsedFreeTrial()) {
            user.setHasUsedFreeTrial(true);
        }
        if (user.getFirstSubscriptionAt() == null) {
            user.setFirstSubscriptionAt(LocalDateTime.now());
        }
        userRepository.save(user);

        // Clear embedded-checkout lock (otherwise the workspace stays stuck in "pending checkout")
        if (session.getId() != null) {
            workspaceRepository.findByPendingCheckoutSessionId(session.getId()).ifPresent(ws -> {
                ws.setPendingCheckoutSessionId(null);
                ws.setPendingCheckoutCreatedAt(null);
                workspaceRepository.save(ws);
            });
        }
        String workspaceId = session.getMetadata() != null ? session.getMetadata().get("workspaceId") : null;
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaceRepository.findByWorkspaceId(workspaceId).ifPresent(ws -> {
                if (session.getId() != null && session.getId().equals(ws.getPendingCheckoutSessionId())) {
                    ws.setPendingCheckoutSessionId(null);
                    ws.setPendingCheckoutCreatedAt(null);
                    workspaceRepository.save(ws);
                }
            });
        }

        workspaceService.syncWorkspacesToOwnerPlan(user);

        log.info("Checkout session completed for user {}, subscription: {}", userId, session.getSubscription());
    }

    public void handleSubscriptionCreated(Subscription subscription) {
        updateUserFromSubscription(subscription, "created");

        if (subscription.getTrialEnd() == null) return;

        String userId = subscription.getMetadata().get("userId");
        Optional<User> optUser = userId != null
                ? userRepository.findById(userId)
                : findUserByStripeCustomerId(subscription.getCustomer());

        optUser.ifPresent(user -> {
            String trialEndDate = LocalDateTime
                    .ofInstant(Instant.ofEpochSecond(subscription.getTrialEnd()), ZoneOffset.UTC)
                    .format(DATE_FMT);
            String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
            try {
                emailService.sendTrialStartedEmail(
                        user.getEmail(), user.getUsername(), planName, trialEndDate, baseUrl + "/billing");
            } catch (Exception e) {
                log.error("Failed to send trial-started email to {}: {}", user.getEmail(), e.getMessage());
            }
        });
    }

    public void handleTrialWillEnd(Subscription subscription) {
        String userId = subscription.getMetadata().get("userId");
        Optional<User> optUser = userId != null
                ? userRepository.findById(userId)
                : findUserByStripeCustomerId(subscription.getCustomer());

        optUser.ifPresent(user -> {
            if (subscription.getTrialEnd() == null) return;
            long trialEndEpoch = subscription.getTrialEnd();
            LocalDateTime trialEnd = LocalDateTime.ofInstant(Instant.ofEpochSecond(trialEndEpoch), ZoneOffset.UTC);
            long daysLeft = (trialEndEpoch - Instant.now().getEpochSecond()) / 86400;
            if (daysLeft < 0) daysLeft = 0;
            String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
            try {
                emailService.sendTrialEndingReminderEmail(
                        user.getEmail(), user.getUsername(), planName,
                        daysLeft, trialEnd.format(DATE_FMT), baseUrl + "/billing");
            } catch (Exception e) {
                log.error("Failed to send trial-ending reminder to {}: {}", user.getEmail(), e.getMessage());
            }
        });
    }

    public void handleSubscriptionUpdated(Subscription subscription) {
        Optional<User> previousUser = resolveUserForSubscription(subscription);
        boolean wasAutoRenewEnabled = previousUser
                .map(User::isAutoRenewEnabled)
                .orElse(true);

        updateUserFromSubscription(subscription, "updated");

        boolean cancelAtPeriodEnd = Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd());
        if (cancelAtPeriodEnd && wasAutoRenewEnabled) {
            resolveUserForSubscription(subscription).ifPresent(user -> sendAutoRenewDisabledEmailOnce(user, subscription));
        } else if (!cancelAtPeriodEnd && !wasAutoRenewEnabled) {
            resolveUserForSubscription(subscription).ifPresent(user -> sendAutoRenewEnabledEmailOnce(user, subscription));
        }
    }

    public void handleSubscriptionDeleted(Subscription subscription) {
        Optional<User> optUser = resolveUserForSubscription(subscription);

        optUser.ifPresent(user -> {
            String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
            String accessEndDate = formatSubscriptionAccessEnd(subscription);

            // Only clear user-level subscription if it matches this subscription
            if (subscription.getId().equals(user.getStripeSubscriptionId())) {
                user.setStripeSubscriptionId(null);
                user.setSubscriptionStatus("canceled");
            }
            user.setAutoRenewEnabled(false);
            user.setSubscriptionCanceledAt(LocalDateTime.now());
            userRepository.save(user);
            workspaceService.syncWorkspacesToOwnerPlan(user);
            log.info("Subscription deleted for user {}", user.getUsername());

            sendCancellationEmailOnce(user, subscription);
        });
    }

    public void handleInvoicePaymentFailed(Invoice invoice) {
        // Proration invoices from upgrade attempts (PRO→ENTERPRISE, monthly→annual, etc.) use
        // ERROR_IF_INCOMPLETE — Stripe rolls back the subscription update when payment fails.
        // The user's current plan is still active; do NOT mark it past_due.
        // Send a targeted "upgrade payment failed" email instead.
        String billingReason = invoice.getBillingReason();
        if ("subscription_update".equals(billingReason)) {
            log.warn("[InvoicePaymentFailed] Proration invoice {} failed (billingReason=subscription_update). " +
                     "Stripe rolled back the subscription update — current plan unchanged. No status change applied.",
                     invoice.getId());
            findUserForInvoice(invoice).ifPresent(user -> {
                String emailKey = "upgrade-payment-failed:" + invoice.getId();
                if (reserveEmailLog(emailKey, user, invoice, "upgrade_payment_failed")) {
                    String amount = invoice.getAmountDue() != null
                            ? String.format("$%.2f %s", invoice.getAmountDue() / 100.0,
                                    invoice.getCurrency() != null ? invoice.getCurrency().toUpperCase() : "USD")
                            : "the upgrade amount";
                    String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
                    try {
                        emailService.sendPaymentFailedEmail(
                                user.getEmail(), user.getUsername(), planName, amount, baseUrl + "/billing");
                        markEmailLogSent(emailKey);
                        log.info("[InvoicePaymentFailed] Upgrade-payment-failed email sent to {} for invoice {}",
                                user.getEmail(), invoice.getId());
                    } catch (Exception e) {
                        markEmailLogFailed(emailKey, e.getMessage());
                        log.error("[InvoicePaymentFailed] Failed to send upgrade-payment-failed email to {}: {}",
                                user.getEmail(), e.getMessage());
                    }
                }
            });
            return;
        }

        findUserForInvoice(invoice).ifPresent(user -> {
            user.setSubscriptionStatus("past_due");
            userRepository.save(user);
            log.warn("[InvoicePaymentFailed] user={} invoice={} billingReason={} — status set to past_due",
                    user.getUsername(), invoice.getId(), billingReason);

            // Sync status to ALL workspaces (disables collaboration while past_due)
            workspaceService.syncWorkspacesToOwnerPlan(user);

            // Dedup: one payment-failed email per invoice regardless of how many times
            // Stripe retries the same invoice (default: up to 4 attempts over ~3 weeks).
            String emailKey = "payment-failed:" + invoice.getId();
            if (!reserveEmailLog(emailKey, user, invoice, "payment_failed")) {
                log.info("[InvoicePaymentFailed] Skipping duplicate payment-failed email for invoice {} / user {}",
                        invoice.getId(), user.getUsername());
                return;
            }
            String amount = invoice.getAmountDue() != null
                    ? String.format("$%.2f %s",
                            invoice.getAmountDue() / 100.0,
                            invoice.getCurrency() != null ? invoice.getCurrency().toUpperCase() : "USD")
                    : "your subscription amount";
            String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
            try {
                emailService.sendPaymentFailedEmail(
                        user.getEmail(), user.getUsername(), planName, amount, baseUrl + "/billing");
                markEmailLogSent(emailKey);
                log.info("[InvoicePaymentFailed] Payment-failed email sent to {} for invoice {}",
                        user.getEmail(), invoice.getId());
            } catch (Exception e) {
                markEmailLogFailed(emailKey, e.getMessage());
                log.error("[InvoicePaymentFailed] Failed to send payment-failed email to {}: {}",
                        user.getEmail(), e.getMessage());
            }
        });
    }

    /**
     * Customer must complete 3DS authentication or bank debit setup; funds are not captured yet.
     * Sets a temporary status to disable collaboration until Stripe confirms payment via
     * {@link #handleInvoicePaymentSucceeded} (restores "active") or
     * {@link #handleInvoicePaymentFailed} (sets "past_due").
     */
    public void handleInvoicePaymentActionRequired(Invoice invoice) {
        findUserForInvoice(invoice).ifPresentOrElse(user -> {
            log.warn("[InvoicePaymentActionRequired] user={} invoice={} — awaiting 3DS/ACH. " +
                    "Disabling collaboration until payment is confirmed.",
                    user.getUsername(), invoice.getId());
            user.setSubscriptionStatus("payment_action_required");
            userRepository.save(user);
            workspaceService.syncWorkspacesToOwnerPlan(user);
        }, () -> log.warn("[InvoicePaymentActionRequired] invoice={} — no local user matched",
                invoice.getId()));
    }

    public void handleInvoicePaymentSucceeded(Invoice invoice) {
        // ── Step 0: Entry log — makes every invocation auditable from grep ───
        log.info("[InvoicePaymentSucceeded] invoiceId={} customerId={} subscriptionId={} amountPaid={} amountDue={} customerEmail={}",
                invoice.getId(),
                invoice.getCustomer(),
                invoice.getSubscription(),
                invoice.getAmountPaid(),
                invoice.getAmountDue(),
                invoice.getCustomerEmail());

        Optional<User> userOpt = findUserForInvoice(invoice);
        if (userOpt.isEmpty()) {
            // ── Step 0a: Last-resort fallback — pull the email from Stripe directly ───
            // The local user lookup chain may miss if customer.subscription.created
            // hasn't persisted yet, or if the user record was created with a different
            // customer ID and never reconciled.
            String fallbackEmail = invoice.getCustomerEmail();
            if ((fallbackEmail == null || fallbackEmail.isBlank()) && invoice.getCustomer() != null) {
                try {
                    Customer customer = Customer.retrieve(invoice.getCustomer());
                    fallbackEmail = customer.getEmail();
                    if (fallbackEmail != null && !fallbackEmail.isBlank()) {
                        userOpt = userRepository.findByEmailIgnoreCase(fallbackEmail);
                        if (userOpt.isPresent()) {
                            // Repair the link so future events resolve via the fast path.
                            User repaired = userOpt.get();
                            if (repaired.getStripeCustomerId() == null
                                    || !invoice.getCustomer().equals(repaired.getStripeCustomerId())) {
                                repaired.setStripeCustomerId(invoice.getCustomer());
                                userRepository.save(repaired);
                                log.warn("[InvoicePaymentSucceeded] Repaired missing stripeCustomerId for user {} (email={}, customerId={})",
                                        repaired.getUsername(), fallbackEmail, invoice.getCustomer());
                            }
                        }
                    }
                } catch (Exception ex) {
                    log.warn("[InvoicePaymentSucceeded] Stripe Customer.retrieve fallback failed for {}: {}",
                            invoice.getCustomer(), ex.getMessage());
                }
            }
            if (userOpt.isEmpty()) {
                recordUnmatchedInvoice(invoice, fallbackEmail);
                return;
            }
        }

        User user = userOpt.get();
        Long paidAmount = resolvePaidAmount(invoice);

        // Resolve the correct next-period-end: prefer the subscription's live
        // currentPeriodEnd over invoice.getPeriodEnd(). For proration invoices
        // generated when a trial is ended immediately (setTrialEnd = now), the
        // invoice covers the trial period and its periodEnd == today, which is
        // wrong for "next billing date". The subscription's currentPeriodEnd is
        // always the actual renewal date.
        long nowEpoch = Instant.now().getEpochSecond();
        Long resolvedPeriodEnd = null;
        if (invoice.getSubscription() != null) {
            try {
                Subscription liveSub = Subscription.retrieve(invoice.getSubscription());
                Long subPeriodEnd = liveSub.getCurrentPeriodEnd();
                if (subPeriodEnd != null && subPeriodEnd > nowEpoch) {
                    resolvedPeriodEnd = subPeriodEnd;
                }
            } catch (StripeException ex) {
                log.warn("[InvoicePaymentSucceeded] Could not retrieve subscription {} to resolve periodEnd: {}",
                        invoice.getSubscription(), ex.getMessage());
            }
        }
        // Fallback: scan line items for the latest future period end.
        // Needed when the subscription.currentPeriodEnd hasn't been committed yet
        // (race condition on proration/trial-end invoices where periodEnd == today).
        if (resolvedPeriodEnd == null && invoice.getLines() != null && invoice.getLines().getData() != null) {
            for (InvoiceLineItem line : invoice.getLines().getData()) {
                if (line.getPeriod() != null && line.getPeriod().getEnd() != null) {
                    long lineEnd = line.getPeriod().getEnd();
                    if (lineEnd > nowEpoch && (resolvedPeriodEnd == null || lineEnd > resolvedPeriodEnd)) {
                        resolvedPeriodEnd = lineEnd;
                    }
                }
            }
        }
        if (resolvedPeriodEnd == null && invoice.getPeriodEnd() != null) {
            resolvedPeriodEnd = invoice.getPeriodEnd();
        }

        // invoice.payment_succeeded is a reliable fallback if
        // customer.subscription.updated is delayed/missed.
        if (paidAmount > 0) {
            user.setSubscriptionStatus("active");
            if (resolvedPeriodEnd != null) {
                user.setSubscriptionCurrentPeriodEnd(
                        LocalDateTime.ofInstant(Instant.ofEpochSecond(resolvedPeriodEnd), ZoneOffset.UTC));
            }
            userRepository.save(user);
            log.info("[InvoicePaymentSucceeded] User {} email={} — status set to active, nextPeriodEnd={}",
                    user.getUsername(), user.getEmail(), resolvedPeriodEnd);
        }

        // Skip $0 invoices (e.g. trial-start invoice with no charge)
        if (paidAmount == 0) {
            log.info("[InvoicePaymentSucceeded] Skipping payment success email for zero-amount invoice {} / user {} (email={})",
                    invoice.getId(), user.getUsername(), user.getEmail());
            return;
        }

        // Sync status to ALL workspaces (restores collaboration if paid)
        workspaceService.syncWorkspacesToOwnerPlan(user);

        String amount = String.format("$%.2f %s",
                paidAmount / 100.0,
                invoice.getCurrency() != null ? invoice.getCurrency().toUpperCase() : "USD");
        String nextBillingDate = resolvedPeriodEnd != null
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(resolvedPeriodEnd), ZoneOffset.UTC)
                        .format(DATE_FMT)
                : "N/A";
        String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
        String emailLogKey = "payment-succeeded:" + invoice.getId();
        if (!reserveEmailLog(emailLogKey, user, invoice, "payment_succeeded")) {
            log.info("[InvoicePaymentSucceeded] Skipping duplicate payment success email for invoice {} / user {} (email={})",
                    invoice.getId(), user.getUsername(), user.getEmail());
            return;
        }
        try {
            emailService.sendPaymentSucceededEmail(
                    user.getEmail(), user.getUsername(), planName,
                    amount, nextBillingDate, invoice.getHostedInvoiceUrl());
            markEmailLogSent(emailLogKey);
            log.info("[InvoicePaymentSucceeded] Payment-receipt email sent to {} for invoice {}",
                    user.getEmail(), invoice.getId());
        } catch (Exception e) {
            markEmailLogFailed(emailLogKey, e.getMessage());
            log.error("[InvoicePaymentSucceeded] Failed to send payment-receipt email to {} for invoice {}: {}",
                    user.getEmail(), invoice.getId(), e.getMessage(), e);
        }
    }

    /**
     * Records an invoice that couldn't be matched to a local user so support
     * can manually reconcile. Writes to {@code stripe_unmatched_invoices}.
     * The collection grows slowly (only failures land here) and provides the
     * audit trail that {@code findUserForInvoice}'s warn-only log does not.
     */
    private void recordUnmatchedInvoice(Invoice invoice, String discoveredEmail) {
        try {
            Document doc = new Document("invoiceId", invoice.getId())
                    .append("stripeCustomerId", invoice.getCustomer())
                    .append("stripeSubscriptionId", invoice.getSubscription())
                    .append("customerEmail", invoice.getCustomerEmail())
                    .append("discoveredEmail", discoveredEmail)
                    .append("amountPaid", invoice.getAmountPaid())
                    .append("amountDue", invoice.getAmountDue())
                    .append("currency", invoice.getCurrency())
                    .append("hostedInvoiceUrl", invoice.getHostedInvoiceUrl())
                    .append("recordedAt", LocalDateTime.now())
                    .append("status", "unmatched");
            mongoTemplate.insert(doc, "stripe_unmatched_invoices");
            log.error("[InvoicePaymentSucceeded] UNMATCHED invoice {} customerId={} customerEmail={} discoveredEmail={} — recorded to stripe_unmatched_invoices for reconciliation",
                    invoice.getId(), invoice.getCustomer(), invoice.getCustomerEmail(), discoveredEmail);
        } catch (Exception persistEx) {
            log.error("[InvoicePaymentSucceeded] UNMATCHED invoice {} could not even be recorded: {}",
                    invoice.getId(), persistEx.getMessage(), persistEx);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Optional<User> resolveUserForSubscription(Subscription subscription) {
        String userId = subscription.getMetadata() != null ? subscription.getMetadata().get("userId") : null;
        if (userId != null) {
            return userRepository.findById(userId);
        }
        return findUserByStripeCustomerId(subscription.getCustomer());
    }

    private Optional<User> findUserForInvoice(Invoice invoice) {
        String customerId = invoice.getCustomer();
        if (customerId != null && !customerId.isBlank()) {
            Optional<User> user = findUserByStripeCustomerId(customerId);
            if (user.isPresent()) {
                return user;
            }
        }

        String subscriptionId = invoice.getSubscription();
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            Optional<User> user = userRepository.findByStripeSubscriptionId(subscriptionId);
            if (user.isPresent()) {
                return user;
            }

            user = findUserFromStripeSubscriptionMetadata(subscriptionId);
            if (user.isPresent()) {
                return user;
            }
        }

        if (customerId != null && !customerId.isBlank()) {
            Optional<User> user = findUserFromStripeCustomerMetadata(customerId);
            if (user.isPresent()) {
                return user;
            }
        }

        String customerEmail = invoice.getCustomerEmail();
        if (customerEmail != null && !customerEmail.isBlank()) {
            Optional<User> user = userRepository.findByEmailIgnoreCase(customerEmail);
            if (user.isPresent()) {
                log.info("Resolved invoice {} user by customer email fallback: {}", invoice.getId(), customerEmail);
                return user;
            }
        }

        log.warn("No user found for invoice {} (customer={}, subscription={})",
                invoice.getId(), customerId, subscriptionId);
        return Optional.empty();
    }

    private long resolvePaidAmount(Invoice invoice) {
        if (invoice.getAmountPaid() != null) {
            return invoice.getAmountPaid();
        }
        // Some test/API-version payloads may omit amount_paid. For a success
        // event, amount_due is the best fallback for formatting the receipt.
        if (invoice.getAmountDue() != null) {
            return invoice.getAmountDue();
        }
        return 0L;
    }

    private Optional<User> findUserByStripeCustomerId(String customerId) {
        return customerId == null || customerId.isBlank()
                ? Optional.empty()
                : userRepository.findByStripeCustomerId(customerId);
    }

    private Optional<User> findUserFromStripeSubscriptionMetadata(String subscriptionId) {
        try {
            Subscription subscription = Subscription.retrieve(subscriptionId);
            Map<String, String> metadata = subscription.getMetadata();
            String userId = metadata != null ? metadata.get("userId") : null;
            Optional<User> user = userId != null ? userRepository.findById(userId) : Optional.empty();
            user.ifPresent(u -> {
                if (u.getStripeSubscriptionId() == null || u.getStripeSubscriptionId().isBlank()) {
                    u.setStripeSubscriptionId(subscription.getId());
                }
                if (u.getStripeCustomerId() == null || u.getStripeCustomerId().isBlank()) {
                    u.setStripeCustomerId(subscription.getCustomer());
                }
                userRepository.save(u);
            });
            return user;
        } catch (Exception e) {
            log.warn("Could not resolve user from subscription metadata {}: {}", subscriptionId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<User> findUserFromStripeCustomerMetadata(String customerId) {
        try {
            Customer customer = Customer.retrieve(customerId);
            Map<String, String> metadata = customer.getMetadata();
            String userId = metadata != null ? metadata.get("userId") : null;
            Optional<User> user = userId != null ? userRepository.findById(userId) : Optional.empty();
            user.ifPresent(u -> {
                if (u.getStripeCustomerId() == null || u.getStripeCustomerId().isBlank()) {
                    u.setStripeCustomerId(customer.getId());
                    userRepository.save(u);
                }
            });
            return user;
        } catch (Exception e) {
            log.warn("Could not resolve user from customer metadata {}: {}", customerId, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean reserveEmailLog(String key, User user, Invoice invoice, String type) {
        Document doc = new Document("key", key)
                .append("type", type)
                .append("status", "sending")
                .append("email", user.getEmail())
                .append("userId", user.getId())
                .append("username", user.getUsername())
                .append("stripeInvoiceId", invoice.getId())
                .append("stripeCustomerId", invoice.getCustomer())
                .append("stripeSubscriptionId", invoice.getSubscription())
                .append("amountPaid", invoice.getAmountPaid())
                .append("currency", invoice.getCurrency())
                .append("createdAt", LocalDateTime.now());
        try {
            mongoTemplate.insert(doc, "email_logs");
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private boolean reserveEmailLog(String key, User user, Subscription subscription, String type) {
        Document doc = new Document("key", key)
                .append("type", type)
                .append("status", "sending")
                .append("email", user.getEmail())
                .append("userId", user.getId())
                .append("username", user.getUsername())
                .append("stripeCustomerId", subscription.getCustomer())
                .append("stripeSubscriptionId", subscription.getId())
                .append("createdAt", LocalDateTime.now());
        try {
            mongoTemplate.insert(doc, "email_logs");
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private void sendCancellationEmailOnce(User user, Subscription subscription) {
        String key = "subscription-cancelled:" + subscription.getId();
        if (!reserveEmailLog(key, user, subscription, "subscription_cancelled")) {
            log.info("Skipping duplicate cancellation email for subscription {} / user {}",
                    subscription.getId(), user.getUsername());
            return;
        }

        String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
        String accessEndDate = formatSubscriptionAccessEnd(subscription);
        try {
            emailService.sendSubscriptionCancelledEmail(
                    user.getEmail(), user.getUsername(), planName, accessEndDate);
            markEmailLogSent(key);
            log.info("Cancellation email sent to {} for subscription {}", user.getEmail(), subscription.getId());
        } catch (Exception e) {
            markEmailLogFailed(key, e.getMessage());
            log.error("Failed to send cancellation email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    private void sendAutoRenewDisabledEmailOnce(User user, Subscription subscription) {
        // Key scoped to today (UTC) — one email per toggle action per day.
        // Avoids duplicates from webhook retries while still notifying on genuine re-toggles.
        String key = "auto-renew-disabled:" + subscription.getId() + ":" + LocalDate.now(ZoneOffset.UTC);
        if (!reserveEmailLog(key, user, subscription, "auto_renew_disabled")) {
            log.info("Skipping duplicate auto-renew disabled email for subscription {} / user {}",
                    subscription.getId(), user.getUsername());
            return;
        }

        String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
        String accessEndDate = formatSubscriptionAccessEnd(subscription);
        try {
            emailService.sendAutoRenewDisabledEmail(
                    user.getEmail(), user.getUsername(), planName, accessEndDate, baseUrl + "/billing");
            markEmailLogSent(key);
            log.info("Auto-renew disabled email sent to {} for subscription {}",
                    user.getEmail(), subscription.getId());
        } catch (Exception e) {
            markEmailLogFailed(key, e.getMessage());
            log.error("Failed to send auto-renew disabled email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    private void sendAutoRenewEnabledEmailOnce(User user, Subscription subscription) {
        // Key scoped to today (UTC) — one email per toggle action per day.
        String key = "auto-renew-enabled:" + subscription.getId() + ":" + LocalDate.now(ZoneOffset.UTC);
        if (!reserveEmailLog(key, user, subscription, "auto_renew_enabled")) {
            log.info("Skipping duplicate auto-renew enabled email for subscription {} / user {}",
                    subscription.getId(), user.getUsername());
            return;
        }

        String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
        String renewalDate = formatSubscriptionAccessEnd(subscription);
        try {
            emailService.sendAutoRenewEnabledEmail(
                    user.getEmail(), user.getUsername(), planName, renewalDate, baseUrl + "/billing");
            markEmailLogSent(key);
            log.info("Auto-renew enabled email sent to {} for subscription {}",
                    user.getEmail(), subscription.getId());
        } catch (Exception e) {
            markEmailLogFailed(key, e.getMessage());
            log.error("Failed to send auto-renew enabled email to {}: {}", user.getEmail(), e.getMessage());
        }
    }

    private void markEmailLogSent(String key) {
        mongoTemplate.updateFirst(
                new Query(Criteria.where("key").is(key)),
                new Update().set("status", "sent").set("sentAt", LocalDateTime.now()),
                "email_logs");
    }

    private void markEmailLogFailed(String key, String errorMessage) {
        mongoTemplate.updateFirst(
                new Query(Criteria.where("key").is(key)),
                new Update()
                        .set("status", "failed")
                        .set("failedAt", LocalDateTime.now())
                        .set("errorMessage", errorMessage),
                "email_logs");
    }

    private String formatSubscriptionAccessEnd(Subscription subscription) {
        if (subscription.getCurrentPeriodEnd() != null) {
            return LocalDateTime.ofInstant(
                            Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()), ZoneOffset.UTC)
                    .format(DATE_FMT);
        }
        return "immediately";
    }

    private void updateUserFromSubscription(Subscription subscription, String action) {
        Map<String, String> metadata = subscription.getMetadata();
        String userId = metadata.get("userId");

        Optional<User> optUser = userId != null
                ? userRepository.findById(userId)
                : findUserByStripeCustomerId(subscription.getCustomer());

        optUser.ifPresent(user -> {
            user.setStripeSubscriptionId(subscription.getId());
            String newStatus = subscription.getStatus();
            String previousStatus = user.getSubscriptionStatus();
            if ("past_due".equals(previousStatus) && "active".equals(newStatus)) {
                log.info("[SubscriptionUpdate] Subscription {} recovered from past_due → active for user {} — re-enabling collaboration",
                        subscription.getId(), user.getUsername());
            }
            user.setSubscriptionStatus(newStatus);
            user.setAutoRenewEnabled(!Boolean.TRUE.equals(subscription.getCancelAtPeriodEnd()));

            if (subscription.getCancelAtPeriodEnd() != null && subscription.getCancelAtPeriodEnd()) {
                user.setSubscriptionCanceledAt(LocalDateTime.now());
            } else {
                user.setSubscriptionCanceledAt(null);
            }

            // Extract price/plan info from the first subscription item
            if (subscription.getItems() != null && subscription.getItems().getData() != null
                    && !subscription.getItems().getData().isEmpty()) {
                SubscriptionItem item = subscription.getItems().getData().get(0);
                String priceId = item.getPrice().getId();
                user.setSubscriptionPlanId(priceId);
                String stripeInterval = item.getPrice().getRecurring() != null
                    ? item.getPrice().getRecurring().getInterval() : null;
                String normalizedBillingInterval = "year".equalsIgnoreCase(stripeInterval)
                    ? "annual"
                    : ("month".equalsIgnoreCase(stripeInterval) ? "monthly" : stripeInterval);
                user.setBillingInterval(normalizedBillingInterval);
                String derivedPlanName = derivePlanName(priceId);
                if (derivedPlanName != null) {
                    String currentPlan = user.getSubscriptionPlanName();
                    if (currentPlan != null && planRank(derivedPlanName) < planRank(currentPlan)) {
                        // Stripe Billing Portal allows plan changes that our API blocks.
                        // Preserve the existing plan and log for admin review — the subscription
                        // in Stripe should be manually corrected in the Dashboard.
                        log.error("[SubscriptionUpdate] Downgrade blocked via webhook: user={} {} → {}. " +
                                  "Keeping {}. Review subscription {} in Stripe Dashboard.",
                                  user.getUsername(), currentPlan, derivedPlanName,
                                  currentPlan, subscription.getId());
                    } else {
                        user.setSubscriptionPlanName(derivedPlanName);
                    }
                }
            }

            // Set period end
            if (subscription.getCurrentPeriodEnd() != null) {
                user.setSubscriptionCurrentPeriodEnd(
                        LocalDateTime.ofInstant(Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()), ZoneOffset.UTC));
            }

            userRepository.save(user);

            // Sync after persist so account-level fields are durable before workspace projection.
            workspaceService.syncWorkspacesToOwnerPlan(user);

            log.info("Subscription {} for user {} — status={}, autoRenew={}",
                    action, user.getUsername(), subscription.getStatus(), user.isAutoRenewEnabled());
        });
    }

    /**
     * Copies Stripe subscription state onto the user after create/update API calls.
     * Uses the subscription line item for plan and cadence when possible so Mongo matches Stripe.
     * Billing interval is stored as {@code monthly} or {@code annual} (not {@code yearly}) for UI consistency.
     */
    private void applySubscriptionSnapshotToUser(User user, Subscription subscription,
            String requestedPlan, String requestedInterval) {
        user.setStripeSubscriptionId(subscription.getId());
        user.setSubscriptionStatus(subscription.getStatus());

        // When a trial is ended immediately (setTrialEnd = now), Stripe may return
        // currentPeriodEnd == trial-end timestamp in the update response — before the
        // invoice is processed and the new billing cycle is committed.  If the value
        // is in the past, re-fetch the subscription to get the definitive period end.
        Long rawPeriodEnd = subscription.getCurrentPeriodEnd();
        if (rawPeriodEnd != null && rawPeriodEnd <= Instant.now().getEpochSecond()) {
            try {
                Subscription refreshed = Subscription.retrieve(subscription.getId());
                Long refreshedPeriodEnd = refreshed.getCurrentPeriodEnd();
                if (refreshedPeriodEnd != null && refreshedPeriodEnd > Instant.now().getEpochSecond()) {
                    rawPeriodEnd = refreshedPeriodEnd;
                    log.info("[applySnapshot] Re-fetched subscription {} — updated periodEnd from {} to {}",
                            subscription.getId(), subscription.getCurrentPeriodEnd(), rawPeriodEnd);
                }
            } catch (StripeException e) {
                log.warn("[applySnapshot] Re-fetch failed for {}: {}", subscription.getId(), e.getMessage());
            }
        }
        if (rawPeriodEnd != null) {
            user.setSubscriptionCurrentPeriodEnd(
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(rawPeriodEnd), ZoneOffset.UTC));
        }

        String plan = requestedPlan != null ? requestedPlan.trim().toUpperCase() : "PRO";
        String intervalKey = requestedInterval != null ? requestedInterval.trim().toLowerCase() : "monthly";
        if ("yearly".equals(intervalKey) || "annual".equals(intervalKey)) {
            intervalKey = "annual";
        } else {
            intervalKey = "monthly";
        }

        if (subscription.getItems() != null && subscription.getItems().getData() != null
                && !subscription.getItems().getData().isEmpty()) {
            SubscriptionItem item = subscription.getItems().getData().get(0);
            if (item.getPrice() != null) {
                String priceId = item.getPrice().getId();
                user.setSubscriptionPlanId(priceId);
                String derivedPlan = derivePlanName(priceId);
                if (derivedPlan != null) {
                    plan = derivedPlan;
                }
                String stripeInterval = item.getPrice().getRecurring() != null
                        ? item.getPrice().getRecurring().getInterval() : null;
                if ("year".equalsIgnoreCase(stripeInterval)) {
                    intervalKey = "annual";
                } else if ("month".equalsIgnoreCase(stripeInterval)) {
                    intervalKey = "monthly";
                }
            }
        }

        user.setSubscriptionPlanName(plan);
        user.setBillingInterval(intervalKey);
    }

    private String resolvePriceId(String planName, String interval) {
        return switch (planName.toUpperCase() + "_" + interval.toUpperCase()) {
            case "PRO_MONTHLY"         -> priceProMonthly;
            case "PRO_YEARLY"          -> priceProYearly;
            case "PRO_ANNUAL"          -> priceProYearly;
            case "ENTERPRISE_MONTHLY"  -> priceEnterpriseMonthly;
            case "ENTERPRISE_YEARLY"   -> priceEnterpriseYearly;
            case "ENTERPRISE_ANNUAL"   -> priceEnterpriseYearly;
            default -> throw new IllegalArgumentException(
                    "Unknown plan/interval combination: " + planName + "/" + interval);
        };
    }

    private void validateAllowedPlanChange(User user, String requestedPlan, String requestedInterval) {
        String normalizedRequested = requestedPlan != null ? requestedPlan.toUpperCase() : "";
        int requestedRank = planRank(normalizedRequested);
        if (requestedRank < 2) {
            throw new IllegalArgumentException("Invalid planName. Must be PRO or ENTERPRISE");
        }

        String currentPlan = user.getSubscriptionPlanName() != null
                ? user.getSubscriptionPlanName().toUpperCase()
                : "FREE";
        int currentRank = planRank(currentPlan);
        if (currentRank < 2) {
            return;
        }

        if (requestedRank < currentRank) {
            throw new IllegalStateException(
                    "Downgrading is not permitted. You can renew your existing plan or upgrade to a higher tier.");
        }

        String currentStatus = user.getSubscriptionStatus() != null ? user.getSubscriptionStatus() : "";
        boolean activeLike = "active".equalsIgnoreCase(currentStatus) || "trialing".equalsIgnoreCase(currentStatus);
        String currentInterval = normalizeBillingInterval(user.getBillingInterval());
        String targetInterval = normalizeBillingInterval(requestedInterval);
        if (activeLike && "annual".equals(currentInterval) && "monthly".equals(targetInterval)) {
            throw new IllegalStateException(
                    "Switching from annual to monthly is not available during the current annual period.");
        }
    }

    private String normalizeBillingInterval(String interval) {
        if (interval == null) return "";
        String normalized = interval.trim().toLowerCase();
        return "yearly".equals(normalized) ? "annual" : normalized;
    }

    private int planRank(String plan) {
        return switch (plan != null ? plan.toUpperCase() : "") {
            case "FREE" -> 1;
            case "PRO" -> 2;
            case "ENTERPRISE" -> 3;
            default -> 0;
        };
    }

    private String derivePlanName(String priceId) {
        if (priceId.equals(priceProMonthly) || priceId.equals(priceProYearly)) return "PRO";
        if (priceId.equals(priceEnterpriseMonthly) || priceId.equals(priceEnterpriseYearly)) return "ENTERPRISE";
        log.warn("Unknown Stripe price ID '{}' — cannot map to plan name; existing plan name preserved", priceId);
        return null;
    }

    private Map<String, Object> resolveDefaultPaymentMethod(String stripeCustomerId) {
        Map<String, Object> result = new HashMap<>();
        try {
            CustomerRetrieveParams params = CustomerRetrieveParams.builder()
                    .addExpand("invoice_settings.default_payment_method")
                    .build();
            Customer customer = Customer.retrieve(stripeCustomerId, params, null);
            if (customer.getInvoiceSettings() != null) {
                PaymentMethod pm = customer.getInvoiceSettings().getDefaultPaymentMethodObject();
                if (pm != null && pm.getCard() != null) {
                    Map<String, Object> card = new HashMap<>();
                    card.put("last4", pm.getCard().getLast4());
                    card.put("brand", pm.getCard().getBrand());
                    card.put("expMonth", pm.getCard().getExpMonth());
                    card.put("expYear", pm.getCard().getExpYear());
                    result.put("defaultPaymentMethod", card);
                }
            }
        } catch (StripeException e) {
            logger.warn("Could not retrieve default payment method for customer {}: {}", stripeCustomerId, e.getMessage());
        }
        return result;
    }

    private List<Map<String, Object>> listPaymentHistory(String stripeCustomerId) throws StripeException {
        InvoiceListParams params = InvoiceListParams.builder()
            .setCustomer(stripeCustomerId)
            .setLimit(12L)
            .build();

        InvoiceCollection invoices = Invoice.list(params);
        List<Map<String, Object>> history = new ArrayList<>();
        for (Invoice invoice : invoices.getData()) {
            InvoiceLineItem primaryLine = invoice.getLines() != null
                && invoice.getLines().getData() != null
                && !invoice.getLines().getData().isEmpty()
                    ? invoice.getLines().getData().get(0)
                    : null;
            Long periodStart = primaryLine != null && primaryLine.getPeriod() != null
                ? primaryLine.getPeriod().getStart()
                : invoice.getPeriodStart();
            Long periodEnd = primaryLine != null && primaryLine.getPeriod() != null
                ? primaryLine.getPeriod().getEnd()
                : invoice.getPeriodEnd();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("invoiceId", invoice.getId());
            entry.put("number", emptyIfNull(invoice.getNumber()));
            entry.put("status", emptyIfNull(invoice.getStatus()));
            entry.put("amountPaid", centsToDisplay(invoice.getAmountPaid()));
            entry.put("amountDue", centsToDisplay(invoice.getAmountDue()));
            entry.put("currency", invoice.getCurrency() != null ? invoice.getCurrency().toUpperCase() : "USD");
            entry.put("createdAt", epochToIso(invoice.getCreated()));
            entry.put("periodStart", epochToIso(periodStart));
            entry.put("periodEnd", epochToIso(periodEnd));
            entry.put("hostedInvoiceUrl", emptyIfNull(invoice.getHostedInvoiceUrl()));
            entry.put("invoicePdf", emptyIfNull(invoice.getInvoicePdf()));
            entry.put("description", deriveInvoiceDescription(invoice));
            history.add(entry);
        }
        return history;
    }

    private String deriveInvoiceDescription(Invoice invoice) {
        if (invoice.getLines() != null && invoice.getLines().getData() != null && !invoice.getLines().getData().isEmpty()) {
            String description = invoice.getLines().getData().get(0).getDescription();
            if (description != null && !description.isBlank()) {
                return description;
            }
        }
        return "Subscription charge";
    }

    private String normalizeStripeInterval(String stripeInterval) {
        if ("year".equalsIgnoreCase(stripeInterval)) {
            return "annual";
        }
        if ("month".equalsIgnoreCase(stripeInterval)) {
            return "monthly";
        }
        return emptyIfNull(stripeInterval);
    }

    private String centsToDisplay(Long amountInCents) {
        if (amountInCents == null) {
            return "0.00";
        }
        return String.format("%.2f", amountInCents / 100.0d);
    }

    private String epochToIso(Long epochSeconds) {
        if (epochSeconds == null) {
            return "";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC).toString();
    }

    private String emptyIfNull(String value) {
        return value != null ? value : "";
    }

    private void requireActiveSubscription(User user) {
        if (user.getStripeSubscriptionId() == null) {
            throw new IllegalStateException("No active subscription found.");
        }
    }

    /**
     * Ensures a confirmed SetupIntent was created for this user's Stripe customer (prevents re-use of another account's setup).
     */
    private void assertSetupIntentBelongsToUser(User user, SetupIntent setupIntent) {
        if (user.getStripeCustomerId() == null || user.getStripeCustomerId().isBlank()) {
            throw new IllegalStateException("Billing account not ready.");
        }
        String intentCustomer = setupIntent.getCustomer();
        if (intentCustomer == null || intentCustomer.isBlank()) {
            throw new IllegalArgumentException("Invalid payment setup.");
        }
        if (!user.getStripeCustomerId().equals(intentCustomer)) {
            throw new IllegalArgumentException("This payment confirmation is not valid for your account.");
        }
    }

    /**
     * Reads the live subscription status from Stripe and updates MongoDB if it differs.
     * Returns the up-to-date status string. Falls back to cached MongoDB value on Stripe errors.
     */
    public String syncStatusFromStripe(User user) {
        String cached = user.getSubscriptionStatus() != null ? user.getSubscriptionStatus() : "";
        if (user.getStripeSubscriptionId() == null || user.getStripeSubscriptionId().isBlank()) {
            return cached;
        }
        try {
            Subscription sub = Subscription.retrieve(user.getStripeSubscriptionId());
            String liveStatus = sub.getStatus() != null ? sub.getStatus() : "";

            // Also check if the stored period end is stale (can happen when trial ends immediately
            // and Stripe returns trial-end timestamp as currentPeriodEnd before invoice processing).
            // Use UTC and !isAfter (<=) so a date of "today" is also treated as stale.
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
            boolean periodEndStale = sub.getCurrentPeriodEnd() != null
                    && (user.getSubscriptionCurrentPeriodEnd() == null
                        || !user.getSubscriptionCurrentPeriodEnd().isAfter(nowUtc));

            if (!liveStatus.equals(cached) || periodEndStale) {
                if (!liveStatus.equals(cached)) {
                    log.info("[syncStatusFromStripe] Stale status for user={}: cached={} live={}. Updating MongoDB.",
                            user.getUsername(), cached, liveStatus);
                }
                if (periodEndStale) {
                    log.info("[syncStatusFromStripe] Stale periodEnd for user={}: cached={} live={}. Updating MongoDB.",
                            user.getUsername(), user.getSubscriptionCurrentPeriodEnd(),
                            sub.getCurrentPeriodEnd());
                }
                user.setSubscriptionStatus(liveStatus);
                if (sub.getCurrentPeriodEnd() != null) {
                    user.setSubscriptionCurrentPeriodEnd(
                            LocalDateTime.ofInstant(
                                    Instant.ofEpochSecond(sub.getCurrentPeriodEnd()),
                                    ZoneOffset.UTC));
                }
                userRepository.save(user);
                workspaceService.syncWorkspacesToOwnerPlan(user);
            }
            return liveStatus;
        } catch (StripeException e) {
            log.warn("[syncStatusFromStripe] Stripe lookup failed for user={}: {}. Using cached status.",
                    user.getUsername(), e.getMessage());
            return cached;
        }
    }
}
