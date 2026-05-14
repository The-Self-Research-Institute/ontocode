package self.research.ontology.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.User;
import self.research.ontology.auth.repository.UserRepository;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import self.research.ontology.auth.model.PlanFeatureConfig;

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
    private final MongoTemplate mongoTemplate;
    private final PlanFeatureConfigService planFeatureConfigService;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${billing.reminder.enabled:true}")
    private boolean reminderEnabled;

    @Value("${billing.reminder.days-before-list:${billing.reminder.days-before:15,7,1}}")
    private String reminderDaysBeforeList;

    public BillingReminderService(UserRepository userRepository, EmailService emailService,
                                   MongoTemplate mongoTemplate, PlanFeatureConfigService planFeatureConfigService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.mongoTemplate = mongoTemplate;
        this.planFeatureConfigService = planFeatureConfigService;
    }

    @PostConstruct
    public void ensureEmailLogIndexes() {
        // ensureIndex() does NOT upgrade an existing non-unique index to unique.
        // If the collection was created earlier with a non-unique key index,
        // the dedup invariant silently fails and reminders fire every cron tick.
        // Detect that case and rebuild.
        boolean keyIndexIsUnique = mongoTemplate.indexOps("email_logs").getIndexInfo().stream()
                .filter(info -> info.getIndexFields().stream().anyMatch(f -> "key".equals(f.getKey())))
                .findFirst()
                .map(info -> info.isUnique())
                .orElse(false);

        if (!keyIndexIsUnique) {
            log.warn("[BillingReminder] email_logs.key index is missing or non-unique — rebuilding as unique. "
                    + "This is the dedup invariant; without it duplicate reminders will fire.");
            try {
                mongoTemplate.indexOps("email_logs").dropIndex("key_1");
            } catch (Exception ignored) {
                // No prior index — fall through to create.
            }
            mongoTemplate.indexOps("email_logs")
                    .ensureIndex(new Index().on("key", Sort.Direction.ASC).unique());
            log.info("[BillingReminder] email_logs.key unique index created.");
        }
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
            // Cover the entire target UTC calendar day so a trial/subscription ending
            // at any hour is caught regardless of when the cron fires.
            // The dedup key (dailyReminderKey) ensures at most one email per user per day.
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

                // Defence in depth: even if the unique index is somehow bypassed,
                // a prior "sent" record for this key means the reminder already went out.
                Query priorQuery = new Query(Criteria.where("key").is(reminderKey)
                        .and("status").in("sent", "sending"));
                if (mongoTemplate.exists(priorQuery, "email_logs")) {
                    log.info("[BillingReminder] Already sent or sending today: key={} email={} — skipping",
                            reminderKey, user.getEmail());
                    continue;
                }

                if (!reserveReminder(user, reminderKind, daysBefore, periodEnd, reminderKey)) {
                    // Promoted from debug → info so the skip is visible in production logs.
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
        Document doc = new Document("key", reminderKey)
                .append("type", "billing_reminder")
                .append("status", "sending")
                .append("kind", reminderKind)
                .append("daysBefore", daysBefore)
                .append("periodEnd", periodEnd)
                .append("email", user.getEmail())
                .append("userId", user.getId())
                .append("username", user.getUsername())
                .append("createdAt", LocalDateTime.now());
        try {
            mongoTemplate.insert(doc, "email_logs");
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private void markReminderSent(String reminderKey) {
        Query query = new Query(Criteria.where("key").is(reminderKey));
        Update update = new Update()
                .set("status", "sent")
                .set("sentAt", LocalDateTime.now());
        mongoTemplate.updateFirst(query, update, "email_logs");
    }

    private void markReminderFailed(String reminderKey, String errorMessage) {
        Query query = new Query(Criteria.where("key").is(reminderKey));
        Update update = new Update()
                .set("status", "failed")
                .set("failedAt", LocalDateTime.now())
                .set("errorMessage", errorMessage);
        mongoTemplate.updateFirst(query, update, "email_logs");
    }
}
