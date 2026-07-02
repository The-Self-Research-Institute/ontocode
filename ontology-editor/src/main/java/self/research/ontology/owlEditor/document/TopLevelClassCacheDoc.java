package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import self.research.ontology.owlEditor.dto.OntologyDto;

import java.util.Date;
import java.util.List;

/**
 * Persistent MongoDB cache for pre-computed top-level class results.
 *
 * <p>Survives Fuseki/TDB2 restarts — after the first SPARQL computation the
 * enriched result is stored here. All subsequent requests (including those
 * immediately after a container restart) are served from MongoDB in <5 ms
 * instead of hitting a cold TDB2 dataset (which can take 30-85 s).
 *
 * <p>Eviction happens explicitly on every ontology mutation and import.
 * The {@code expiresAt} TTL index is a safety net: MongoDB auto-deletes
 * documents that survive 7 days without being refreshed, preventing stale
 * data if an eviction point is ever missed.
 */
@Document(collection = "top_level_class_cache")
public class TopLevelClassCacheDoc {

    @Id
    private String projectId;

    /** Fully enriched nodes (labels, descriptions, hasChildren, equivalentClasses). */
    private List<OntologyDto.TreeNode> nodes;

    /** The limit value used when this entry was computed. */
    private int computedWithLimit;

    /** Epoch ms — used for freshness checks in application code. */
    private long computedAt;

    /**
     * TTL index: MongoDB automatically deletes this document 7 days after
     * {@code expiresAt}. Acts as a safety net against stale cache if an
     * eviction call is ever missed.
     */
    @Indexed(expireAfterSeconds = 0)
    private Date expiresAt;

    public TopLevelClassCacheDoc() {}

    public TopLevelClassCacheDoc(String projectId, List<OntologyDto.TreeNode> nodes, int computedWithLimit) {
        this.projectId = projectId;
        this.nodes = nodes;
        this.computedWithLimit = computedWithLimit;
        this.computedAt = System.currentTimeMillis();
        // Auto-expire from MongoDB after 7 days (application-level eviction is the primary mechanism)
        this.expiresAt = new Date(this.computedAt + 7L * 24 * 60 * 60 * 1000);
    }

    /**
     * Returns true if this entry can serve a request for {@code requestedLimit} nodes.
     * True when: stored count >= requested, OR stored count < computedWithLimit
     * (meaning all top-level classes fit within the limit and no more exist).
     */
    public boolean coversLimit(int requestedLimit) {
        if (nodes == null) return false;
        return nodes.size() >= requestedLimit || nodes.size() < computedWithLimit;
    }

    /** Stale after 24 hours — caller should recompute and refresh. */
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
