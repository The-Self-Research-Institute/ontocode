package self.research.ontology.owlEditor.dto;

import java.util.List;
import java.util.Map;

public class AnnotationPropertyDto {
    private String id;
    private String iri;
    private String label;
    private String description;
    private Map<String, List<String>> annotations;
    private List<String> superProperties;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIri() {
        return iri;
    }

    public void setIri(String iri) {
        this.iri = iri;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, List<String>> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(Map<String, List<String>> annotations) {
        this.annotations = annotations;
    }

    public List<String> getSuperProperties() {
        return superProperties;
    }

    public void setSuperProperties(List<String> superProperties) {
        this.superProperties = superProperties;
    }
}