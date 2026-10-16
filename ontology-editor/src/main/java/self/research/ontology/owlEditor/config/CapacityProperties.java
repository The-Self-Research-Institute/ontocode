package self.research.ontology.owlEditor.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import self.research.ontology.owlEditor.service.Limit;
import self.research.ontology.owlEditor.service.Limit.Profile;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@ConfigurationProperties(prefix = "ontocode.capacity")
public class CapacityProperties {

    private String profile = "auto";
    private Map<String, Integer> limits = new HashMap<>();

    @PostConstruct
    void install() {
        long heap = Runtime.getRuntime().maxMemory();
        Profile selected = Limit.parseProfile(profile, heap);
        int[] resolved = resolve(heap);
        Limit.install(resolved);
        StringBuilder line = new StringBuilder();
        for (Limit limit : Limit.values()) {
            line.append(limit.key()).append('=').append(resolved[limit.ordinal()]).append(' ');
        }
        log.info("[Capacity] profile={} maxHeapMb={} {}", selected, heap / (1024 * 1024), line.toString().trim());
    }

    int[] resolve(long maxHeapBytes) {
        Map<Limit, Integer> overrides = new EnumMap<>(Limit.class);
        for (Map.Entry<String, Integer> entry : limits.entrySet()) {
            Limit limit = Limit.find(entry.getKey());
            if (limit == null) {
                log.warn("[Capacity] Unknown setting ontocode.capacity.limits.{} ignored", entry.getKey());
            } else if (entry.getValue() != null) {
                overrides.put(limit, entry.getValue());
            }
        }
        return Limit.resolve(Limit.parseProfile(profile, maxHeapBytes), overrides);
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public Map<String, Integer> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Integer> limits) {
        this.limits = limits;
    }
}
