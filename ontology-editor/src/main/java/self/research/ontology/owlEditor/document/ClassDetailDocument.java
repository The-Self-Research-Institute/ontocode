package self.research.ontology.owlEditor.document;

import java.util.Map;

public class ClassDetailDocument {

    private String projectId;
    private String classIri;
    private long builtAt;
    private Map<String, Object> details;
    private boolean partial;

    public ClassDetailDocument() {}

    public ClassDetailDocument(String projectId, String classIri, Map<String, Object> details, boolean partial) {
        this(projectId, classIri, details, partial, System.currentTimeMillis());
    }

    public ClassDetailDocument(String projectId, String classIri, Map<String, Object> details, boolean partial,
                               long builtAt) {
        this.projectId = projectId;
        this.classIri = classIri;
        this.details = details;
        this.partial = partial;
        this.builtAt = builtAt;
    }

    public String getId() { return projectId + "::" + classIri; }
    public String getProjectId() { return projectId; }
    public String getClassIri() { return classIri; }
    public long getBuiltAt() { return builtAt; }
    public Map<String, Object> getDetails() { return details; }
    public boolean isPartial() { return partial; }
}
