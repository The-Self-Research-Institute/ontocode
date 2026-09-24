package self.research.ontology.owlEditor.service;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import self.research.ontology.owlEditor.document.ProjectWriteLeaseDocument;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class InMemoryLeaseCollection {

    final MongoTemplate template = mock(MongoTemplate.class);
    final AtomicInteger insertAttempts = new AtomicInteger();
    final AtomicInteger renewals = new AtomicInteger();
    final Queue<RuntimeException> upcomingFailures = new ConcurrentLinkedQueue<>();
    private final Map<String, ProjectWriteLeaseDocument> leases = new HashMap<>();

    InMemoryLeaseCollection() {
        when(template.insert(any(ProjectWriteLeaseDocument.class)))
                .thenAnswer(inv -> insert(inv.getArgument(0)));
        when(template.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(ProjectWriteLeaseDocument.class)))
                .thenAnswer(inv -> updateFirst(inv.getArgument(0), inv.getArgument(1)));
        when(template.remove(any(Query.class), eq(ProjectWriteLeaseDocument.class)))
                .thenAnswer(inv -> remove(inv.getArgument(0)));
    }

    synchronized void put(String projectId, String ownerToken, Instant expiresAt) {
        leases.put(projectId, ProjectWriteLeaseDocument.builder()
                .projectId(projectId)
                .ownerToken(ownerToken)
                .holder("other-node")
                .acquiredAt(expiresAt.minusSeconds(30))
                .expiresAt(expiresAt)
                .build());
    }

    synchronized ProjectWriteLeaseDocument get(String projectId) {
        ProjectWriteLeaseDocument lease = leases.get(projectId);
        return lease == null ? null : copy(lease);
    }

    synchronized void delete(String projectId) {
        leases.remove(projectId);
    }

    private void maybeFail() {
        RuntimeException failure = upcomingFailures.poll();
        if (failure != null) {
            throw failure;
        }
    }

    private synchronized ProjectWriteLeaseDocument insert(ProjectWriteLeaseDocument lease) {
        insertAttempts.incrementAndGet();
        maybeFail();
        if (leases.containsKey(lease.getProjectId())) {
            throw new DuplicateKeyException("E11000 duplicate key error collection: project_write_leases");
        }
        leases.put(lease.getProjectId(), copy(lease));
        return lease;
    }

    private static ProjectWriteLeaseDocument copy(ProjectWriteLeaseDocument lease) {
        return ProjectWriteLeaseDocument.builder()
                .projectId(lease.getProjectId())
                .ownerToken(lease.getOwnerToken())
                .holder(lease.getHolder())
                .acquiredAt(lease.getAcquiredAt())
                .expiresAt(lease.getExpiresAt())
                .build();
    }

    private synchronized UpdateResult updateFirst(Query query, UpdateDefinition update) {
        maybeFail();
        Document filter = query.getQueryObject();
        ProjectWriteLeaseDocument match = findMatch(filter);
        if (match == null) {
            return UpdateResult.acknowledged(0, 0L, null);
        }
        Document updateObject = update.getUpdateObject();
        for (String operator : updateObject.keySet()) {
            if (!"$set".equals(operator)) {
                throw new AssertionError("Unexpected update operator " + operator);
            }
        }
        Document set = updateObject.get("$set", Document.class);
        boolean onlyExpiry = set.keySet().size() == 1 && set.containsKey(ProjectWriteLeaseDocument.FIELD_EXPIRES_AT);
        for (Map.Entry<String, Object> entry : set.entrySet()) {
            switch (entry.getKey()) {
                case ProjectWriteLeaseDocument.FIELD_OWNER_TOKEN -> match.setOwnerToken((String) entry.getValue());
                case ProjectWriteLeaseDocument.FIELD_HOLDER -> match.setHolder((String) entry.getValue());
                case ProjectWriteLeaseDocument.FIELD_ACQUIRED_AT -> match.setAcquiredAt(toInstant(entry.getValue()));
                case ProjectWriteLeaseDocument.FIELD_EXPIRES_AT -> match.setExpiresAt(toInstant(entry.getValue()));
                default -> throw new AssertionError("Unexpected update field " + entry.getKey());
            }
        }
        if (onlyExpiry) {
            renewals.incrementAndGet();
        }
        return UpdateResult.acknowledged(1, 1L, null);
    }

    private synchronized DeleteResult remove(Query query) {
        maybeFail();
        ProjectWriteLeaseDocument match = findMatch(query.getQueryObject());
        if (match == null) {
            return DeleteResult.acknowledged(0);
        }
        leases.remove(match.getProjectId());
        return DeleteResult.acknowledged(1);
    }

    private ProjectWriteLeaseDocument findMatch(Document filter) {
        Object id = filter.get(ProjectWriteLeaseDocument.FIELD_ID);
        if (!(id instanceof String projectId)) {
            throw new AssertionError("Lease queries must filter on _id, got " + filter.toJson());
        }
        ProjectWriteLeaseDocument lease = leases.get(projectId);
        if (lease == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : filter.entrySet()) {
            switch (entry.getKey()) {
                case ProjectWriteLeaseDocument.FIELD_ID -> {
                }
                case ProjectWriteLeaseDocument.FIELD_OWNER_TOKEN -> {
                    if (!entry.getValue().equals(lease.getOwnerToken())) {
                        return null;
                    }
                }
                case ProjectWriteLeaseDocument.FIELD_EXPIRES_AT -> {
                    Document condition = (Document) entry.getValue();
                    if (condition.size() != 1 || !condition.containsKey("$lt")) {
                        throw new AssertionError("Unexpected expiry condition " + condition.toJson());
                    }
                    if (!lease.getExpiresAt().isBefore(toInstant(condition.get("$lt")))) {
                        return null;
                    }
                }
                default -> throw new AssertionError("Unexpected filter field " + entry.getKey());
            }
        }
        return lease;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        throw new AssertionError("Unexpected time value " + value);
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
