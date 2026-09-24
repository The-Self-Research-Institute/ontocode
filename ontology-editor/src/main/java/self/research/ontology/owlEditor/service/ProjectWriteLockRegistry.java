package self.research.ontology.owlEditor.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Service
public class ProjectWriteLockRegistry {

    static final String WAIT_TIMER = "assistant.lock.wait";
    static final String HOLD_TIMER = "assistant.lock.hold";
    static final String MODE_TAG = "mode";
    public static final String LOCAL_MODE = "local";
    public static final String MONGO_MODE = "mongo";

    private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> leaseGates = new ConcurrentHashMap<>();
    private final ProjectWriteLeaseManager leaseManager;
    private final Timer exclusiveWait;
    private final Timer exclusiveHold;
    private final Timer sharedWait;
    private final Timer sharedHold;

    public ProjectWriteLockRegistry() {
        this(null, null);
    }

    public ProjectWriteLockRegistry(MeterRegistry meterRegistry) {
        this(null, meterRegistry);
    }

    public ProjectWriteLockRegistry(ProjectWriteLeaseManager leaseManager, MeterRegistry meterRegistry) {
        this.leaseManager = leaseManager;
        this.exclusiveWait = timer(meterRegistry, WAIT_TIMER, "exclusive");
        this.exclusiveHold = timer(meterRegistry, HOLD_TIMER, "exclusive");
        this.sharedWait = timer(meterRegistry, WAIT_TIMER, "shared");
        this.sharedHold = timer(meterRegistry, HOLD_TIMER, "shared");
    }

    @Autowired
    public ProjectWriteLockRegistry(
            @Value("${ontocode.assistant.lock.mode:local}") String mode,
            @Value("${ontocode.assistant.lock.lease-ttl-ms:30000}") long leaseTtlMs,
            @Value("${ontocode.assistant.lock.lease-renew-interval-ms:10000}") long leaseRenewIntervalMs,
            @Value("${ontocode.assistant.lock.lease-acquire-timeout-ms:120000}") long leaseAcquireTimeoutMs,
            ObjectProvider<MongoTemplate> mongoTemplate,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this(createLeaseManager(mode, leaseTtlMs, leaseRenewIntervalMs, leaseAcquireTimeoutMs,
                mongoTemplate, meterRegistry.getIfAvailable()), meterRegistry.getIfAvailable());
    }

    static ProjectWriteLeaseManager createLeaseManager(String mode, long leaseTtlMs, long leaseRenewIntervalMs,
                                                       long leaseAcquireTimeoutMs,
                                                       ObjectProvider<MongoTemplate> mongoTemplate,
                                                       MeterRegistry meterRegistry) {
        String normalized = mode == null ? LOCAL_MODE : mode.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || LOCAL_MODE.equals(normalized)) {
            return null;
        }
        if (!MONGO_MODE.equals(normalized)) {
            throw new IllegalStateException("Unknown ontocode.assistant.lock.mode '" + mode
                    + "'; expected " + LOCAL_MODE + " or " + MONGO_MODE);
        }
        MongoTemplate template = mongoTemplate.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("ontocode.assistant.lock.mode=mongo needs a MongoTemplate bean");
        }
        ProjectWriteLeaseManager.Settings settings = ProjectWriteLeaseManager.Settings.of(
                Duration.ofMillis(leaseTtlMs),
                Duration.ofMillis(leaseRenewIntervalMs),
                Duration.ofMillis(leaseAcquireTimeoutMs));
        log.info("[WriteLock] Using Mongo write leases (ttl {} ms, renew every {} ms, wait up to {} ms)",
                leaseTtlMs, leaseRenewIntervalMs, leaseAcquireTimeoutMs);
        return new ProjectWriteLeaseManager(template, Clock.systemUTC(), settings, meterRegistry);
    }

    public String getMode() {
        return leaseManager == null ? LOCAL_MODE : MONGO_MODE;
    }

    public <T> T runExclusive(String projectId, Callable<T> work) throws Exception {
        long waitStart = System.nanoTime();
        if (leaseManager == null) {
            return runWithWriteLock(projectId, work, waitStart);
        }
        ReentrantReadWriteLock local = lockFor(projectId);
        if (local.getReadHoldCount() > 0 && !local.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("Cannot take the exclusive write lock for project " + projectId
                    + " while this thread holds its shared read lock");
        }
        ReentrantLock gate = leaseGateFor(projectId);
        gate.lock();
        try {
            ProjectWriteLeaseManager.Lease lease;
            try {
                lease = gate.getHoldCount() == 1 ? leaseManager.acquire(projectId) : null;
            } catch (Exception e) {
                record(exclusiveWait, waitStart);
                throw e;
            }
            try {
                return runWithWriteLock(projectId, work, waitStart);
            } finally {
                if (lease != null) {
                    lease.release();
                }
            }
        } finally {
            gate.unlock();
        }
    }

    private <T> T runWithWriteLock(String projectId, Callable<T> work, long waitStart) throws Exception {
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

    @PreDestroy
    public void shutdown() {
        if (leaseManager != null) {
            leaseManager.close();
        }
    }

    private ReentrantReadWriteLock lockFor(String projectId) {
        return locks.computeIfAbsent(projectId, k -> new ReentrantReadWriteLock());
    }

    private ReentrantLock leaseGateFor(String projectId) {
        return leaseGates.computeIfAbsent(projectId, k -> new ReentrantLock());
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
