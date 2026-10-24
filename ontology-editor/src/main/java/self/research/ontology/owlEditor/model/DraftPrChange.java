package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "draft_pr_changes")
public class DraftPrChange {

    @Id
    private String id;

    @Indexed
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
    public String getPrId() { return prId; }
    public String getProjectId() { return projectId; }
    public String getSnapshotKey() { return snapshotKey; }
    public DraftChange getChange() { return change; }
}
