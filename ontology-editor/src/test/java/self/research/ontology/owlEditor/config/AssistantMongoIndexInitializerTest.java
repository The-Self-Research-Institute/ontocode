package self.research.ontology.owlEditor.config;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexOptions;
import self.research.ontology.owlEditor.config.AssistantMongoIndexInitializer.Outcome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantMongoIndexInitializerTest {

    private final Map<String, FakeIndexOperations> collections = new HashMap<>();
    private MongoTemplate mongoTemplate;
    private AssistantMongoIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.indexOps(anyString())).thenAnswer(inv ->
                collections.computeIfAbsent(inv.getArgument(0), name -> new FakeIndexOperations()));
        initializer = new AssistantMongoIndexInitializer(mongoTemplate);
    }

    @Test
    void createsEveryRequiredIndexWithTheExpectedKeysAndTtl() {
        Map<String, Outcome> outcomes = initializer.ensureAll();

        assertTrue(outcomes.values().stream().allMatch(o -> o == Outcome.CREATED), outcomes.toString());

        assertEquals(86400L, ttlSeconds("assistant_sessions", "expiresAt_ttl"));
        assertEquals(0L, ttlSeconds("assistant_edit_group", "expiresAt_ttl"));
        assertEquals(90L * 86400L, ttlSeconds("assistant_audit", "createdAt_ttl"));
        assertEquals(0L, ttlSeconds("assistant_idempotency", "expiresAt_ttl"));

        assertEquals(new Document("projectId", 1).append("targetPath", 1).append("status", 1),
                keys("assistant_edit_group", "projectId_targetPath_status"));
        assertEquals(new Document("userEmail", 1).append("status", 1).append("expiresAt", 1),
                keys("assistant_sessions", "userEmail_status_expiresAt"));
        assertEquals(new Document("projectId", 1).append("createdAt", 1),
                keys("assistant_audit", "projectId_createdAt"));
        assertEquals(null, stored("assistant_audit", "projectId_createdAt").get("expireAfterSeconds"));
    }

    @Test
    void secondRunIsIdempotent() {
        initializer.ensureAll();
        int before = totalIndexes();

        Map<String, Outcome> outcomes = initializer.ensureAll();

        assertTrue(outcomes.values().stream().allMatch(o -> o == Outcome.ALREADY_PRESENT), outcomes.toString());
        assertEquals(before, totalIndexes());
    }

    @Test
    void existingIndexWithSameKeysButDifferentTtlIsLeftAloneAndOthersStillCreated() {
        FakeIndexOperations sessions = collections.computeIfAbsent("assistant_sessions", n -> new FakeIndexOperations());
        sessions.indexes.add(new Document("key", new Document("expiresAt", 1))
                .append("name", "legacy_expiry").append("expireAfterSeconds", 0L));

        Map<String, Outcome> outcomes = initializer.ensureAll();

        assertEquals(Outcome.CONFLICT, outcomes.get("assistant_sessions.expiresAt_ttl"));
        assertEquals(1, sessions.indexes.stream().filter(d -> d.get("key", Document.class).containsKey("expiresAt")
                && d.get("key", Document.class).size() == 1).count());
        assertEquals("legacy_expiry", sessions.indexes.get(0).getString("name"));
        assertEquals(0L, sessions.indexes.get(0).get("expireAfterSeconds"));
        assertEquals(Outcome.CREATED, outcomes.get("assistant_audit.createdAt_ttl"));
    }

    @Test
    void annotationCreatedEditGroupTtlIsRecognisedAsPresent() {
        FakeIndexOperations groups = collections.computeIfAbsent("assistant_edit_group", n -> new FakeIndexOperations());
        groups.indexes.add(new Document("key", new Document("expiresAt", 1))
                .append("name", "expiresAt_ttl").append("expireAfterSeconds", 0));

        Map<String, Outcome> outcomes = initializer.ensureAll();

        assertEquals(Outcome.ALREADY_PRESENT, outcomes.get("assistant_edit_group.expiresAt_ttl"));
    }

    @Test
    void failuresAreContainedPerIndexAndNeverPropagate() {
        FakeIndexOperations audit = collections.computeIfAbsent("assistant_audit", n -> new FakeIndexOperations());
        audit.failOnCreate = true;

        Map<String, Outcome> outcomes = initializer.ensureAll();

        assertEquals(Outcome.FAILED, outcomes.get("assistant_audit.createdAt_ttl"));
        assertEquals(Outcome.FAILED, outcomes.get("assistant_audit.projectId_createdAt"));
        assertEquals(Outcome.CREATED, outcomes.get("assistant_sessions.expiresAt_ttl"));
    }

    @Test
    void unreachableMongoDoesNotStopStartup() {
        when(mongoTemplate.indexOps(anyString())).thenThrow(new IllegalStateException("connection refused"));

        initializer.onApplicationReady();

        assertTrue(initializer.ensureAll().values().stream().allMatch(o -> o == Outcome.FAILED));
    }

    private int totalIndexes() {
        return collections.values().stream().mapToInt(c -> c.indexes.size()).sum();
    }

    private Document stored(String collection, String name) {
        return collections.get(collection).indexes.stream()
                .filter(d -> name.equals(d.getString("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing index " + collection + "." + name));
    }

    private Document keys(String collection, String name) {
        return stored(collection, name).get("key", Document.class);
    }

    private long ttlSeconds(String collection, String name) {
        return ((Number) stored(collection, name).get("expireAfterSeconds")).longValue();
    }

    private static final class FakeIndexOperations implements IndexOperations {
        private final List<Document> indexes = new ArrayList<>();
        private boolean failOnCreate;

        @Override
        public String ensureIndex(IndexDefinition indexDefinition) {
            if (failOnCreate) {
                throw new IllegalStateException("not authorized to create index");
            }
            Document options = indexDefinition.getIndexOptions();
            Document stored = new Document("key", new Document(indexDefinition.getIndexKeys()))
                    .append("name", options.getString("name"));
            if (options.containsKey("expireAfterSeconds")) {
                stored.append("expireAfterSeconds", options.get("expireAfterSeconds"));
            }
            indexes.add(stored);
            return options.getString("name");
        }

        @Override
        public void alterIndex(String name, IndexOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dropIndex(String name) {
            indexes.removeIf(d -> name.equals(d.getString("name")));
        }

        @Override
        public void dropAllIndexes() {
            indexes.clear();
        }

        @Override
        public List<IndexInfo> getIndexInfo() {
            return indexes.stream().map(IndexInfo::indexInfoOf).toList();
        }
    }
}
