package self.research.ontology.owlEditor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantSessionResponse {
    private String sessionId;
    private Snapshot snapshot;
    private Budget budget;
    private Instant expiresAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Snapshot {
        private String projectId;
        private String documentPath;
        private long revision;
        private String actionType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Budget {
        private int retrievalCallsRemaining;
        private int maxRetrievalCalls;
    }
}
