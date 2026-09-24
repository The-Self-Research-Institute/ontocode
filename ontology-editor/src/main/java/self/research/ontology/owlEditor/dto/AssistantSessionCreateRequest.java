package self.research.ontology.owlEditor.dto;

import lombok.Data;

@Data
public class AssistantSessionCreateRequest {
    private String projectId;
    private String documentPath;
    private String actionType;
    private String actionContext;
    private String provider;
    private String model;
}
