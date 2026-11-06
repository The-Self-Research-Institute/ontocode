package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class DraftPrChangeMigration implements CollectionMigration {

    private Set<String> pullRequestIds = new HashSet<>();

    @Override
    public String name() {
        return "draft_pr_changes";
    }

    @Override
    public String sourceCollection() {
        return "draft_pr_changes";
    }

    @Override
    public void prepare(SourceReader source) {
        Set<String> ids = new HashSet<>();
        if (source.exists("draft_pull_requests")) {
            try (CloseableIterator<Document> cursor = source.scan("draft_pull_requests", null, 1000)) {
                while (cursor.hasNext()) {
                    ids.add(Values.idText(cursor.next().get("_id")));
                }
            }
        }
        this.pullRequestIds = ids;
    }

    @Override
    public Skip skipReason(Document d) {
        String pr = Values.text(d, "prId");
        if (pr == null || !pullRequestIds.contains(pr)) {
            return new Skip("pull request missing", "its pull request " + pr + " does not exist");
        }
        return null;
    }

    @Override
    public List<Write> map(Document d) {
        Object embedded = d.get("change");
        boolean has = embedded instanceof Map<?, ?>;
        Document c = has ? Values.documents(new Document("x", List.of(embedded)), "x").get(0) : new Document();
        return List.of(Write.upsert("draft_pr_change", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("pr_id", Values.required(d, "prId"))
                .put("project_id", Values.text(d, "projectId"))
                .put("snapshot_key", Values.text(d, "snapshotKey"))
                .put("change_id", Values.text(c, "_id"))
                .put("change_user_id", Values.text(c, "userId"))
                .put("change_username", Values.text(c, "username"))
                .put("change_type", Values.text(c, "operationType"))
                .put("change_data", Values.json(c.get("operationData")))
                .put("change_at", Values.time(c, "timestamp"))
                .put("change_session_id", Values.text(c, "sessionId"))
                .put("change_applied", Values.bool(c, "applied", false))
                .put("has_change", has)
                .build());
    }
}
