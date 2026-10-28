package self.research.ontology.common.admission;

import java.time.Duration;

public record KindSettings(
        String kind,
        double maxShare,
        int maxActivePerOwner,
        int maxWaitingPerOwner,
        Duration maxWait,
        int maxWaiters) {

    public KindSettings {
        if (!(maxShare > 0 && maxShare <= 1)) {
            throw new IllegalArgumentException("maxShare must be in (0,1]");
        }
        if (maxActivePerOwner < 1) {
            throw new IllegalArgumentException("maxActivePerOwner must be at least 1");
        }
        if (maxWaitingPerOwner < 0 || maxWaiters < 0) {
            throw new IllegalArgumentException("waiter limits must not be negative");
        }
        if (maxWait == null || maxWait.isNegative()) {
            throw new IllegalArgumentException("maxWait must not be negative");
        }
    }

    public static KindSettings fromProperties(String kind, AdmissionProperties p) {
        return new KindSettings(kind, 1.0, p.maxActivePerOwner(), p.maxWaitingPerOwner(), p.maxWait(),
                p.maxWaiters());
    }
}
