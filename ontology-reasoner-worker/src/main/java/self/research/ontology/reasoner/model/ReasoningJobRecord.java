package self.research.ontology.reasoner.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("reasoning_jobs")
public class ReasoningJobRecord {

    @Id
    private String jobId;
    private String jobType;
    private String projectId;
    private String status;
    private Instant queuedAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant updatedAt;
    private long executionTimeMs;
    private String error;
    private String resultJson;
    private boolean resultTooLarge;
}
