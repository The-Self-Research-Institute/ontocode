package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Singleton document (id = "global") storing runtime-configurable system settings.
 * All fields default to safe/open values so a missing document means "no restrictions".
 */
@Document(collection = "system_settings")
public class SystemSettings {

    public static final String GLOBAL_ID = "global";

    @Id
    private String id = GLOBAL_ID;

    // ── Maintenance mode ──────────────────────────────────────────────────────
    /** When true, maintenance mode is active (subject to schedule if set). */
    private boolean maintenanceModeEnabled = false;

    /** Optional custom message shown to blocked users. */
    private String maintenanceMessage = "";

    /**
     * Email domains that can still access the system during maintenance.
     * Example: ["coretopia.com", "partner.com"]
     */
    private List<String> maintenanceAllowedDomains = new ArrayList<>();

    /**
     * Individual email addresses allowed during maintenance (bypasses domain check).
     * Example: ["admin@example.com", "tester@other.org"]
     */
    private List<String> maintenanceAllowedEmails = new ArrayList<>();

    // ── Maintenance schedule ──────────────────────────────────────────────────
    /** When true, maintenance is only active within the scheduled window. */
    private boolean maintenanceScheduleEnabled = false;

    /** All-day maintenance on a specific date (format: "YYYY-MM-DD"). Null = not set. */
    private String maintenanceAllDayDate = null;

    /** Scheduled maintenance start (ISO datetime). Null = no scheduled start. */
    private LocalDateTime maintenanceStartTime = null;

    /** Scheduled maintenance end (ISO datetime). Null = no scheduled end. */
    private LocalDateTime maintenanceEndTime = null;

    // ── Enterprise bypass (beta / partner access) ───────────────────────────────
    /**
     * Email domains whose users automatically receive an Enterprise plan
     * without going through Stripe payment.
     * Example: ["university.edu", "hospital.org"]
     */
    private List<String> enterpriseDomains = new ArrayList<>();

    /**
     * Individual email addresses granted Enterprise access (beta testers, partners).
     * Example: ["beta.user@company.com"]
     */
    private List<String> enterpriseEmails = new ArrayList<>();

    private LocalDateTime updatedAt;
    private String updatedBy;

    public SystemSettings() {
    }

    // ── Getters & setters ─────────────────────────────────────────────────────

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean isMaintenanceModeEnabled() { return maintenanceModeEnabled; }
    public void setMaintenanceModeEnabled(boolean maintenanceModeEnabled) {
        this.maintenanceModeEnabled = maintenanceModeEnabled;
    }

    public String getMaintenanceMessage() { return maintenanceMessage != null ? maintenanceMessage : ""; }
    public void setMaintenanceMessage(String maintenanceMessage) { this.maintenanceMessage = maintenanceMessage; }

    public List<String> getMaintenanceAllowedDomains() { return maintenanceAllowedDomains; }
    public void setMaintenanceAllowedDomains(List<String> maintenanceAllowedDomains) {
        this.maintenanceAllowedDomains = maintenanceAllowedDomains != null ? maintenanceAllowedDomains : new ArrayList<>();
    }

    public List<String> getMaintenanceAllowedEmails() { return maintenanceAllowedEmails != null ? maintenanceAllowedEmails : new ArrayList<>(); }
    public void setMaintenanceAllowedEmails(List<String> maintenanceAllowedEmails) {
        this.maintenanceAllowedEmails = maintenanceAllowedEmails != null ? maintenanceAllowedEmails : new ArrayList<>();
    }

    public boolean isMaintenanceScheduleEnabled() { return maintenanceScheduleEnabled; }
    public void setMaintenanceScheduleEnabled(boolean maintenanceScheduleEnabled) { this.maintenanceScheduleEnabled = maintenanceScheduleEnabled; }

    public String getMaintenanceAllDayDate() { return maintenanceAllDayDate; }
    public void setMaintenanceAllDayDate(String maintenanceAllDayDate) { this.maintenanceAllDayDate = maintenanceAllDayDate; }

    public LocalDateTime getMaintenanceStartTime() { return maintenanceStartTime; }
    public void setMaintenanceStartTime(LocalDateTime maintenanceStartTime) { this.maintenanceStartTime = maintenanceStartTime; }

    public LocalDateTime getMaintenanceEndTime() { return maintenanceEndTime; }
    public void setMaintenanceEndTime(LocalDateTime maintenanceEndTime) { this.maintenanceEndTime = maintenanceEndTime; }

    public List<String> getEnterpriseDomains() { return enterpriseDomains; }
    public void setEnterpriseDomains(List<String> enterpriseDomains) {
        this.enterpriseDomains = enterpriseDomains != null ? enterpriseDomains : new ArrayList<>();
    }

    public List<String> getEnterpriseEmails() { return enterpriseEmails; }
    public void setEnterpriseEmails(List<String> enterpriseEmails) {
        this.enterpriseEmails = enterpriseEmails != null ? enterpriseEmails : new ArrayList<>();
    }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns true when maintenance is currently active based on enabled flag + schedule.
     */
    public boolean isMaintenanceCurrentlyActive() {
        if (!maintenanceModeEnabled) return false;
        if (!maintenanceScheduleEnabled) return true;

        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        // All-day on a specific date
        if (maintenanceAllDayDate != null && !maintenanceAllDayDate.isBlank()) {
            try {
                java.time.LocalDate target = java.time.LocalDate.parse(maintenanceAllDayDate);
                return now.toLocalDate().equals(target);
            } catch (Exception ignored) {}
        }

        // Time-range window
        if (maintenanceStartTime != null && maintenanceEndTime != null) {
            return !now.isBefore(maintenanceStartTime) && !now.isAfter(maintenanceEndTime);
        }
        if (maintenanceStartTime != null) return !now.isBefore(maintenanceStartTime);
        if (maintenanceEndTime != null)   return !now.isAfter(maintenanceEndTime);

        return true; // schedule enabled but no window configured — treat as active
    }

    /**
     * True when the given email is allowed to access the system during maintenance
     * (either via domain allowlist or individual email allowlist).
     */
    public boolean isAllowedDuringMaintenance(String email) {
        if (email == null) return false;
        String lower = email.toLowerCase().trim();
        // individual email match
        if (!maintenanceAllowedEmails.isEmpty() &&
                maintenanceAllowedEmails.stream().anyMatch(e -> lower.equals(e.trim().toLowerCase()))) {
            return true;
        }
        // domain match
        return maintenanceAllowedDomains.stream()
                .anyMatch(d -> lower.endsWith("@" + d.trim().toLowerCase()));
    }

    public boolean isDomainAllowedDuringMaintenance(String email) {
        return isAllowedDuringMaintenance(email);
    }

    public boolean isEnterpriseDomain(String email) {
        if (email == null || enterpriseDomains.isEmpty()) return false;
        String lower = email.toLowerCase().trim();
        return enterpriseDomains.stream()
                .anyMatch(d -> lower.endsWith("@" + d.trim().toLowerCase()));
    }

    /** True when email matches an explicit allowlist entry or an enterprise domain. */
    public boolean isEnterpriseBypass(String email) {
        if (email == null) return false;
        String lower = email.toLowerCase().trim();
        if (!enterpriseEmails.isEmpty()) {
            boolean emailMatch = enterpriseEmails.stream()
                    .anyMatch(e -> lower.equals(e.trim().toLowerCase()));
            if (emailMatch) return true;
        }
        return isEnterpriseDomain(email);
    }
}
