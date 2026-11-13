package self.research.ontology.migrator.collections;

import org.bson.Document;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.MappingException;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.time.OffsetDateTime;
import java.util.List;

public final class ReasoningJobMigration implements CollectionMigration {

    @Override
    public String name() {
        return "reasoning_jobs";
    }

    @Override
    public String sourceCollection() {
        return "reasoning_jobs";
    }

    @Override
    public List<Write> map(Document d) {
        OffsetDateTime updated = firstPresent(Values.time(d, "updatedAt"), Values.time(d, "completedAt"),
                Values.time(d, "startedAt"), Values.time(d, "queuedAt"));
        if (updated == null) {
            throw new MappingException("Job has no time at all, cannot fill updated_at");
        }
        return List.of(Write.upsert("reasoning_job", "job_id")
                .put("job_id", Values.idText(d.get("_id")))
                .put("job_type", Values.text(d, "jobType"))
                .put("project_id", Values.text(d, "projectId"))
                .put("status", Values.required(d, "status"))
                .put("queued_at", Values.time(d, "queuedAt"))
                .put("started_at", Values.time(d, "startedAt"))
                .put("completed_at", Values.time(d, "completedAt"))
                .put("updated_at", updated)
                .put("execution_time_ms", Values.longValue(d, "executionTimeMs", 0L))
                .put("error", Values.text(d, "error"))
                .put("result_json", Values.text(d, "resultJson"))
                .put("result_too_large", Values.bool(d, "resultTooLarge", false))
                .build());
    }

    static OffsetDateTime firstPresent(OffsetDateTime... values) {
        for (OffsetDateTime v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
