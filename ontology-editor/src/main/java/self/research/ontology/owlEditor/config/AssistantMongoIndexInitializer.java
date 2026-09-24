package self.research.ontology.owlEditor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class AssistantMongoIndexInitializer {

    public static final String SESSIONS_COLLECTION = "assistant_sessions";
    public static final String EDIT_GROUP_COLLECTION = "assistant_edit_group";
    public static final String AUDIT_COLLECTION = "assistant_audit";
    public static final String IDEMPOTENCY_COLLECTION = "assistant_idempotency";

    public static final Duration SESSION_TTL_GRACE = Duration.ofHours(24);
    public static final Duration AUDIT_RETENTION = Duration.ofDays(90);

    public enum Outcome { CREATED, ALREADY_PRESENT, CONFLICT, FAILED }

    public record IndexSpec(String collection, String name, LinkedHashMap<String, Sort.Direction> keys,
                            Duration expireAfter) {}

    private final MongoTemplate mongoTemplate;

    public AssistantMongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public static List<IndexSpec> requiredIndexes() {
        return List.of(
                new IndexSpec(SESSIONS_COLLECTION, "expiresAt_ttl", keys("expiresAt"), SESSION_TTL_GRACE),
                new IndexSpec(EDIT_GROUP_COLLECTION, "expiresAt_ttl", keys("expiresAt"), Duration.ZERO),
                new IndexSpec(EDIT_GROUP_COLLECTION, "projectId_targetPath_status",
                        keys("projectId", "targetPath", "status"), null),
                new IndexSpec(AUDIT_COLLECTION, "createdAt_ttl", keys("createdAt"), AUDIT_RETENTION),
                new IndexSpec(AUDIT_COLLECTION, "projectId_createdAt", keys("projectId", "createdAt"), null),
                new IndexSpec(IDEMPOTENCY_COLLECTION, "expiresAt_ttl", keys("expiresAt"), Duration.ZERO));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ensureAll();
    }

    public Map<String, Outcome> ensureAll() {
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        for (IndexSpec spec : requiredIndexes()) {
            outcomes.put(spec.collection() + "." + spec.name(), ensure(spec));
        }
        log.info("[AssistantIndexes] Index check finished: {}", outcomes);
        return outcomes;
    }

    Outcome ensure(IndexSpec spec) {
        try {
            IndexOperations ops = mongoTemplate.indexOps(spec.collection());
            Optional<IndexInfo> sameKeys = ops.getIndexInfo().stream()
                    .filter(info -> sameKeys(info, spec))
                    .findFirst();
            if (sameKeys.isPresent()) {
                if (sameOptions(sameKeys.get(), spec)) {
                    return Outcome.ALREADY_PRESENT;
                }
                log.warn("[AssistantIndexes] {} already has index '{}' on {} with different options "
                                + "(expireAfter={}); leaving it in place instead of creating '{}' (expireAfter={})",
                        spec.collection(), sameKeys.get().getName(), spec.keys().keySet(),
                        sameKeys.get().getExpireAfter().orElse(null), spec.name(), spec.expireAfter());
                return Outcome.CONFLICT;
            }
            ops.ensureIndex(toIndex(spec));
            log.info("[AssistantIndexes] Created index '{}' on {}.{}", spec.name(), spec.collection(),
                    spec.keys().keySet());
            return Outcome.CREATED;
        } catch (Exception e) {
            log.warn("[AssistantIndexes] Could not ensure index '{}' on {}: {}", spec.name(), spec.collection(),
                    e.getMessage());
            return Outcome.FAILED;
        }
    }

    static Index toIndex(IndexSpec spec) {
        Index index = new Index().named(spec.name());
        spec.keys().forEach(index::on);
        if (spec.expireAfter() != null) {
            index.expire(spec.expireAfter());
        }
        return index;
    }

    private static boolean sameKeys(IndexInfo info, IndexSpec spec) {
        List<IndexField> fields = info.getIndexFields();
        if (fields.size() != spec.keys().size()) {
            return false;
        }
        int i = 0;
        for (Map.Entry<String, Sort.Direction> key : spec.keys().entrySet()) {
            IndexField field = fields.get(i++);
            if (!key.getKey().equals(field.getKey()) || field.getDirection() != key.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameOptions(IndexInfo info, IndexSpec spec) {
        Optional<Duration> existing = info.getExpireAfter();
        if (spec.expireAfter() == null) {
            return existing.isEmpty();
        }
        return existing.isPresent() && existing.get().getSeconds() == spec.expireAfter().getSeconds();
    }

    private static LinkedHashMap<String, Sort.Direction> keys(String... fields) {
        LinkedHashMap<String, Sort.Direction> keys = new LinkedHashMap<>();
        for (String field : fields) {
            keys.put(field, Sort.Direction.ASC);
        }
        return keys;
    }
}
