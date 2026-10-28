package self.research.ontology.owlEditor.service;

import self.research.ontology.common.admission.CapacityConfig;
import self.research.ontology.common.admission.MemorySignals;
import self.research.ontology.common.admission.ReservationLedger;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

public final class FakeMemorySignals implements MemorySignals {

    public static final long MIB = 1024L * 1024L;

    public volatile long heapMax = 1000 * MIB;
    public volatile long heapUsed = 0;
    public volatile long afterGc = 0;
    public volatile Metric limit = Metric.unavailable();
    public volatile Metric usage = Metric.unavailable();

    @Override
    public Metric containerLimit() {
        return limit;
    }

    @Override
    public Metric containerUsageRaw() {
        return usage;
    }

    @Override
    public Metric containerUsageAdjusted() {
        return usage;
    }

    @Override
    public long heapMaxBytes() {
        return heapMax;
    }

    @Override
    public long heapUsedBytes() {
        return heapUsed;
    }

    @Override
    public HeapAfterGc heapAfterGc() {
        return new HeapAfterGc(afterGc, true);
    }

    @Override
    public Metric directBufferBytes() {
        return Metric.available(0);
    }

    @Override
    public Metric mappedBufferBytes() {
        return Metric.unavailable();
    }

    public ReservationLedger ledger() {
        return ledger(Map.of());
    }

    public ReservationLedger ledger(Map<String, String> properties) {
        Map<String, String> copy = new HashMap<>(properties);
        copy.putIfAbsent("ontocode.capacity.max-wait-millis", "200");
        for (String kind : new String[] {"execute-rules", "reasoning", "dl-query", "sparql-query", "inference-build"}) {
            copy.putIfAbsent("ontocode.capacity.kind." + kind + ".max-wait-millis", "200");
        }
        CapacityConfig config = CapacityConfig.load(copy::get, k -> null, Map.of());
        return new ReservationLedger(this, config, null, Clock.systemUTC());
    }
}
