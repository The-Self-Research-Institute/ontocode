package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.*;

import java.util.*;

public final class DraftPullRequestMigration implements CollectionMigration {

    @Override
    public String name() {
        return "draft_pull_requests";
    }

    @Override
    public String sourceCollection() {
        return "draft_pull_requests";
    }

    @Override
    public Skip skipReason(Document d) {
        return EditorSkips.missingProject(d);
    }

    @Override
    public List<Write> map(Document d) {
        String status = Values.text(d, "status");
        return List.of(Write.upsert("draft_pull_request", "id")
                .put("id", Values.idText(d.get("_id")))
                .put("project_id", Values.required(d, "projectId"))
                .put("author_id", Values.text(d, "authorId"))
                .put("author_username", Values.text(d, "authorUsername"))
                .put("title", Values.text(d, "title"))
                .put("description", Values.text(d, "description"))
                .put("status", status == null ? "OPEN" : status)
                .put("change_count", Values.integer(d, "changeCount", 0))
                .put("submitted_change_ids", Values.list(d, "submittedChangeIds"))
                .put("snapshot_key", Values.text(d, "snapshotKey"))
                .put("published_change_set_ids", Values.list(d, "publishedChangeSetIds"))
                .put("reverted_at", Values.time(d, "revertedAt"))
                .put("reverted_by", Values.text(d, "revertedBy"))
                .put("revert_reason", Values.text(d, "revertReason"))
                .put("restored_at", Values.time(d, "restoredAt"))
                .put("revert_started_at", Values.time(d, "revertStartedAt"))
                .put("created_at", Values.time(d, "createdAt"))
                .put("reviewed_at", Values.time(d, "reviewedAt"))
                .put("reviewer_id", Values.text(d, "reviewerId"))
                .put("review_note", Values.text(d, "reviewNote"))
                .build());
    }
}
