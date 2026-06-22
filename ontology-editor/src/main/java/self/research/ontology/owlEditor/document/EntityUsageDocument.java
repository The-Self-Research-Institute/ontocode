package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Map;

/**
 * Pre-computed entity usage index stored in MongoDB.
 *
 * Built once at import time (async), updated on mutation (invalidate affected iris).
 * Lookup is O(1) vs Fuseki SPARQL which requires blank-node traversal on every request.
 *
 * Schema: one document per (projectId, entityIri).
 */
@Document(collection = "entity_usage")
@CompoundIndexes({
    @CompoundIndex(name = "project_iri", def = "{'projectId': 1, 'entityIri': 1}", unique = true)
})
public class EntityUsageDocument {

    @Id
    private String id; // projectId + "::" + entityIri

    private String projectId;
    private String entityIri;
    private long revision;
    private long builtAt;
    private List<Map<String, String>> usages;

    public EntityUsageDocument() {}

    public EntityUsageDocument(String projectId, String entityIri, long revision,
                               List<Map<String, String>> usages) {
        this.id = projectId + "::" + entityIri;
        this.projectId = projectId;
        this.entityIri = entityIri;
        this.revision = revision;
        this.builtAt = System.currentTimeMillis();
        this.usages = usages;
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getEntityIri() { return entityIri; }
    public long getRevision() { return revision; }
    public long getBuiltAt() { return builtAt; }
    public List<Map<String, String>> getUsages() { return usages; }
}
