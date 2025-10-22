package self.research.ontology.owlEditor.dto;

import java.util.List;
import java.util.Map;

public class PropertyDto {
    private String id;
    private String iri;
    private String label;
    private String type;
    private Map<String, String> annotations;
    private List<String> domains;
    private List<String> ranges;
    private List<String> characteristics;
    private List<String> superProperties;
    private List<String> subProperties;
    private List<PropertyDto> children;  // NEW - for tree structure

    // Getters and Setters
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(Map<String, String> annotations) {
        this.annotations = annotations;
    }

    public List<String> getDomains() {
        return domains;
    }

    public void setDomains(List<String> domains) {
        this.domains = domains;
    }

    public List<String> getRanges() {
        return ranges;
    }

    public void setRanges(List<String> ranges) {
        this.ranges = ranges;
    }

    public List<String> getCharacteristics() {
        return characteristics;
    }

    public void setCharacteristics(List<String> characteristics) {
        this.characteristics = characteristics;
    }

    public List<String> getSuperProperties() {
        return superProperties;
    }

    public void setSuperProperties(List<String> superProperties) {
        this.superProperties = superProperties;
    }

    public List<String> getSubProperties() {
        return subProperties;
    }

    public void setSubProperties(List<String> subProperties) {
        this.subProperties = subProperties;
    }

    public List<PropertyDto> getChildren() {
        return children;
    }

    public void setChildren(List<PropertyDto> children) {
        this.children = children;
    }
}