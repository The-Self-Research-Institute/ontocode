package self.research.ontology.common.admission;

import java.util.concurrent.atomic.AtomicLong;

public final class CacheAccount {

    private final String name;
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong underflows = new AtomicLong();

    CacheAccount(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void add(long delta) {
        if (delta > 0) {
            bytes.addAndGet(delta);
        }
    }

    public void remove(long delta) {
        if (delta <= 0) {
            return;
        }
        while (true) {
            long current = bytes.get();
            long next = Math.max(0, current - delta);
            if (bytes.compareAndSet(current, next)) {
                if (current < delta) {
                    underflows.incrementAndGet();
                }
                return;
            }
        }
    }

    public long bytes() {
        return bytes.get();
    }

    public long underflowCount() {
        return underflows.get();
    }
}
