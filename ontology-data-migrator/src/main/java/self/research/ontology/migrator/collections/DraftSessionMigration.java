package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class DraftSessionMigration implements CollectionMigration {

    @Override
    public String name() {
        return "draft_sessions";
    }

    @Override
    public String sourceCollection() {
        return "draft_sessions";
    }

    @Override
    public List<UniqueRule> uniqueRules() {
        return List.of(UniqueRule.pair("project_id+user_id", "projectId", "userId"));
    }

    @Override
    public java.time.Instant recency(Document d) {
        return Values.recency(d, "baselineAt");
    }

    @Override
    public List<Write> map(Document d) {
        return List.of(Write.upsert("draft_session", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("user_id", Values.required(d, "userId"))
                .put("baseline_main_revision", Values.longValue(d, "baselineMainRevision", 0L))
                .put("baseline_main_triple_count", Values.longValue(d, "baselineMainTripleCount", 0L))
                .put("baseline_at", Values.time(d, "baselineAt"))
                .put("baseline_snapshot_path", Values.text(d, "baselineSnapshotPath"))
                .put("copy_status", Values.text(d, "copyStatus"))
                .put("prefixes", Values.json(d.get("prefixes")))
                .build());
    }
}
