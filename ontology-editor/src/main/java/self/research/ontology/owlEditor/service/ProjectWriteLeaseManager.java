package self.research.ontology.owlEditor.service;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import self.research.ontology.owlEditor.document.ProjectWriteLeaseDocument;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class ProjectWriteLeaseManager implements AutoCloseable {

    static final String TIMEOUT_COUNTER = "assistant.lock.lease.timeout";
    static final String LOST_COUNTER = "assistant.lock.lease.lost";

    public record Settings(Duration leaseTtl, Duration renewInterval, Duration acquireTimeout,
                           Duration initialBackoff, Duration maxBackoff) {

        public Settings {
            Objects.requireNonNull(leaseTtl, "leaseTtl");
            Objects.requireNonNull(renewInterval, "renewInterval");
            Objects.requireNonNull(acquireTimeout, "acquireTimeout");
            Objects.requireNonNull(initialBackoff, "initialBackoff");
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            if (leaseTtl.isNegative() || leaseTtl.isZero()) {
                throw new IllegalArgumentException("Lease TTL must be positive");
            }
            if (renewInterval.isNegative() || renewInterval.isZero() || renewInterval.compareTo(leaseTtl) >= 0) {
                throw new IllegalArgumentException("Lease renew interval must be positive and shorter than the lease TTL");
            }
            if (acquireTimeout.isNegative()) {
                throw new IllegalArgumentException("Lease acquire timeout must not be negative");
            }
            if (initialBackoff.isNegative() || initialBackoff.isZero() || maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("Lease backoff must be positive and maxBackoff must be at least initialBackoff");
            }
        }

        public static Settings of(Duration leaseTtl, Duration renewInterval, Duration acquireTimeout) {
            return new Settings(leaseTtl, renewInterval, acquireTimeout, Duration.ofMillis(50), Duration.ofSeconds(1));
        }
    }

    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final Settings settings;
    private final String holder;
    private final ScheduledThreadPoolExecutor renewer;
    private final Counter timeouts;
    private final Counter lostLeases;

    public ProjectWriteLeaseManager(MongoTemplate mongoTemplate, Clock clock, Settings settings, MeterRegistry meterRegistry) {
        this.mongoTemplate = Objects.requireNonNull(mongoTemplate, "mongoTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.holder = ManagementFactory.getRuntimeMXBean().getName();
        this.renewer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "project-write-lease-renewer");
            thread.setDaemon(true);
            return thread;
        });
        this.renewer.setRemoveOnCancelPolicy(true);
        this.timeouts = meterRegistry == null ? null : Counter.builder(TIMEOUT_COUNTER).register(meterRegistry);
        this.lostLeases = meterRegistry == null ? null : Counter.builder(LOST_COUNTER).register(meterRegistry);
    }

    public Settings getSettings() {
        return settings;
    }

    public Lease acquire(String projectId) throws InterruptedException {
        Objects.requireNonNull(projectId, "projectId");
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + settings.acquireTimeout().toNanos();
        long backoffMillis = settings.initialBackoff().toMillis();
        DataAccessException lastFailure = null;
        while (true) {
            try {
                if (tryAcquire(projectId, token)) {
                    return startRenewal(projectId, token);
                }
                lastFailure = null;
            } catch (DataAccessException e) {
                lastFailure = e;
                log.warn("[WriteLease] Could not reach the lease store for project {}: {}", projectId, e.getMessage());
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                if (timeouts != null) {
                    timeouts.increment();
                }
                throw new ProjectWriteLeaseUnavailableException(projectId, settings.acquireTimeout(), lastFailure);
            }
            long jittered = backoffMillis / 2 + ThreadLocalRandom.current().nextLong(backoffMillis / 2 + 1);
            Thread.sleep(Math.max(1, Math.min(remainingMillis, jittered)));
            backoffMillis = Math.min(backoffMillis * 2, settings.maxBackoff().toMillis());
        }
    }

    private boolean tryAcquire(String projectId, String token) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(settings.leaseTtl());
        try {
            mongoTemplate.insert(ProjectWriteLeaseDocument.builder()
                    .projectId(projectId)
                    .ownerToken(token)
                    .holder(holder)
                    .acquiredAt(now)
                    .expiresAt(expiresAt)
                    .build());
            return true;
        } catch (DuplicateKeyException alreadyHeld) {
            Query expired = Query.query(Criteria.where(ProjectWriteLeaseDocument.FIELD_ID).is(projectId)
                    .and(ProjectWriteLeaseDocument.FIELD_EXPIRES_AT).lt(Date.from(now)));
            Update takeOver = new Update()
                    .set(ProjectWriteLeaseDocument.FIELD_OWNER_TOKEN, token)
                    .set(ProjectWriteLeaseDocument.FIELD_HOLDER, holder)
                    .set(ProjectWriteLeaseDocument.FIELD_ACQUIRED_AT, Date.from(now))
                    .set(ProjectWriteLeaseDocument.FIELD_EXPIRES_AT, Date.from(expiresAt));
            UpdateResult result = mongoTemplate.updateFirst(expired, takeOver, ProjectWriteLeaseDocument.class);
            if (result.getMatchedCount() > 0) {
                log.warn("[WriteLease] Took over an expired write lease for project {}", projectId);
                return true;
            }
            return false;
        }
    }

    private Lease startRenewal(String projectId, String token) {
        Lease lease = new Lease(projectId, token);
        long intervalMillis = settings.renewInterval().toMillis();
        try {
            lease.renewal = renewer.scheduleAtFixedRate(lease::renew, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            lease.release();
            throw e;
        }
        return lease;
    }

    private Query ownedBy(String projectId, String token) {
        return Query.query(Criteria.where(ProjectWriteLeaseDocument.FIELD_ID).is(projectId)
                .and(ProjectWriteLeaseDocument.FIELD_OWNER_TOKEN).is(token));
    }

    @Override
    public void close() {
        renewer.shutdownNow();
    }

    public final class Lease {

        private final String projectId;
        private final String ownerToken;
        private final AtomicBoolean lost = new AtomicBoolean(false);
        private final AtomicBoolean released = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> renewal;

        private Lease(String projectId, String ownerToken) {
            this.projectId = projectId;
            this.ownerToken = ownerToken;
        }

        public String getProjectId() {
            return projectId;
        }

        public String getOwnerToken() {
            return ownerToken;
        }

        public boolean isLost() {
            return lost.get();
        }

        void renew() {
            if (released.get() || lost.get()) {
                return;
            }
            try {
                Instant expiresAt = clock.instant().plus(settings.leaseTtl());
                UpdateResult result = mongoTemplate.updateFirst(ownedBy(projectId, ownerToken),
                        new Update().set(ProjectWriteLeaseDocument.FIELD_EXPIRES_AT, Date.from(expiresAt)),
                        ProjectWriteLeaseDocument.class);
                if (result.getMatchedCount() == 0 && !released.get() && lost.compareAndSet(false, true)) {
                    if (lostLeases != null) {
                        lostLeases.increment();
                    }
                    log.error("[WriteLease] Lost the write lease for project {} while work was still running", projectId);
                    ScheduledFuture<?> scheduled = renewal;
                    if (scheduled != null) {
                        scheduled.cancel(false);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[WriteLease] Could not renew the write lease for project {}: {}", projectId, e.getMessage());
            }
        }

        public void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> scheduled = renewal;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            try {
                DeleteResult result = mongoTemplate.remove(ownedBy(projectId, ownerToken), ProjectWriteLeaseDocument.class);
                if (result.getDeletedCount() == 0) {
                    log.warn("[WriteLease] Write lease for project {} was no longer ours at release", projectId);
                }
            } catch (RuntimeException e) {
                log.warn("[WriteLease] Could not release the write lease for project {}; it will expire on its own: {}",
                        projectId, e.getMessage());
            }
        }
    }
}
