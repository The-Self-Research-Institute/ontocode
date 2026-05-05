package self.research.ontology.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends scheduled billing reminder emails for trial endings and subscription renewals.
 *
 * Runs daily at 09:00 server time. Each configured reminder day uses a 24-hour
 * window around that mark so each reminder fires once per subscription period.
 */
@Slf4j
@Service
public class BillingReminderService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final UserRepository userRepository;
    private final EmailService emailService;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${billing.reminder.enabled:true}")
    private boolean reminderEnabled;

    @Value("${billing.reminder.days-before-list:${billing.reminder.days-before:15,7,1}}")
    private String reminderDaysBeforeList;

    public BillingReminderService(UserRepository userRepository, EmailService emailService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @Scheduled(cron = "${billing.reminder.cron:0 0 9 * * *}")
    public void sendBillingReminders() {
        if (!reminderEnabled) {
            log.debug("[BillingReminder] Reminders disabled - skipping");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<Integer> reminderDays = parseReminderDays();

        List<User> users = userRepository.findAll();
        int trialReminders = 0;
        int renewalReminders = 0;

        for (int daysBefore : reminderDays) {
            LocalDateTime windowStart = now.plusDays(daysBefore - 1L).plusHours(12);
            LocalDateTime windowEnd = now.plusDays(daysBefore).plusHours(12);

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
                                user.getEmail(), user.getUsername(), planName, daysBefore, endDate, portalUrl);
                        trialReminders++;
                        log.info("[BillingReminder] Trial ending reminder sent to {} ({} days before)",
                                user.getUsername(), daysBefore);
                    } else if ("active".equalsIgnoreCase(status)) {
                        String amount = resolveAmount(planName, user.getBillingInterval());
                        emailService.sendRenewalReminderEmail(
                                user.getEmail(), user.getUsername(), planName, daysBefore, endDate, amount, portalUrl);
                        renewalReminders++;
                        log.info("[BillingReminder] Renewal reminder sent to {} ({} days before)",
                                user.getUsername(), daysBefore);
                    }
                } catch (Exception e) {
                    log.error("[BillingReminder] Failed to send reminder to {}: {}", user.getEmail(), e.getMessage());
                }
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
            case "ENTERPRISE" -> yearly ? "$3,588/year" : "$299/month";
            default -> yearly ? "$708/year" : "$59/month";
        };
    }

    private List<Integer> parseReminderDays() {
        List<Integer> days = new ArrayList<>();
        if (reminderDaysBeforeList != null) {
            for (String token : reminderDaysBeforeList.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    int value = Integer.parseInt(trimmed);
                    if (value > 0 && !days.contains(value)) {
                        days.add(value);
                    }
                } catch (NumberFormatException e) {
                    log.warn("[BillingReminder] Ignoring invalid reminder day '{}'", trimmed);
                }
            }
        }
        return days.isEmpty() ? List.of(15, 7, 1) : days;
    }
}
