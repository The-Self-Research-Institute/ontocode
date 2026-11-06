package self.research.ontology.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.EmailLog;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.EmailLogRepository;
import self.research.ontology.auth.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import self.research.ontology.auth.model.PlanFeatureConfig;

@Slf4j
@Service
public class BillingReminderService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final EmailLogRepository emailLogs;
    private final PlanFeatureConfigService planFeatureConfigService;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${billing.reminder.enabled:true}")
    private boolean reminderEnabled;

    @Value("${billing.reminder.days-before-list:${billing.reminder.days-before:15,7,1}}")
    private String reminderDaysBeforeList;

    public BillingReminderService(UserRepository userRepository, EmailService emailService,
                                   EmailLogRepository emailLogs, PlanFeatureConfigService planFeatureConfigService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.emailLogs = emailLogs;
        this.planFeatureConfigService = planFeatureConfigService;
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

            LocalDate targetDate = LocalDate.now(ZoneOffset.UTC).plusDays(daysBefore);
            LocalDateTime windowStart = targetDate.atStartOfDay();
            LocalDateTime windowEnd   = targetDate.plusDays(1).atStartOfDay();

            for (User user : users) {
                if (user.getSubscriptionCurrentPeriodEnd() == null) continue;
                if (user.getEmail() == null || user.getEmail().isBlank()) continue;

                LocalDateTime periodEnd = user.getSubscriptionCurrentPeriodEnd();
                if (periodEnd.isBefore(windowStart) || periodEnd.isAfter(windowEnd)) continue;

                String status = user.getSubscriptionStatus();
                String planName = user.getSubscriptionPlanName() != null ? user.getSubscriptionPlanName() : "PRO";
                String endDate = periodEnd.format(DATE_FMT);
                String portalUrl = baseUrl;
                String reminderKind = "trialing".equalsIgnoreCase(status) ? "trial" : "active".equalsIgnoreCase(status) ? "renewal" : null;
                if (reminderKind == null) continue;
                String reminderKey = dailyReminderKey(user, now.toLocalDate());

                if (emailLogs.existsWithStatus(reminderKey, "sent", "sending")) {
                    log.info("[BillingReminder] Already sent or sending today: key={} email={} — skipping",
                            reminderKey, user.getEmail());
                    continue;
                }

                if (!reserveReminder(user, reminderKind, daysBefore, periodEnd, reminderKey)) {

                    log.info("[BillingReminder] Skipping duplicate reminder key={} email={}",
                            reminderKey, user.getEmail());
                    continue;
                }

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
                    markReminderSent(reminderKey);
                } catch (Exception e) {
                    markReminderFailed(reminderKey, e.getMessage());
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
        try {
            Map<String, PlanFeatureConfig> configs = planFeatureConfigService.getAllByPlanId();
            PlanFeatureConfig config = configs.get(planName.toUpperCase());
            if (config == null) return "your subscription amount";
            int monthly = config.getMonthlyPrice();
            if (yearly) {
                int yearlyTotal = (int) Math.round(monthly * 12 * (1.0 - config.getAnnualDiscountPercent() / 100.0));
                return "$" + yearlyTotal + "/year";
            }
            return "$" + monthly + "/month";
        } catch (Exception e) {
            log.warn("[BillingReminder] Could not resolve amount for {}/{}: {}", planName, interval, e.getMessage());
            return "your subscription amount";
        }
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

    private String dailyReminderKey(User user, LocalDate today) {
        String userPart = user.getId() != null ? user.getId() : user.getEmail().toLowerCase();
        return "billing-reminder:" + userPart + ":" + today;
    }

    private boolean reserveReminder(User user, String reminderKind, int daysBefore,
                                    LocalDateTime periodEnd, String reminderKey) {
        if (user == null || user.getId() == null || user.getEmail() == null) {
            return false;
        }
        return emailLogs.reserve(EmailLog.builder()
                .key(reminderKey)
                .type("billing_reminder")
                .kind(reminderKind)
                .daysBefore(daysBefore)
                .periodEnd(periodEnd)
                .email(user.getEmail())
                .userId(user.getId())
                .username(user.getUsername())
                .createdAt(LocalDateTime.now())
                .build());
    }

    private void markReminderSent(String reminderKey) {
        emailLogs.markSent(reminderKey, LocalDateTime.now());
    }

    private void markReminderFailed(String reminderKey, String errorMessage) {
        emailLogs.markFailed(reminderKey, errorMessage, LocalDateTime.now());
    }
}
