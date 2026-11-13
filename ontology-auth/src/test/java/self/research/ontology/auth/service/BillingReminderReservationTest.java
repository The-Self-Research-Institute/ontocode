package self.research.ontology.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.JdbcEmailLogRepository;
import self.research.ontology.auth.repository.JdbcUserRepository;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BillingReminderReservationTest {

    private final JdbcTemplate jdbc = EmbeddedTestDatabase.jdbc();
    private final JdbcUserRepository users = new JdbcUserRepository(jdbc);
    private final JdbcEmailLogRepository emailLogs = new JdbcEmailLogRepository(jdbc);
    private final EmailService emailService = mock(EmailService.class);
    private BillingReminderService service;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from email_log");
        jdbc.update("delete from app_user");
        service = new BillingReminderService(users, emailService, emailLogs, mock(PlanFeatureConfigService.class));
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost");
        ReflectionTestUtils.setField(service, "reminderEnabled", true);
        ReflectionTestUtils.setField(service, "reminderDaysBeforeList", "1");
        User u = new User();
        u.setUsername("renewer");
        u.setEmail("renewer@example.com");
        u.setPassword("hash");
        u.setSubscriptionStatus("active");
        u.setSubscriptionPlanName("PRO");
        u.setBillingInterval("monthly");
        u.setSubscriptionCurrentPeriodEnd(LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().plusHours(5));
        users.save(u);
    }

    @Test
    void aDueReminderIsSentOnceEvenWhenTheJobRunsTwiceAndIsLoggedAsSent() {
        service.sendBillingReminders();
        service.sendBillingReminders();

        verify(emailService, times(1)).sendRenewalReminderEmail(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
        assertEquals("sent", jdbc.queryForObject("select status from email_log", String.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from email_log", Integer.class));
    }

    @Test
    void concurrentRunsReserveTheReminderOnlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                service.sendBillingReminders();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        verify(emailService, times(1)).sendRenewalReminderEmail(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
        assertEquals(1, jdbc.queryForObject("select count(*) from email_log", Integer.class));
    }

    @Test
    void aFailedSendIsLoggedAsFailedAndNotRetriedTheSameDay() {
        doThrow(new IllegalStateException("smtp down")).when(emailService)
                .sendRenewalReminderEmail(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());

        service.sendBillingReminders();
        service.sendBillingReminders();

        verify(emailService, times(1)).sendRenewalReminderEmail(any(), any(), any(), anyInt(), any(), any(), any());
        assertEquals("failed", jdbc.queryForObject("select status from email_log", String.class));
        assertEquals("smtp down", jdbc.queryForObject("select error_message from email_log", String.class));
    }
}
