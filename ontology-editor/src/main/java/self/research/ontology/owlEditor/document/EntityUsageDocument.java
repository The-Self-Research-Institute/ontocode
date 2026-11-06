package self.research.ontology.owlEditor.document;

import java.util.List;
import java.util.Map;

public class EntityUsageDocument {

    private String projectId;
    private String entityIri;
    private long revision;
    private long builtAt;
    private List<Map<String, String>> usages;

    public EntityUsageDocument() {}

    public EntityUsageDocument(String projectId, String entityIri, long revision,
                               List<Map<String, String>> usages) {
        this(projectId, entityIri, revision, System.currentTimeMillis(), usages);
    }

    public EntityUsageDocument(String projectId, String entityIri, long revision, long builtAt,
                               List<Map<String, String>> usages) {
        this.projectId = projectId;
        this.entityIri = entityIri;
        this.revision = revision;
        this.builtAt = builtAt;
        this.usages = usages;
    }

    public String getId() { return projectId + "::" + entityIri; }
    public String getProjectId() { return projectId; }
    public String getEntityIri() { return entityIri; }
    public long getRevision() { return revision; }
    public long getBuiltAt() { return builtAt; }
    public List<Map<String, String>> getUsages() { return usages; }
}
