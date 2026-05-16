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
    /** When true, only domains in maintenanceAllowedDomains can log in / sign up. */
    private boolean maintenanceModeEnabled = false;

    /**
     * Email domains that can still access the system during maintenance.
     * Example: ["coretopia.com", "partner.com"]
     */
    private List<String> maintenanceAllowedDomains = new ArrayList<>();

    // ── Enterprise domain bypass ──────────────────────────────────────────────
    /**
     * Email domains whose users automatically receive an Enterprise plan
     * without going through Stripe payment.
     * Example: ["university.edu", "hospital.org"]
     */
    private List<String> enterpriseDomains = new ArrayList<>();

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

    public List<String> getMaintenanceAllowedDomains() { return maintenanceAllowedDomains; }
    public void setMaintenanceAllowedDomains(List<String> maintenanceAllowedDomains) {
        this.maintenanceAllowedDomains = maintenanceAllowedDomains != null ? maintenanceAllowedDomains : new ArrayList<>();
    }

    public List<String> getEnterpriseDomains() { return enterpriseDomains; }
    public void setEnterpriseDomains(List<String> enterpriseDomains) {
        this.enterpriseDomains = enterpriseDomains != null ? enterpriseDomains : new ArrayList<>();
    }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isDomainAllowedDuringMaintenance(String email) {
        if (!maintenanceModeEnabled) return true;
        if (email == null) return false;
        String lower = email.toLowerCase();
        return maintenanceAllowedDomains.stream()
                .anyMatch(d -> lower.endsWith("@" + d.trim().toLowerCase()));
    }

    public boolean isEnterpriseDomain(String email) {
        if (email == null || enterpriseDomains.isEmpty()) return false;
        String lower = email.toLowerCase();
        return enterpriseDomains.stream()
                .anyMatch(d -> lower.endsWith("@" + d.trim().toLowerCase()));
    }
}
