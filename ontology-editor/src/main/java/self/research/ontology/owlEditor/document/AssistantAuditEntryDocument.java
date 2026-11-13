package self.research.ontology.owlEditor.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantAuditEntryDocument {

    private String id;

    private String actor;
    private String projectId;
    private String sessionId;
    private String groupId;
    private String operation;
    private Long sourceRevision;
    private String provider;
    private String model;
    private String outcome;
    private String errorCode;
    private String detail;
    private Instant createdAt;
}
