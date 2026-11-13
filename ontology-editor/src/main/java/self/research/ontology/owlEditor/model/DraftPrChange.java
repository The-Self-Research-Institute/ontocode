package self.research.ontology.owlEditor.model;

public class DraftPrChange {

    private String id;

    private String prId;

    private String projectId;
    private String snapshotKey;
    private DraftChange change;

    public DraftPrChange() {
    }

    public DraftPrChange(String projectId, String prId, String snapshotKey, DraftChange change) {
        this.projectId = projectId;
        this.prId = prId;
        this.snapshotKey = snapshotKey;
        this.change = change;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPrId() { return prId; }
    public String getProjectId() { return projectId; }
    public String getSnapshotKey() { return snapshotKey; }
    public DraftChange getChange() { return change; }
}
