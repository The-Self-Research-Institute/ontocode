package self.research.ontology.owlEditor.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import self.research.ontology.common.admission.CapacityConfig;
import self.research.ontology.common.admission.LedgerWatchdog;
import self.research.ontology.common.admission.ReservationLedger;

@Component
public class CapacityMonitor {

    private static final Logger log = LoggerFactory.getLogger(CapacityMonitor.class);

    private final LedgerWatchdog watchdog;
    private final ReservationLedger ledger;
    private final CapacityConfig config;

    public CapacityMonitor(LedgerWatchdog watchdog, ReservationLedger ledger, CapacityConfig config) {
        this.watchdog = watchdog;
        this.ledger = ledger;
        this.config = config;
    }

    @PostConstruct
    void logEffectiveConfig() {
        StringBuilder line = new StringBuilder();
        config.effectiveValues().forEach((key, value) ->
                line.append(key).append('=').append(value).append(" (").append(config.sources().get(key)).append(") "));
        log.info("[Capacity] Effective settings: {}", line.toString().trim());
        log.info("[Capacity] Signals: {}", ledger.signals().report());
    }

    @Scheduled(fixedDelayString = "${ontocode.capacity.watchdog-interval-ms:60000}",
            initialDelayString = "${ontocode.capacity.watchdog-interval-ms:60000}")
    public void checkForLeaks() {
        watchdog.checkOnce();
    }

    @Scheduled(fixedDelayString = "${ontocode.capacity.log-interval-ms:900000}",
            initialDelayString = "${ontocode.capacity.log-interval-ms:900000}")
    public void logCapacityTable() {
        log.info("[Capacity]\n{}", ledger.capacityReport().renderTable());
    }
}
