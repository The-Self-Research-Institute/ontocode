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
@Document("reasoning_snapshots")
public class ReasoningSnapshotRecord {

    @Id
    private String id;
    private String projectId;
    private long revision;
    private long tripleCount;
    private String reasoner;
    private String resultJson;
    private Instant createdAt;
    private Instant updatedAt;
}
