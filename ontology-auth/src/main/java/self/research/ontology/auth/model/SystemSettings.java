package self.research.ontology.auth.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "system_settings")
public class SystemSettings {

    public static final String GLOBAL_ID = "global";

    @Id
    private String id = GLOBAL_ID;

    private boolean maintenanceModeEnabled = false;

    private String maintenanceMessage = "";

    private List<String> maintenanceAllowedDomains = new ArrayList<>();

    private List<String> maintenanceAllowedEmails = new ArrayList<>();

    private boolean maintenanceScheduleEnabled = false;

    private String maintenanceAllDayDate = null;

    private LocalDateTime maintenanceStartTime = null;

    private LocalDateTime maintenanceEndTime = null;

    private boolean maintenanceDailyEnabled = false;

    private String maintenanceDailyStartTime = "09:00";

    private String maintenanceDailyEndTime = "19:00";

    private String maintenanceDailyTimezone = "Asia/Kolkata";

    private List<String> enterpriseDomains = new ArrayList<>();

    private List<String> enterpriseEmails = new ArrayList<>();

    private LocalDateTime updatedAt;
    private String updatedBy;

    public SystemSettings() {
    }

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

    public boolean isMaintenanceDailyEnabled() { return maintenanceDailyEnabled; }
    public void setMaintenanceDailyEnabled(boolean maintenanceDailyEnabled) { this.maintenanceDailyEnabled = maintenanceDailyEnabled; }

    public String getMaintenanceDailyStartTime() { return maintenanceDailyStartTime; }
    public void setMaintenanceDailyStartTime(String maintenanceDailyStartTime) { this.maintenanceDailyStartTime = maintenanceDailyStartTime; }

    public String getMaintenanceDailyEndTime() { return maintenanceDailyEndTime; }
    public void setMaintenanceDailyEndTime(String maintenanceDailyEndTime) { this.maintenanceDailyEndTime = maintenanceDailyEndTime; }

    public String getMaintenanceDailyTimezone() { return maintenanceDailyTimezone; }
    public void setMaintenanceDailyTimezone(String maintenanceDailyTimezone) { this.maintenanceDailyTimezone = maintenanceDailyTimezone; }

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

    public boolean isMaintenanceCurrentlyActive() {
        if (!maintenanceModeEnabled) return false;
        if (!maintenanceScheduleEnabled) return true;

        if (maintenanceDailyEnabled) {
            try {
                ZoneId tz = ZoneId.of(maintenanceDailyTimezone != null ? maintenanceDailyTimezone : "Asia/Kolkata");
                ZonedDateTime now = ZonedDateTime.now(tz);
                LocalTime current = now.toLocalTime();
                LocalTime start = LocalTime.parse(maintenanceDailyStartTime != null ? maintenanceDailyStartTime : "09:00");
                LocalTime end = LocalTime.parse(maintenanceDailyEndTime != null ? maintenanceDailyEndTime : "19:00");
                return !current.isBefore(start) && !current.isAfter(end);
            } catch (Exception ignored) {
                return true;
            }
        }

        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        if (maintenanceAllDayDate != null && !maintenanceAllDayDate.isBlank()) {
            try {
                java.time.LocalDate target = java.time.LocalDate.parse(maintenanceAllDayDate);
                return now.toLocalDate().equals(target);
            } catch (Exception ignored) {}
        }

        if (maintenanceStartTime != null && maintenanceEndTime != null) {
            return !now.isBefore(maintenanceStartTime) && !now.isAfter(maintenanceEndTime);
        }
        if (maintenanceStartTime != null) return !now.isBefore(maintenanceStartTime);
        if (maintenanceEndTime != null)   return !now.isAfter(maintenanceEndTime);

        return true;
    }

    public boolean isAllowedDuringMaintenance(String email) {
        if (email == null) return false;
        String lower = email.toLowerCase().trim();

        if (!maintenanceAllowedEmails.isEmpty() &&
                maintenanceAllowedEmails.stream().anyMatch(e -> lower.equals(e.trim().toLowerCase()))) {
            return true;
        }

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
