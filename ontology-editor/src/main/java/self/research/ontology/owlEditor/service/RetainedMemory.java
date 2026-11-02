package self.research.ontology.owlEditor.service;

import self.research.ontology.common.admission.CacheAccount;
import self.research.ontology.common.admission.CapacityConfig;
import self.research.ontology.common.admission.CgroupAwareMemorySignals;
import self.research.ontology.common.admission.Estimates;
import self.research.ontology.common.admission.ReservationLedger;

import java.lang.ref.Cleaner;
import java.util.Collection;
import java.util.Map;

public final class RetainedMemory {

    public static final long BYTES_PER_ONTOLOGY_AXIOM = Estimates.DEFAULT_ESTIMATED_BYTES_PER_TRIPLE_LOAD;
    public static final long BYTES_PER_REASONER_AXIOM = Estimates.DEFAULT_ESTIMATED_BYTES_PER_TRIPLE_RULE_EXECUTE;
    public static final long BYTES_PER_RULE_RUN_AXIOM = 1_000L;
    public static final long BYTES_PER_CACHED_NODE = 256L;
    public static final long BYTES_PER_MIRRORED_TRIPLE = 400L;
    public static final long BYTES_PER_CHARACTER = 2L;
    public static final long BYTES_PER_PARSED_ITEM_LINE = 200L;
    private static final int NODE_COUNT_LIMIT = 5_000_000;

    private static final Cleaner CLEANER = Cleaner.create(r -> {
        Thread t = new Thread(r, "retained-memory-cleaner");
        t.setDaemon(true);
        return t;
    });

    private static final ReservationLedger DETACHED = new ReservationLedger(
            new CgroupAwareMemorySignals(), CapacityConfig.defaults(), null, null);

    private RetainedMemory() {}

    public static CacheAccount detachedAccount(String name) {
        return DETACHED.cacheAccount(name);
    }

    public static void retainUntilCollected(CacheAccount account, Object holder, long bytes) {
        if (holder == null || bytes <= 0) {
            return;
        }
        account.add(bytes);
        CLEANER.register(holder, new Release(account, bytes));
    }

    public static long ontologyBytes(long axiomCount) {
        return saturatingMultiply(axiomCount, BYTES_PER_ONTOLOGY_AXIOM);
    }

    public static long reasonerBytes(long axiomCount) {
        return saturatingMultiply(axiomCount, BYTES_PER_REASONER_AXIOM);
    }

    public static long nodeBytes(Object structure) {
        return saturatingMultiply(countNodes(structure, new int[] {0}), BYTES_PER_CACHED_NODE);
    }

    private static long countNodes(Object value, int[] seen) {
        if (value == null || seen[0] >= NODE_COUNT_LIMIT) {
            return 0;
        }
        long count = 1;
        seen[0]++;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                count += countNodes(e.getValue(), seen);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                count += countNodes(item, seen);
            }
        } else if (value instanceof CharSequence text) {
            count += text.length() * BYTES_PER_CHARACTER / BYTES_PER_CACHED_NODE;
        }
        return count;
    }

    static long saturatingMultiply(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0;
        }
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    private static final class Release implements Runnable {
        private final CacheAccount account;
        private final long bytes;

        Release(CacheAccount account, long bytes) {
            this.account = account;
            this.bytes = bytes;
        }

        @Override
        public void run() {
            account.remove(bytes);
        }
    }
}
