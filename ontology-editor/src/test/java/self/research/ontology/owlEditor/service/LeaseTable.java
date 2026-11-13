package self.research.ontology.owlEditor.service;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.owlEditor.TestDatabase;
import self.research.ontology.owlEditor.repository.JdbcProjectWriteLeaseRepository;
import self.research.ontology.owlEditor.repository.ProjectWriteLeaseRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

final class LeaseTable {

    final ProjectWriteLeaseRepository repository;
    final AtomicInteger insertAttempts = new AtomicInteger();
    final AtomicInteger renewals = new AtomicInteger();
    final Queue<RuntimeException> upcomingFailures = new ConcurrentLinkedQueue<>();
    private final JdbcTemplate jdbc = TestDatabase.jdbc();

    LeaseTable() {
        jdbc.update("delete from project_write_lease");
        JdbcProjectWriteLeaseRepository real = new JdbcProjectWriteLeaseRepository(jdbc);
        repository = new ProjectWriteLeaseRepository() {
            @Override
            public Acquisition tryAcquire(String projectId, String ownerToken, String holder, Instant now, Instant expiresAt) {
                insertAttempts.incrementAndGet();
                maybeFail();
                return real.tryAcquire(projectId, ownerToken, holder, now, expiresAt);
            }

            @Override
            public boolean renew(String projectId, String ownerToken, Instant expiresAt) {
                maybeFail();
                boolean renewed = real.renew(projectId, ownerToken, expiresAt);
                if (renewed) {
                    renewals.incrementAndGet();
                }
                return renewed;
            }

            @Override
            public boolean release(String projectId, String ownerToken) {
                maybeFail();
                return real.release(projectId, ownerToken);
            }
        };
    }

    void put(String projectId, String ownerToken, Instant expiresAt) {
        jdbc.update("insert into project_write_lease (project_id, owner_token, holder, acquired_at, expires_at) "
                        + "values (?, ?, 'other-node', ?, ?) on conflict (project_id) do update set "
                        + "owner_token = excluded.owner_token, holder = excluded.holder, "
                        + "acquired_at = excluded.acquired_at, expires_at = excluded.expires_at",
                projectId, ownerToken, Instants.toDb(expiresAt.minusSeconds(30)), Instants.toDb(expiresAt));
    }

    LeaseRow get(String projectId) {
        List<LeaseRow> rows = jdbc.query("select * from project_write_lease where project_id = ?",
                (rs, i) -> new LeaseRow(rs.getString("project_id"), rs.getString("owner_token"), rs.getString("holder"),
                        Instants.read(rs, "acquired_at"), Instants.read(rs, "expires_at")), projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    void delete(String projectId) {
        jdbc.update("delete from project_write_lease where project_id = ?", projectId);
    }

    private void maybeFail() {
        RuntimeException failure = upcomingFailures.poll();
        if (failure != null) {
            throw failure;
        }
    }

    record LeaseRow(String projectId, String ownerToken, String holder, Instant acquiredAt, Instant expiresAt) {

        String getProjectId() {
            return projectId;
        }

        String getOwnerToken() {
            return ownerToken;
        }

        String getHolder() {
            return holder;
        }

        Instant getAcquiredAt() {
            return acquiredAt;
        }

        Instant getExpiresAt() {
            return expiresAt;
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
