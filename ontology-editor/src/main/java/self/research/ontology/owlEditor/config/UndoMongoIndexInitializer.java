package self.research.ontology.owlEditor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class UndoMongoIndexInitializer {

    public record Spec(String collection, String name, List<String> keys, int[] directions, boolean unique, boolean sparse) {}

    private final MongoTemplate mongoTemplate;

    public UndoMongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public static List<Spec> requiredIndexes() {
        return List.of(
                new Spec("undo_intents", "project_op", List.of("projectId", "opId"), new int[] {1, 1}, true, true),
                new Spec("undo_intents", "project_status", List.of("projectId", "status"), new int[] {1, 1}, false, false),
                new Spec("history_changes", "project_draft_user_ts", List.of("projectId", "draft", "userId", "timestamp"),
                        new int[] {1, 1, 1, -1}, false, false),
                new Spec("history_changes", "project_entity_ts", List.of("projectId", "entityIRI", "timestamp"),
                        new int[] {1, 1, -1}, false, false),
                new Spec("history_changes", "project_changeset", List.of("projectId", "changeSetId"), new int[] {1, 1}, false, false),
                new Spec("history_changes", "project_draft_ts", List.of("projectId", "draft", "timestamp"), new int[] {1, 1, -1}, false, false),
                new Spec("history_changes", "project_published_pr", List.of("projectId", "publishedPrId"), new int[] {1, 1}, false, true),
                new Spec("draft_pr_changes", "pr", List.of("prId"), new int[] {1}, false, false));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ensureAll();
    }

    public Map<String, Boolean> ensureAll() {
        Map<String, Boolean> outcomes = new LinkedHashMap<>();
        for (Spec spec : requiredIndexes()) {
            outcomes.put(spec.collection() + "." + spec.name(), ensure(spec));
        }
        log.info("[UndoIndexes] Index check finished: {}", outcomes);
        return outcomes;
    }

    boolean ensure(Spec spec) {
        try {
            var operations = mongoTemplate.indexOps(spec.collection());
            for (IndexInfo existing : operations.getIndexInfo()) {
                if (spec.name().equals(existing.getName())) {
                    return true;
                }
            }
            Index index = new Index().named(spec.name());
            for (int i = 0; i < spec.keys().size(); i++) {
                index.on(spec.keys().get(i), spec.directions()[i] < 0 ? Sort.Direction.DESC : Sort.Direction.ASC);
            }
            if (spec.unique()) {
                index.unique();
            }
            if (spec.sparse()) {
                index.sparse();
            }
            operations.ensureIndex(index);
            return true;
        } catch (RuntimeException e) {
            log.warn("[UndoIndexes] Could not create index '{}' on {}: {}", spec.name(), spec.collection(), e.getMessage());
            return false;
        }
    }
}
