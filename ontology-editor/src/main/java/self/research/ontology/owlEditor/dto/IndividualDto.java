package self.research.ontology.owlEditor.dto;

import java.util.List;
import java.util.Map;

public class IndividualDto {

    private String id;
    private String iri;
    private String label;
    private String description;
    private Map<String, String> annotations;
    private List<String> types;
    private List<String> sameAs;
    private List<String> differentFrom;

    public IndividualDto() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIri() { return iri; }
    public void setIri(String iri) { this.iri = iri; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, String> getAnnotations() { return annotations; }
    public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }

    public List<String> getTypes() { return types; }
    public void setTypes(List<String> types) { this.types = types; }

    public List<String> getSameAs() { return sameAs; }
    public void setSameAs(List<String> sameAs) { this.sameAs = sameAs; }

    public List<String> getDifferentFrom() { return differentFrom; }
    public void setDifferentFrom(List<String> differentFrom) { this.differentFrom = differentFrom; }
}