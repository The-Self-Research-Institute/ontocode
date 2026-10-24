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
    private DraftChange change;

    public DraftPrChange() {
    }

    public DraftPrChange(String projectId, String prId, DraftChange change) {
        this.projectId = projectId;
        this.prId = prId;
        this.change = change;
    }

    public String getId() { return id; }
    public String getPrId() { return prId; }
    public String getProjectId() { return projectId; }
    public DraftChange getChange() { return change; }
}
