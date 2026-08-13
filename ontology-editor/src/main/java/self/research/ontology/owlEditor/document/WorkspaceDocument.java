package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "workspaces")
public class WorkspaceDocument {

    @Id
    private String id;
    private String workspaceId;
    private String ownerId;
    private String subscriptionPlan;

    public String getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getOwnerId() { return ownerId; }
    public String getSubscriptionPlan() { return subscriptionPlan; }
}
