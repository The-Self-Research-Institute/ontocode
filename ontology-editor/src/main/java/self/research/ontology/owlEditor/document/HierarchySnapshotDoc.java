package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import self.research.ontology.owlEditor.dto.OntologyDto;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Document(collection = "hierarchy_snapshots")
public class HierarchySnapshotDoc {

    public enum Status { PENDING, BUILDING, READY, FAILED, STALE }

    @Id
    private String projectId;

    private Status status = Status.PENDING;
    private String algorithmVersion;
    private String revision;
    private long builtAt;
    private String errorMessage;

    private List<OntologyDto.TreeNode> topLevelNodes;
    private int topLevelTotal;
    private int topLevelComputedLimit;

    private Map<String, List<OntologyDto.TreeNode>> childrenByParent;

    private Map<String, Object> meta = Map.of();

    @org.springframework.data.mongodb.core.index.Indexed(expireAfterSeconds = 0)
    private Date expiresAt;

    public HierarchySnapshotDoc() {}

    public static HierarchySnapshotDoc building(String projectId, String revision) {
        HierarchySnapshotDoc doc = new HierarchySnapshotDoc();
        doc.projectId = projectId;
        doc.status = Status.BUILDING;
        doc.revision = revision;
        doc.algorithmVersion = self.research.ontology.owlEditor.hierarchy.HierarchyAlgorithmVersion.CURRENT;
        doc.builtAt = System.currentTimeMillis();
        return doc;
    }

    public void markReady() {
        this.status = Status.READY;
        this.builtAt = System.currentTimeMillis();
        this.expiresAt = new Date(this.builtAt + 30L * 24 * 60 * 60 * 1000);
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public void setAlgorithmVersion(String algorithmVersion) { this.algorithmVersion = algorithmVersion; }
    public String getRevision() { return revision; }
    public void setRevision(String revision) { this.revision = revision; }
    public long getBuiltAt() { return builtAt; }
    public void setBuiltAt(long builtAt) { this.builtAt = builtAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public List<OntologyDto.TreeNode> getTopLevelNodes() { return topLevelNodes; }
    public void setTopLevelNodes(List<OntologyDto.TreeNode> topLevelNodes) { this.topLevelNodes = topLevelNodes; }
    public int getTopLevelTotal() { return topLevelTotal; }
    public void setTopLevelTotal(int topLevelTotal) { this.topLevelTotal = topLevelTotal; }
    public int getTopLevelComputedLimit() { return topLevelComputedLimit; }
    public void setTopLevelComputedLimit(int topLevelComputedLimit) { this.topLevelComputedLimit = topLevelComputedLimit; }
    public Map<String, List<OntologyDto.TreeNode>> getChildrenByParent() { return childrenByParent; }
    public void setChildrenByParent(Map<String, List<OntologyDto.TreeNode>> childrenByParent) {
        this.childrenByParent = childrenByParent;
    }
    public Map<String, Object> getMeta() { return meta; }
    public void setMeta(Map<String, Object> meta) { this.meta = meta != null ? meta : Map.of(); }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
}
