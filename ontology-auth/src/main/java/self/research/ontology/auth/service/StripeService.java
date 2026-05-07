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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.model.Workspace;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.time.Instant;
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

    public StripeService(UserRepository userRepository, WorkspaceRepository workspaceRepository,
                         WorkspaceService workspaceService, EmailService emailService) {
        this.userRepository = userRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceService = workspaceService;
        this.emailService = emailService;
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

        validateAllowedPlanChange(user, planName);

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
            String idempotencyKey = "sub-update-" + user.getId() + "-" + subscription.getId() + "-" + priceId;

            SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                    .setDefaultPaymentMethod(paymentMethodId)
                    .addItem(
                            SubscriptionUpdateParams.Item.builder()
                                    .setId(itemId)
                                    .setPrice(priceId)
                                    .build()
                    )
                    // Keep the subscription running; Stripe will compute prorations based on their config.
                    .setCancelAtPeriodEnd(false)
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
                    .putMetadata("planName", planName.toUpperCase())
                    .putMetadata("billingInterval", interval.toLowerCase())
                    .putMetadata("workspaceId", workspaceId != null ? workspaceId : "")
                    .build();

            subscription = subscription.update(
                    params,
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

            boolean firstEverSubscription = !user.isHasUsedFreeTrial()
                    && user.getFirstSubscriptionAt() == null;
            if (firstEverSubscription && trialPeriodDays != null && trialPeriodDays > 0L) {
                createParams.setTrialPeriodDays(trialPeriodDays);
                log.info("Granting {}-day trial to user {} (first ever subscription)",
                        trialPeriodDays, user.getUsername());
            } else {
                log.info("Skipping trial for user {} (hasUsedFreeTrial={}, firstSubscriptionAt={}). " +
                        "Card will be charged immediately.",
                        user.getUsername(), user.isHasUsedFreeTrial(), user.getFirstSubscriptionAt());
            }

            subscription = com.stripe.model.Subscription.create(createParams.build());

            // Mark trial as consumed in the same transaction as the
            // subscription create — see userRepository.save below.
            user.setHasUsedFreeTrial(true);
            if (user.getFirstSubscriptionAt() == null) {
                user.setFirstSubscriptionAt(LocalDateTime.now());
            }
        }

        // Update user-level subscription reference
        user.setStripeSubscriptionId(subscription.getId());
        user.setSubscriptionStatus(subscription.getStatus());
        user.setSubscriptionPlanName(planName.toUpperCase());
        user.setBillingInterval(interval);
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
     * Cancels the account subscription immediately and syncs all owned workspaces.
     * Paid workspace access remains blocked until the user renews the plan.
     * customer.subscription.deleted at period end — at which point handleSubscriptionDeleted
     *
     */
    public void cancelAccountSubscription(User user) throws StripeException {
        String subId = user.getStripeSubscriptionId();
        if (subId == null || subId.isBlank()) {
            throw new IllegalStateException("No active account subscription found.");
        }

        Subscription subscription = Subscription.retrieve(subId);
        subscription.cancel();

        // Mark locally as canceled so workspace access is blocked immediately.
        user.setSubscriptionStatus("canceled");
        user.setAutoRenewEnabled(false);
        user.setSubscriptionCanceledAt(LocalDateTime.now());
        // Keep status as "active"/"trialing" — access continues until period end
        userRepository.save(user);
        workspaceService.syncWorkspacesToOwnerPlan(user);
        log.info("Account subscription {} canceled for user {}", subId, user.getUsername());
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
        validateAllowedPlanChange(user, planName);

        String priceId = resolvePriceId(planName, interval);
        Customer customer = getOrCreateCustomer(user);

        // Bug #39 / #40: trial is granted only on the first ever subscription.
        SessionCreateParams.SubscriptionData.Builder subData = SessionCreateParams.SubscriptionData.builder()
                .putMetadata("userId", user.getId())
                .putMetadata("planName", planName.toUpperCase())
                .putMetadata("billingInterval", interval.toLowerCase())
                .putMetadata("workspaceId", workspaceId != null ? workspaceId : "");
        boolean firstEverSubscription = !user.isHasUsedFreeTrial()
                && user.getFirstSubscriptionAt() == null;
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

        Subscription.retrieve(user.getStripeSubscriptionId()).update(params, options);

        user.setAutoRenewEnabled(false);
        userRepository.save(user);
        log.info("Auto-renew disabled for user {} subscription {}", user.getUsername(), user.getStripeSubscriptionId());
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

        Subscription.retrieve(user.getStripeSubscriptionId()).update(params, options);

        user.setAutoRenewEnabled(true);
        user.setSubscriptionCanceledAt(null);
        userRepository.save(user);
        log.info("Auto-renew re-enabled for user {} subscription {}", user.getUsername(), user.getStripeSubscriptionId());
    }

    /**
     * Immediately cancels the subscription (prorated refund not issued).
     */
    public void cancelSubscriptionImmediately(User user) throws StripeException {
        requireActiveSubscription(user);

        Subscription subscription = Subscription.retrieve(user.getStripeSubscriptionId());
        subscription.cancel();

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
        userRepository.save(user);

        // Clear pending lock and immediately activate the workspace
        String workspaceId = session.getMetadata() != null ? session.getMetadata().get("workspaceId") : null;
        if (workspaceId != null && !workspaceId.isBlank()) {
        // Sync status to ALL workspaces
        workspaceService.syncWorkspacesToOwnerPlan(user);
        }

        log.info("Checkout session completed for user {}, subscription: {}", userId, session.getSubscription());
    }

    public void handleSubscriptionCreated(Subscription subscription) {
        updateUserFromSubscription(subscription, "created");

        if (subscription.getTrialEnd() == null) return;

        String userId = subscription.getMetadata().get("userId");
        Optional<User> optUser = userId != null
                ? userRepository.findById(userId)
                : userRepository.findAll().stream()
                        .filter(u -> subscription.getCustomer().equals(u.getStripeCustomerId()))
                        .findFirst();

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
                : userRepository.findAll().stream()
                        .filter(u -> subscription.getCustomer().equals(u.getStripeCustomerId()))
                        .findFirst();

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
        updateUserFromSubscription(subscription, "updated");
    }

    public void handleSubscriptionDeleted(Subscription subscription) {
        String userId = subscription.getMetadata().get("userId");
        Optional<User> optUser = userId != null
                ? userRepository.findById(userId)
                : userRepository.findAll().stream()
                        .filter(u -> subscription.getCustomer().equals(u.getStripeCustomerId()))
                        .findFirst();

        optUser.ifPresent(user -> {
            String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
            String accessEndDate = subscription.getCurrentPeriodEnd() != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()), ZoneOffset.UTC)
                            .format(DATE_FMT)
                    : "immediately";

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

            try {
                emailService.sendSubscriptionCancelledEmail(
                        user.getEmail(), user.getUsername(), planName, accessEndDate);
            } catch (Exception e) {
                log.error("Failed to send cancellation email to {}: {}", user.getEmail(), e.getMessage());
            }
        });
    }

    public void handleInvoicePaymentFailed(Invoice invoice) {
        String customerId = invoice.getCustomer();
        userRepository.findAll().stream()
                .filter(u -> customerId.equals(u.getStripeCustomerId()))
                .findFirst()
                .ifPresent(user -> {
                    user.setSubscriptionStatus("past_due");
                    userRepository.save(user);
                    log.warn("Invoice payment failed for user {} — status set to past_due", user.getUsername());

                    // Sync status to ALL workspaces
                    workspaceService.syncWorkspacesToOwnerPlan(user);

                    String amount = invoice.getAmountDue() != null
                            ? String.format("$%.2f", invoice.getAmountDue() / 100.0) : "your subscription amount";
                    String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
                    try {
                        emailService.sendPaymentFailedEmail(
                                user.getEmail(), user.getUsername(), planName, amount, baseUrl + "/billing");
                    } catch (Exception e) {
                        log.error("Failed to send payment-failed email to {}: {}", user.getEmail(), e.getMessage());
                    }
                });
    }

    public void handleInvoicePaymentSucceeded(Invoice invoice) {
        String customerId = invoice.getCustomer();
        userRepository.findAll().stream()
                .filter(u -> customerId.equals(u.getStripeCustomerId()))
                .findFirst()
                .ifPresent(user -> {
                    if ("past_due".equals(user.getSubscriptionStatus())) {
                        user.setSubscriptionStatus("active");
                        userRepository.save(user);
                        log.info("Invoice payment recovered for user {} — status set back to active", user.getUsername());
                    }

                    // Skip $0 invoices (e.g. trial-start invoice with no charge)
                    if (invoice.getAmountPaid() == null || invoice.getAmountPaid() == 0) return;

                    // Sync status to ALL workspaces (restores collaboration if paid)
                    workspaceService.syncWorkspacesToOwnerPlan(user);

                    String amount = String.format("$%.2f %s",
                            invoice.getAmountPaid() / 100.0,
                            invoice.getCurrency() != null ? invoice.getCurrency().toUpperCase() : "USD");
                    String nextBillingDate = invoice.getPeriodEnd() != null
                            ? LocalDateTime.ofInstant(Instant.ofEpochSecond(invoice.getPeriodEnd()), ZoneOffset.UTC)
                                    .format(DATE_FMT)
                            : "N/A";
                    String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
                    try {
                        emailService.sendPaymentSucceededEmail(
                                user.getEmail(), user.getUsername(), planName,
                                amount, nextBillingDate, invoice.getHostedInvoiceUrl());
                    } catch (Exception e) {
                        log.error("Failed to send payment-receipt email to {}: {}", user.getEmail(), e.getMessage());
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updateUserFromSubscription(Subscription subscription, String action) {
        Map<String, String> metadata = subscription.getMetadata();
        String userId = metadata.get("userId");

        Optional<User> optUser = userId != null
                ? userRepository.findById(userId)
                : userRepository.findAll().stream()
                        .filter(u -> subscription.getCustomer().equals(u.getStripeCustomerId()))
                        .findFirst();

        optUser.ifPresent(user -> {
            user.setStripeSubscriptionId(subscription.getId());
            user.setSubscriptionStatus(subscription.getStatus());
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
                user.setSubscriptionPlanName(derivePlanName(priceId));
            }

            // Set period end
            if (subscription.getCurrentPeriodEnd() != null) {
                user.setSubscriptionCurrentPeriodEnd(
                        LocalDateTime.ofInstant(Instant.ofEpochSecond(subscription.getCurrentPeriodEnd()), ZoneOffset.UTC));
            }

            // Sync to ALL user-owned workspaces (Account-level billing)
            workspaceService.syncWorkspacesToOwnerPlan(user);

            userRepository.save(user);
            log.info("Subscription {} for user {} — status={}, autoRenew={}",
                    action, user.getUsername(), subscription.getStatus(), user.isAutoRenewEnabled());
        });
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

    private void validateAllowedPlanChange(User user, String requestedPlan) {
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
        return "UNKNOWN";
    }

    private List<Map<String, Object>> listPaymentHistory(String stripeCustomerId) throws StripeException {
        InvoiceListParams params = InvoiceListParams.builder()
            .setCustomer(stripeCustomerId)
            .setLimit(12L)
            .build();

        InvoiceCollection invoices = Invoice.list(params);
        List<Map<String, Object>> history = new ArrayList<>();
        for (Invoice invoice : invoices.getData()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("invoiceId", invoice.getId());
            entry.put("number", emptyIfNull(invoice.getNumber()));
            entry.put("status", emptyIfNull(invoice.getStatus()));
            entry.put("amountPaid", centsToDisplay(invoice.getAmountPaid()));
            entry.put("amountDue", centsToDisplay(invoice.getAmountDue()));
            entry.put("currency", invoice.getCurrency() != null ? invoice.getCurrency().toUpperCase() : "USD");
            entry.put("createdAt", epochToIso(invoice.getCreated()));
            entry.put("periodStart", epochToIso(invoice.getPeriodStart()));
            entry.put("periodEnd", epochToIso(invoice.getPeriodEnd()));
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
}
