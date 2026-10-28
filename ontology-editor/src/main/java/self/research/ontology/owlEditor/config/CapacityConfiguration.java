package self.research.ontology.owlEditor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import self.research.ontology.common.ReasoningHeapMonitor;
import self.research.ontology.common.admission.CapacityConfig;
import self.research.ontology.common.admission.Estimates;
import self.research.ontology.common.admission.LedgerWatchdog;
import self.research.ontology.common.admission.MemorySignals;
import self.research.ontology.common.admission.ReservationLedger;
import self.research.ontology.owlEditor.service.SparqlResultLimits;

import java.time.Clock;
import java.util.Map;

@Configuration
public class CapacityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CapacityConfiguration.class);

    public static final String FIXED_OVERHEAD_MB = "ontocode.capacity.fixed-overhead-mb";
    public static final long DEFAULT_FIXED_OVERHEAD_MB = 256L;
    public static final String SPARQL_MAX_ROWS = "ontocode.capacity.sparql.max-rows";
    public static final String SPARQL_MAX_RESULT_BYTES = "ontocode.capacity.sparql.max-result-bytes";
    public static final String ESTIMATE_PREFIX = "ontocode.capacity.estimate.";

    public static final Map<String, String> LEGACY_ALIASES = Map.of(
            "ontocode.dlquery.max-concurrent", "kind.dl-query.max-active-per-owner",
            "ONTOCODE_DLQUERY_MAX_CONCURRENT", "kind.dl-query.max-active-per-owner",
            "ontocode.import.max-concurrent", "kind.import-parse.max-active-per-owner",
            "ONTOCODE_IMPORT_MAX_CONCURRENT", "kind.import-parse.max-active-per-owner");

    @Bean
    public MemorySignals memorySignals() {
        return ReasoningHeapMonitor.signals();
    }

    @Bean
    public CapacityConfig capacityConfig(Environment environment) {
        return CapacityConfig.load(environment::getProperty, System::getenv, LEGACY_ALIASES);
    }

    @Bean
    public Estimates capacityEstimates(Environment environment) {
        Estimates defaults = new Estimates();
        return new Estimates(
                positive(environment, ESTIMATE_PREFIX + "bytes-per-triple-load", defaults.estimatedBytesPerTripleLoad()),
                positive(environment, ESTIMATE_PREFIX + "bytes-per-triple-rule-execute",
                        defaults.estimatedBytesPerTripleRuleExecute()),
                positive(environment, ESTIMATE_PREFIX + "import-parse-multiplier", defaults.estimatedImportParseMultiplier()),
                positive(environment, ESTIMATE_PREFIX + "sparql-row-bytes", defaults.estimatedSparqlRowBytes()),
                positive(environment, SPARQL_MAX_RESULT_BYTES, defaults.sparqlRowsBytesCap()));
    }

    @Bean
    public SparqlResultLimits sparqlResultLimits(Environment environment, Estimates estimates) {
        long rows = positive(environment, SPARQL_MAX_ROWS, SparqlResultLimits.DEFAULT_MAX_ROWS);
        return new SparqlResultLimits((int) Math.min(rows, Integer.MAX_VALUE), estimates.sparqlRowsBytesCap());
    }

    @Bean
    public ReservationLedger reservationLedger(MemorySignals signals, CapacityConfig config, Environment environment) {
        long overheadBytes = positive(environment, FIXED_OVERHEAD_MB, DEFAULT_FIXED_OVERHEAD_MB) * 1024L * 1024L;
        return new ReservationLedger(signals, config, () -> overheadBytes, Clock.systemUTC());
    }

    @Bean
    public LedgerWatchdog ledgerWatchdog(ReservationLedger ledger, CapacityConfig config) {
        return new LedgerWatchdog(ledger, config.admission().watchdogMaxAge(), leaks -> {
            for (ReservationLedger.Leak leak : leaks) {
                log.warn("[Capacity] Reservation older than {} still held: id={} owner={} kind={} bytes={} age={}",
                        config.admission().watchdogMaxAge(), leak.id(), leak.owner(), leak.kind(), leak.bytes(),
                        leak.age());
            }
        });
    }

    private static long positive(Environment environment, String key, long fallback) {
        Long value = environment.getProperty(key, Long.class);
        return value != null && value > 0 ? value : fallback;
    }
}
