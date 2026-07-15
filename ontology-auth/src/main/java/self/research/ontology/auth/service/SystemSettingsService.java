package self.research.ontology.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import self.research.ontology.auth.model.SystemSettings;
import self.research.ontology.auth.repository.SystemSettingsRepository;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class SystemSettingsService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingsService.class);

    private final SystemSettingsRepository repo;

    // In-memory cache — refreshed on every write; reads always hit this first.
    // Stale for at most one JVM restart (acceptable: settings change rarely).
    private final AtomicReference<SystemSettings> cache = new AtomicReference<>();

    public SystemSettingsService(SystemSettingsRepository repo) {
        this.repo = repo;
    }

    public SystemSettings get() {
        SystemSettings cached = cache.get();
        if (cached != null) return cached;
        SystemSettings loaded = repo.findById(SystemSettings.GLOBAL_ID)
                .orElseGet(SystemSettings::new);
        cache.set(loaded);
        return loaded;
    }

    public SystemSettings save(SystemSettings settings, String updatedBy) {
        settings.setId(SystemSettings.GLOBAL_ID);
        settings.setUpdatedAt(LocalDateTime.now());
        settings.setUpdatedBy(updatedBy);
        SystemSettings saved = repo.save(settings);
        cache.set(saved);
        log.info("SystemSettings updated by {}: maintenance={} scheduleEnabled={} allowedDomains={} allowedEmails={} enterpriseDomains={} enterpriseEmails={}",
                updatedBy, saved.isMaintenanceModeEnabled(), saved.isMaintenanceScheduleEnabled(),
                saved.getMaintenanceAllowedDomains(), saved.getMaintenanceAllowedEmails(),
                saved.getEnterpriseDomains(), saved.getEnterpriseEmails());
        return saved;
    }

    /** True when maintenance is currently active and the email is NOT in the allowed list. */
    public boolean isBlockedByMaintenance(String email) {
        SystemSettings s = get();
        return s.isMaintenanceCurrentlyActive() && !s.isAllowedDuringMaintenance(email);
    }

    /** True when the email's domain is in the enterprise domain bypass list. */
    public boolean isEnterpriseDomain(String email) {
        return get().isEnterpriseDomain(email);
    }

    /** True when the email matches an enterprise domain or explicit email allowlist. */
    public boolean isEnterpriseBypass(String email) {
        return get().isEnterpriseBypass(email);
    }
}
