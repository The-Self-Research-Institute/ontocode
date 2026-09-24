package self.research.ontology.owlEditor.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class ProjectWriteLockRegistry {

    static final String WAIT_TIMER = "assistant.lock.wait";
    static final String HOLD_TIMER = "assistant.lock.hold";
    static final String MODE_TAG = "mode";

    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    private final Timer exclusiveWait;
    private final Timer exclusiveHold;
    private final Timer sharedWait;
    private final Timer sharedHold;

    public ProjectWriteLockRegistry() {
        this((MeterRegistry) null);
    }

    public ProjectWriteLockRegistry(MeterRegistry meterRegistry) {
        this.exclusiveWait = timer(meterRegistry, WAIT_TIMER, "exclusive");
        this.exclusiveHold = timer(meterRegistry, HOLD_TIMER, "exclusive");
        this.sharedWait = timer(meterRegistry, WAIT_TIMER, "shared");
        this.sharedHold = timer(meterRegistry, HOLD_TIMER, "shared");
    }

    @Autowired
    public ProjectWriteLockRegistry(ObjectProvider<MeterRegistry> meterRegistry) {
        this(meterRegistry.getIfAvailable());
    }

    public <T> T runExclusive(String projectId, Callable<T> work) throws Exception {
        long waitStart = System.nanoTime();
        ReentrantReadWriteLock.WriteLock lock = lockFor(projectId).writeLock();
        lock.lock();
        try {
            record(exclusiveWait, waitStart);
            long holdStart = System.nanoTime();
            try {
                return work.call();
            } finally {
                record(exclusiveHold, holdStart);
            }
        } finally {
            lock.unlock();
        }
    }

    public <T> T runShared(String projectId, Callable<T> work) throws Exception {
        long waitStart = System.nanoTime();
        ReentrantReadWriteLock.ReadLock lock = lockFor(projectId).readLock();
        lock.lock();
        try {
            record(sharedWait, waitStart);
            long holdStart = System.nanoTime();
            try {
                return work.call();
            } finally {
                record(sharedHold, holdStart);
            }
        } finally {
            lock.unlock();
        }
    }

    private ReentrantReadWriteLock lockFor(String projectId) {
        return locks.computeIfAbsent(projectId, k -> new ReentrantReadWriteLock());
    }

    private static Timer timer(MeterRegistry registry, String name, String mode) {
        if (registry == null) {
            return null;
        }
        return Timer.builder(name).tag(MODE_TAG, mode).register(registry);
    }

    private static void record(Timer timer, long startNanos) {
        if (timer != null) {
            timer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
    }
}
