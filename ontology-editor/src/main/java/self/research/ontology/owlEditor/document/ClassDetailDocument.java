package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

/**
 * Persistent class details cache (replaces Caffeine classDetails / classAnnotations).
 *
 * Stored once per (projectId, classIri) on first SPARQL fetch, invalidated per-IRI
 * on mutation. Survives pod restarts and is shared across all service instances.
 */
@Document(collection = "class_details")
@CompoundIndexes({
    @CompoundIndex(name = "project_iri", def = "{'projectId': 1, 'classIri': 1}", unique = true)
})
public class ClassDetailDocument {

    @Id
    private String id; // projectId + "::" + classIri

    private String projectId;
    private String classIri;
    private long builtAt;

    /**
     * Full classDetails response map — includes annotations, subClassOf, equivalentClasses, etc.
     * When {@code partial=true} this only contains { id, label, annotations }.
     */
    private Map<String, Object> details;

    /**
     * True when this document holds only annotation data (fast-path pre-warm).
     * A partial document satisfies classAnnotations but NOT classDetails requests.
     * Overwritten to false when a full classDetails SPARQL result is stored.
     */
    private boolean partial;

    public ClassDetailDocument() {}

    public ClassDetailDocument(String projectId, String classIri, Map<String, Object> details, boolean partial) {
        this.id = projectId + "::" + classIri;
        this.projectId = projectId;
        this.classIri = classIri;
        this.details = details;
        this.partial = partial;
        this.builtAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getClassIri() { return classIri; }
    public long getBuiltAt() { return builtAt; }
    public Map<String, Object> getDetails() { return details; }
    public boolean isPartial() { return partial; }
}
