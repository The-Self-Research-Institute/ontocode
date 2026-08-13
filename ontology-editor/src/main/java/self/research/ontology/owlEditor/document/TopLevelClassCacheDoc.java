package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import self.research.ontology.owlEditor.dto.OntologyDto;

import java.util.Date;
import java.util.List;

@Document(collection = "top_level_class_cache")
public class TopLevelClassCacheDoc {

    @Id
    private String projectId;

    private List<OntologyDto.TreeNode> nodes;

    private int computedWithLimit;

    private long computedAt;

    @Indexed(expireAfterSeconds = 0)
    private Date expiresAt;

    public TopLevelClassCacheDoc() {}

    public TopLevelClassCacheDoc(String projectId, List<OntologyDto.TreeNode> nodes, int computedWithLimit) {
        this.projectId = projectId;
        this.nodes = nodes;
        this.computedWithLimit = computedWithLimit;
        this.computedAt = System.currentTimeMillis();

        this.expiresAt = new Date(this.computedAt + 7L * 24 * 60 * 60 * 1000);
    }

    public boolean coversLimit(int requestedLimit) {
        if (nodes == null) return false;
        return nodes.size() >= requestedLimit || nodes.size() < computedWithLimit;
    }

    public boolean isStale() {
        return System.currentTimeMillis() - computedAt > 24L * 60 * 60 * 1000;
    }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public List<OntologyDto.TreeNode> getNodes() { return nodes; }
    public void setNodes(List<OntologyDto.TreeNode> nodes) { this.nodes = nodes; }
    public int getComputedWithLimit() { return computedWithLimit; }
    public void setComputedWithLimit(int computedWithLimit) { this.computedWithLimit = computedWithLimit; }
    public long getComputedAt() { return computedAt; }
    public void setComputedAt(long computedAt) { this.computedAt = computedAt; }
    public Date getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Date expiresAt) { this.expiresAt = expiresAt; }
}
