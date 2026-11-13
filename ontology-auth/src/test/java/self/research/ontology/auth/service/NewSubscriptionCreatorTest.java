package self.research.ontology.auth.service;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.Invoice;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Subscription;
import com.stripe.param.SubscriptionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import self.research.ontology.auth.model.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NewSubscriptionCreatorTest {

    static class FakeApi implements SubscriptionStripeApi {
        final Map<String, Subscription> existing = new HashMap<>();
        final List<String> cancelled = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        Subscription toCreate;
        boolean failCancel;
        SubscriptionCreateParams lastParams;

        @Override
        public Subscription retrieve(String id) {
            events.add("retrieve:" + id);
            return existing.get(id);
        }

        @Override
        public Subscription cancel(Subscription s) throws StripeException {
            events.add("cancel:" + s.getId());
            if (failCancel) {
                throw new ApiConnectionException("boom");
            }
            cancelled.add(s.getId());
            return s;
        }

        @Override
        public Subscription create(SubscriptionCreateParams params, String key) {
            events.add("create");
            lastParams = params;
            return toCreate;
        }
    }

    private FakeApi api;
    private NewSubscriptionCreator creator;
    private User user;

    @BeforeEach
    void setUp() {
        api = new FakeApi();
        creator = new NewSubscriptionCreator(api);
        user = new User();
        user.setId("u1");
        user.setStripeCustomerId("cus_1");
    }

    private static Subscription sub(String id, String status, String customer, String piStatus, String secret) {
        Subscription s = new Subscription();
        s.setId(id);
        s.setStatus(status);
        s.setCustomer(customer);
        if (piStatus != null) {
            PaymentIntent pi = new PaymentIntent();
            pi.setStatus(piStatus);
            pi.setClientSecret(secret);
            Invoice inv = new Invoice();
            inv.setPaymentIntentObject(pi);
            s.setLatestInvoiceObject(inv);
        }
        return s;
    }

    private SubscriptionCreateParams.Builder params() {
        return SubscriptionCreateParams.builder().setCustomer("cus_1");
    }

    @Test
    void requiresActionWithClientSecretWhenIncompleteAndPaymentIntentRequiresAction() throws Exception {
        api.toCreate = sub("sub_new", "incomplete", "cus_1", "requires_action", "pi_x_secret_y");
        Subscription created = creator.create(user, params(), "k");
        SubscriptionResult result = creator.resultFor(created);
        assertEquals("sub_new", result.subscriptionId());
        assertEquals("incomplete", result.status());
        assertTrue(result.requiresAction());
        assertEquals("pi_x_secret_y", result.clientSecret());
        assertEquals("pi_x_secret_y", result.toResponse().get("clientSecret"));
        assertTrue(api.cancelled.isEmpty());
    }

    @Test
    void requestExpandsLatestInvoicePaymentIntent() throws Exception {
        api.toCreate = sub("sub_new", "active", "cus_1", null, null);
        creator.create(user, params(), "k");
        assertTrue(api.lastParams.getExpand().contains("latest_invoice.payment_intent"));
    }

    @Test
    void activeSubscriptionDoesNotRequireActionAndOmitsSecret() throws Exception {
        api.toCreate = sub("sub_new", "active", "cus_1", "succeeded", "secret");
        SubscriptionResult result = creator.resultFor(creator.create(user, params(), "k"));
        assertFalse(result.requiresAction());
        assertEquals("active", result.status());
        assertFalse(result.toResponse().containsKey("clientSecret"));
    }

    @Test
    void trialingWithoutInvoiceDoesNotRequireAction() throws Exception {
        api.toCreate = sub("sub_new", "trialing", "cus_1", null, null);
        SubscriptionResult result = creator.resultFor(creator.create(user, params(), "k"));
        assertFalse(result.requiresAction());
        assertEquals("trialing", result.status());
    }

    @Test
    void declinedCardCancelsIncompleteSubscriptionAndThrowsPlainMessage() {
        api.toCreate = sub("sub_new", "incomplete", "cus_1", "requires_payment_method", "secret");
        CardDeclinedException e = assertThrows(CardDeclinedException.class,
                () -> creator.create(user, params(), "k"));
        assertEquals("Your card was declined. Use a different card and try again.", e.getMessage());
        assertEquals(List.of("sub_new"), api.cancelled);
    }

    @Test
    void declinedStillReportsDeclinedWhenCancelFails() {
        api.toCreate = sub("sub_new", "incomplete", "cus_1", "requires_payment_method", "secret");
        api.failCancel = true;
        assertThrows(CardDeclinedException.class, () -> creator.create(user, params(), "k"));
    }

    @Test
    void staleIncompleteSubscriptionIsCancelledBeforeCreate() throws Exception {
        user.setStripeSubscriptionId("sub_old");
        api.existing.put("sub_old", sub("sub_old", "incomplete", "cus_1", "requires_action", "s"));
        api.toCreate = sub("sub_new", "active", "cus_1", null, null);
        creator.create(user, params(), "k");
        assertEquals(List.of("retrieve:sub_old", "cancel:sub_old", "create"), api.events);
    }

    @Test
    void liveActiveTrialingAndPastDueSubscriptionsAreNeverCancelled() throws Exception {
        for (String status : List.of("active", "trialing", "past_due", "unpaid", "canceled", "incomplete_expired")) {
            api = new FakeApi();
            creator = new NewSubscriptionCreator(api);
            user.setStripeSubscriptionId("sub_old");
            user.setSubscriptionStatus("incomplete");
            api.existing.put("sub_old", sub("sub_old", status, "cus_1", null, null));
            api.toCreate = sub("sub_new", "active", "cus_1", null, null);
            creator.create(user, params(), "k");
            assertTrue(api.cancelled.isEmpty(), status);
            assertTrue(api.events.contains("create"), status);
        }
    }

    @Test
    void incompleteSubscriptionOfAnotherCustomerIsNotCancelled() throws Exception {
        user.setStripeSubscriptionId("sub_old");
        api.existing.put("sub_old", sub("sub_old", "incomplete", "cus_other", null, null));
        api.toCreate = sub("sub_new", "active", "cus_1", null, null);
        creator.create(user, params(), "k");
        assertTrue(api.cancelled.isEmpty());
    }

    @Test
    void userWithoutCustomerIdNeverCancelsOldSubscription() throws Exception {
        user.setStripeCustomerId(null);
        user.setStripeSubscriptionId("sub_old");
        api.existing.put("sub_old", sub("sub_old", "incomplete", null, null, null));
        api.toCreate = sub("sub_new", "active", "cus_1", null, null);
        creator.create(user, params(), "k");
        assertTrue(api.cancelled.isEmpty());
    }

    @Test
    void cancelFailureAbortsWithoutCreatingDuplicate() {
        user.setStripeSubscriptionId("sub_old");
        api.existing.put("sub_old", sub("sub_old", "incomplete", "cus_1", null, null));
        api.failCancel = true;
        api.toCreate = sub("sub_new", "active", "cus_1", null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> creator.create(user, params(), "k"));
        assertFalse(e.getMessage().contains("boom"));
        assertFalse(api.events.contains("create"));
    }

    private Subscription incompleteFor(String setupIntentId, String piStatus) {
        Subscription s = sub("sub_old", "incomplete", "cus_1", piStatus, "pi_old_secret");
        if (setupIntentId != null) {
            s.setMetadata(Map.of("setupIntentId", setupIntentId));
        }
        user.setStripeSubscriptionId("sub_old");
        api.existing.put("sub_old", s);
        return s;
    }

    @Test
    void sameSetupIntentRetryReusesIncompleteSubscriptionWithoutCancelOrCreate() throws Exception {
        incompleteFor("seti_1", "requires_action");
        SubscriptionResult result = creator.resultFor(creator.create(user, params(), "seti_1"));
        assertEquals(List.of("retrieve:sub_old"), api.events);
        assertEquals("sub_old", result.subscriptionId());
        assertTrue(result.requiresAction());
        assertEquals("pi_old_secret", result.clientSecret());
    }

    @Test
    void differentSetupIntentCancelsThenCreates() throws Exception {
        incompleteFor("seti_old", "requires_action");
        api.toCreate = sub("sub_new", "active", "cus_1", null, null);
        creator.create(user, params(), "seti_2");
        assertEquals(List.of("retrieve:sub_old", "cancel:sub_old", "create"), api.events);
    }

    @Test
    void legacySubscriptionWithoutMetadataIsCancelledThenCreated() throws Exception {
        incompleteFor(null, "requires_action");
        api.toCreate = sub("sub_new", "active", "cus_1", null, null);
        creator.create(user, params(), "seti_1");
        assertEquals(List.of("retrieve:sub_old", "cancel:sub_old", "create"), api.events);
    }

    @Test
    void reusedSubscriptionWithDeclinedCardIsCancelledAndThrowsDeclined() {
        incompleteFor("seti_1", "requires_payment_method");
        CardDeclinedException e = assertThrows(CardDeclinedException.class,
                () -> creator.create(user, params(), "seti_1"));
        assertEquals("Your card was declined. Use a different card and try again.", e.getMessage());
        assertEquals(List.of("retrieve:sub_old", "cancel:sub_old"), api.events);
    }

    @Test
    void createdSubscriptionCarriesSetupIntentMetadata() throws Exception {
        api.toCreate = sub("sub_new", "active", "cus_1", null, null);
        creator.create(user, params(), "seti_9");
        assertEquals("seti_9", ((Map<?, ?>) api.lastParams.getMetadata()).get("setupIntentId"));
    }

    @Test
    void userWithoutPriorSubscriptionSkipsCleanup() throws Exception {
        api.toCreate = sub("sub_new", "active", "cus_1", null, null);
        creator.create(user, params(), "k");
        assertEquals(List.of("create"), api.events);
    }
}
