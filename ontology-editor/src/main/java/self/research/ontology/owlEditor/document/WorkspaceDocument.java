package self.research.ontology.owlEditor.document;

public class WorkspaceDocument {

    private final String id;
    private final String workspaceId;
    private final String ownerId;
    private final String subscriptionPlan;

    public WorkspaceDocument(String id, String workspaceId, String ownerId, String subscriptionPlan) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.ownerId = ownerId;
        this.subscriptionPlan = subscriptionPlan;
    }

    public String getId() { return id; }
    public String getWorkspaceId() { return workspaceId; }
    public String getOwnerId() { return ownerId; }
    public String getSubscriptionPlan() { return subscriptionPlan; }
}
