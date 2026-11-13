package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.MappingException;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.time.OffsetDateTime;
import java.util.List;

public final class ReasoningSnapshotMigration implements CollectionMigration {

    @Override
    public String name() {
        return "reasoning_snapshots";
    }

    @Override
    public String sourceCollection() {
        return "reasoning_snapshots";
    }

    @Override
    public List<Write> map(Document d) {
        OffsetDateTime updated = ReasoningJobMigration.firstPresent(Values.time(d, "updatedAt"), Values.time(d, "createdAt"));
        if (updated == null) {
            throw new MappingException("Snapshot has no time at all, cannot fill updated_at");
        }
        Long revision = Values.longValue(d, "revision");
        Long triples = Values.longValue(d, "tripleCount");
        if (revision == null || triples == null) {
            throw new MappingException("Snapshot is missing revision or tripleCount");
        }
        return List.of(Write.upsert("reasoning_snapshot", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("revision", revision)
                .put("triple_count", triples)
                .put("reasoner", Values.text(d, "reasoner"))
                .put("rules_fingerprint", Values.text(d, "rulesFingerprint"))
                .put("result_json", Values.text(d, "resultJson"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("updated_at", updated)
                .build());
    }
}
