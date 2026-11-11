package self.research.ontology.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.auth.EmbeddedDatabaseSpringTest;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PastDueStatusDbTest extends EmbeddedDatabaseSpringTest {

    @Autowired
    UserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from app_user");
    }

    private User subscriber(String name, String subscriptionId, String status) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setPassword("$2a$10$dummyhash");
        u.setEnabled(true);
        u.setRoles(Set.of("ROLE_USER"));
        u.setStripeSubscriptionId(subscriptionId);
        u.setSubscriptionStatus(status);
        u.setSubscriptionPlanName("PRO");
        return users.save(u);
    }

    @Test
    void aFailedPaymentOnTheCurrentSubscriptionMovesItToPastDueAndKeepsTheOtherFields() {
        User u = subscriber("current", "sub_1", "active");

        assertTrue(users.markPastDueIfCurrentSubscription(u.getId(), "sub_1"));

        User loaded = users.findById(u.getId()).orElseThrow();
        assertEquals("past_due", loaded.getSubscriptionStatus());
        assertEquals("PRO", loaded.getSubscriptionPlanName());
        assertEquals("sub_1", loaded.getStripeSubscriptionId());
    }

    @Test
    void aFailedPaymentOnAnotherSubscriptionChangesNothing() {
        User u = subscriber("other", "sub_new", "active");

        assertFalse(users.markPastDueIfCurrentSubscription(u.getId(), "sub_old"));

        assertEquals("active", users.findById(u.getId()).orElseThrow().getSubscriptionStatus());
    }

    @Test
    void aCancelledSubscriptionStaysCancelled() {
        User u = subscriber("cancelled", "sub_1", "canceled");

        assertFalse(users.markPastDueIfCurrentSubscription(u.getId(), "sub_1"));

        assertEquals("canceled", users.findById(u.getId()).orElseThrow().getSubscriptionStatus());
    }

    @Test
    void aFailedPaymentAfterTheSubscriptionWasRemovedChangesNothing() {
        User u = subscriber("removed", null, "canceled");

        assertFalse(users.markPastDueIfCurrentSubscription(u.getId(), "sub_1"));

        User loaded = users.findById(u.getId()).orElseThrow();
        assertEquals("canceled", loaded.getSubscriptionStatus());
        assertNull(loaded.getStripeSubscriptionId());
    }

    @Test
    void whenTheCancellationAndTheFailedPaymentRaceTheUserAlwaysEndsCancelled() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 150; i++) {
                String subId = "sub_race_" + i;
                User u = subscriber("race" + i, subId, "active");
                CountDownLatch go = new CountDownLatch(1);

                Future<?> cancel = pool.submit(() -> {
                    go.await();
                    User stale = users.findById(u.getId()).orElseThrow();
                    stale.setSubscriptionStatus("canceled");
                    stale.setStripeSubscriptionId(null);
                    users.save(stale);
                    return null;
                });
                Future<?> failed = pool.submit(() -> {
                    go.await();
                    users.markPastDueIfCurrentSubscription(u.getId(), subId);
                    return null;
                });
                go.countDown();
                cancel.get();
                failed.get();

                assertEquals("canceled", users.findById(u.getId()).orElseThrow().getSubscriptionStatus(),
                        "iteration " + i);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
