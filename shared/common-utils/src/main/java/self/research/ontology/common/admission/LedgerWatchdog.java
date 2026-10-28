package self.research.ontology.common.admission;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class LedgerWatchdog {

    private final ReservationLedger ledger;
    private final Duration maxAge;
    private final Consumer<List<ReservationLedger.Leak>> onLeaks;

    public LedgerWatchdog(ReservationLedger ledger, Duration maxAge, Consumer<List<ReservationLedger.Leak>> onLeaks) {
        this.ledger = ledger;
        this.maxAge = maxAge;
        this.onLeaks = onLeaks;
    }

    public List<ReservationLedger.Leak> checkOnce() {
        List<ReservationLedger.Leak> leaks = ledger.leaks(maxAge);
        if (!leaks.isEmpty()) {
            onLeaks.accept(leaks);
        }
        return leaks;
    }

    public ScheduledFuture<?> start(ScheduledExecutorService scheduler, Duration interval) {
        return scheduler.scheduleWithFixedDelay(() -> {
            try {
                checkOnce();
            } catch (RuntimeException ignored) {
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }
}
