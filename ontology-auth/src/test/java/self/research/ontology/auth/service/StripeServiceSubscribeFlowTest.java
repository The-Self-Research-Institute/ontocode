package self.research.ontology.auth.service;

import com.stripe.Stripe;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.EmailLogRepository;
import self.research.ontology.auth.repository.StripeUnmatchedInvoiceRepository;
import self.research.ontology.auth.repository.UserRepository;
import self.research.ontology.auth.repository.WorkspaceRepository;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StripeServiceSubscribeFlowTest {

    private HttpServer server;
    private final Map<String, String> routes = new HashMap<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private String previousBase;
    private String previousKey;
    private UserRepository users;
    private StripeService service;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            requests.add(key);
            ex.getRequestBody().readAllBytes();
            String body = routes.get(key);
            byte[] bytes = (body != null ? body : "{\"error\":{\"message\":\"unrouted\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(body != null ? 200 : 404, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        previousBase = Stripe.getApiBase();
        previousKey = Stripe.apiKey;
        Stripe.overrideApiBase("http://127.0.0.1:" + server.getAddress().getPort());
        Stripe.apiKey = "sk_test_fake";

        users = mock(UserRepository.class);
        service = new StripeService(users, mock(WorkspaceRepository.class), mock(WorkspaceService.class),
                mock(EmailService.class), mock(EmailLogRepository.class), mock(StripeUnmatchedInvoiceRepository.class));
        service.subscriptionCreator = new NewSubscriptionCreator(new StripeSubscriptionApi());
        ReflectionTestUtils.setField(service, "priceProMonthly", "price_pro_m");
        ReflectionTestUtils.setField(service, "trialPeriodDays", 0L);

        user = new User();
        user.setId("u1");
        user.setUsername("alice");
        user.setStripeCustomerId("cus_1");
        when(users.findById("u1")).thenReturn(Optional.of(user));
        when(users.tryClaimSubscriptionCreationLock(eq("u1"), any(), any())).thenReturn(true);

        routes.put("GET /v1/setup_intents/seti_1",
                "{\"id\":\"seti_1\",\"object\":\"setup_intent\",\"status\":\"succeeded\","
                        + "\"customer\":\"cus_1\",\"payment_method\":\"pm_1\"}");
        routes.put("GET /v1/customers/cus_1", "{\"id\":\"cus_1\",\"object\":\"customer\"}");
        routes.put("POST /v1/customers/cus_1", "{\"id\":\"cus_1\",\"object\":\"customer\"}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        Stripe.overrideApiBase(previousBase);
        Stripe.apiKey = previousKey;
    }

    private static String subscriptionJson(String id, String status, String piStatus) {
        String invoice = piStatus == null ? "null"
                : "{\"id\":\"in_1\",\"object\":\"invoice\",\"payment_intent\":{\"id\":\"pi_1\","
                + "\"object\":\"payment_intent\",\"status\":\"" + piStatus
                + "\",\"client_secret\":\"pi_1_secret_fake\"}}";
        return "{\"id\":\"" + id + "\",\"object\":\"subscription\",\"status\":\"" + status
                + "\",\"customer\":\"cus_1\",\"latest_invoice\":" + invoice + "}";
    }

    private SubscriptionResult subscribe() throws Exception {
        return service.createSubscriptionAfterSetup(user, "seti_1", "PRO", "monthly", null);
    }

    @Test
    void threeDsReturnsRequiresActionWithSecretAndReleasesLock() throws Exception {
        routes.put("POST /v1/subscriptions", subscriptionJson("sub_new", "incomplete", "requires_action"));
        SubscriptionResult result = subscribe();
        assertTrue(result.requiresAction());
        assertEquals("pi_1_secret_fake", result.clientSecret());
        assertEquals("incomplete", result.status());
        assertEquals("sub_new", user.getStripeSubscriptionId());
        verify(users).releaseSubscriptionCreationLock("u1");
    }

    @Test
    void activeSubscriptionDoesNotRequireActionAndReleasesLock() throws Exception {
        routes.put("POST /v1/subscriptions", subscriptionJson("sub_new", "active", "succeeded"));
        SubscriptionResult result = subscribe();
        assertFalse(result.requiresAction());
        assertEquals("active", result.status());
        verify(users).releaseSubscriptionCreationLock("u1");
    }

    @Test
    void declinedCardCancelsNewSubscriptionSavesNothingAndReleasesLock() {
        routes.put("POST /v1/subscriptions", subscriptionJson("sub_new", "incomplete", "requires_payment_method"));
        routes.put("DELETE /v1/subscriptions/sub_new", subscriptionJson("sub_new", "canceled", null));
        CardDeclinedException e = assertThrows(CardDeclinedException.class, this::subscribe);
        assertEquals("Your card was declined. Use a different card and try again.", e.getMessage());
        assertTrue(requests.contains("DELETE /v1/subscriptions/sub_new"));
        verify(users, never()).save(any());
        verify(users).releaseSubscriptionCreationLock("u1");
    }

    @Test
    void staleCancelFailureCreatesNothingAndReleasesLock() {
        user.setStripeSubscriptionId("sub_old");
        routes.put("GET /v1/subscriptions/sub_old", subscriptionJson("sub_old", "incomplete", null));
        assertThrows(IllegalStateException.class, this::subscribe);
        assertFalse(requests.contains("POST /v1/subscriptions"));
        verify(users, never()).save(any());
        verify(users).releaseSubscriptionCreationLock("u1");
    }

    @Test
    void staleIncompleteIsCancelledThenNewIsCreated() throws Exception {
        user.setStripeSubscriptionId("sub_old");
        routes.put("GET /v1/subscriptions/sub_old", subscriptionJson("sub_old", "incomplete", null));
        routes.put("DELETE /v1/subscriptions/sub_old", subscriptionJson("sub_old", "canceled", null));
        routes.put("POST /v1/subscriptions", subscriptionJson("sub_new", "active", "succeeded"));
        SubscriptionResult result = subscribe();
        assertEquals("sub_new", result.subscriptionId());
        assertEquals("sub_new", user.getStripeSubscriptionId());
        assertTrue(requests.indexOf("DELETE /v1/subscriptions/sub_old")
                < requests.indexOf("POST /v1/subscriptions"));
        verify(users).releaseSubscriptionCreationLock("u1");
    }

    @Test
    void lockNotClaimedThrowsAndDoesNotTouchStripeOrReleaseSomeoneElsesLock() {
        when(users.tryClaimSubscriptionCreationLock(eq("u1"), any(), any())).thenReturn(false);
        assertThrows(IllegalStateException.class, this::subscribe);
        assertTrue(requests.isEmpty());
        verify(users, never()).releaseSubscriptionCreationLock(any());
    }
}
