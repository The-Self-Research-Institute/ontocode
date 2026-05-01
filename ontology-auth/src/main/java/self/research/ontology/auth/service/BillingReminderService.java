package self.research.ontology.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Sends scheduled billing reminder emails:
 *  - Trial ending: 3 days before trial converts to paid
 *  - Renewal reminder: 3 days before subscription auto-renews
 *
 * Runs daily at 09:00 server time. Uses a ±12-hour window around the 3-day mark
 * so each reminder fires exactly once per subscription period regardless of when
 * the cron last ran.
 */
@Slf4j
@Service
public class BillingReminderService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.base-url}")
    private String baseUrl;

    public BillingReminderService(UserRepository userRepository, EmailService emailService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @Scheduled(cron = "0 0 9 * * *")
    public void sendBillingReminders() {
        LocalDateTime now = LocalDateTime.now();
        // Window: period end is between 2.5 and 3.5 days from now
        LocalDateTime windowStart = now.plusDays(2).plusHours(12);
        LocalDateTime windowEnd   = now.plusDays(3).plusHours(12);

        List<User> users = userRepository.findAll();
        int trialReminders = 0;
        int renewalReminders = 0;

        for (User user : users) {
            if (user.getSubscriptionCurrentPeriodEnd() == null) continue;
            if (user.getEmail() == null || user.getEmail().isBlank()) continue;

            LocalDateTime periodEnd = user.getSubscriptionCurrentPeriodEnd();
            if (periodEnd.isBefore(windowStart) || periodEnd.isAfter(windowEnd)) continue;

            String status = user.getSubscriptionStatus();
            String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
            String endDate = periodEnd.format(DATE_FMT);
            String portalUrl = baseUrl;

            try {
                if ("trialing".equalsIgnoreCase(status)) {
                    emailService.sendTrialEndingReminderEmail(
                            user.getEmail(), user.getUsername(), planName, 3, endDate, portalUrl);
                    trialReminders++;
                    log.info("[BillingReminder] Trial ending reminder sent to {}", user.getUsername());
                } else if ("active".equalsIgnoreCase(status)) {
                    String amount = resolveAmount(planName, user.getBillingInterval());
                    emailService.sendRenewalReminderEmail(
                            user.getEmail(), user.getUsername(), planName, endDate, amount, portalUrl);
                    renewalReminders++;
                    log.info("[BillingReminder] Renewal reminder sent to {}", user.getUsername());
                }
            } catch (Exception e) {
                log.error("[BillingReminder] Failed to send reminder to {}: {}", user.getEmail(), e.getMessage());
            }
        }

        if (trialReminders > 0 || renewalReminders > 0) {
            log.info("[BillingReminder] Sent {} trial + {} renewal reminders", trialReminders, renewalReminders);
        }
    }

    private String resolveAmount(String planName, String interval) {
        if (planName == null || interval == null) return "your subscription amount";
        boolean yearly = "yearly".equalsIgnoreCase(interval) || "annual".equalsIgnoreCase(interval);
        return switch (planName.toUpperCase()) {
            case "ENTERPRISE" -> yearly ? "$990/year" : "$99/month";
            default           -> yearly ? "$290/year" : "$29/month";
        };
    }
}
