package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "spreadsheet_imports")
public class SpreadsheetImportRecord {

    @Id
    private String id;

    @Indexed
    private String projectId;

    private String userId;
    private String username;
    private String fileName;
    private boolean draft;
    private String draftSessionId;
    private Instant createdAt;

    private int axiomCount;
    private int alreadyPresentCount;
    private List<String> addedTriples = new ArrayList<>();

    private boolean rolledBack;
    private Instant rolledBackAt;
    private String rolledBackBy;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public boolean isDraft() { return draft; }
    public void setDraft(boolean draft) { this.draft = draft; }
    public String getDraftSessionId() { return draftSessionId; }
    public void setDraftSessionId(String draftSessionId) { this.draftSessionId = draftSessionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public int getAxiomCount() { return axiomCount; }
    public void setAxiomCount(int axiomCount) { this.axiomCount = axiomCount; }
    public int getAlreadyPresentCount() { return alreadyPresentCount; }
    public void setAlreadyPresentCount(int alreadyPresentCount) { this.alreadyPresentCount = alreadyPresentCount; }
    public List<String> getAddedTriples() { return addedTriples; }
    public void setAddedTriples(List<String> addedTriples) { this.addedTriples = addedTriples; }
    public boolean isRolledBack() { return rolledBack; }
    public void setRolledBack(boolean rolledBack) { this.rolledBack = rolledBack; }
    public Instant getRolledBackAt() { return rolledBackAt; }
    public void setRolledBackAt(Instant rolledBackAt) { this.rolledBackAt = rolledBackAt; }
    public String getRolledBackBy() { return rolledBackBy; }
    public void setRolledBackBy(String rolledBackBy) { this.rolledBackBy = rolledBackBy; }
}
