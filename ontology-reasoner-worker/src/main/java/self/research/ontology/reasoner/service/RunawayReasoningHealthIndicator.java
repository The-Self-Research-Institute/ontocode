package self.research.ontology.reasoner.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunawayReasoningHealthIndicator implements HealthIndicator {

    private final Set<String> stuckJobIds = ConcurrentHashMap.newKeySet();

    public void markStuck(String jobId) {
        stuckJobIds.add(jobId);
    }

    public void clear(String jobId) {
        stuckJobIds.remove(jobId);
    }

    public List<String> stuckJobIds() {
        return List.copyOf(stuckJobIds);
    }

    @Override
    public Health health() {
        List<String> stuck = stuckJobIds();
        if (stuck.isEmpty()) {
            return Health.up().withDetail("stuckReasoningThreads", 0).build();
        }
        return Health.down()
                .withDetail("stuckReasoningThreads", stuck.size())
                .withDetail("jobIds", stuck)
                .withDetail("reason", "Reasoning threads kept running long after they were told to stop; "
                        + "their slots stay occupied until they end. Restart the worker if this persists.")
                .build();
    }
}
