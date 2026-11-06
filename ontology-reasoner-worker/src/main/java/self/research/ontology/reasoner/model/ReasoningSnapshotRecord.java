package self.research.ontology.reasoner.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReasoningSnapshotRecord {

    private String id;
    private String projectId;
    private long revision;
    private long tripleCount;
    private String reasoner;
    private String rulesFingerprint;
    private String resultJson;
    private Instant createdAt;
    private Instant updatedAt;
}
