package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Document(collection = "ontology_individuals")
public class IndividualDocument {

    @Id
    private String id;
    private String projectId;
    private String iri;
    private String localName;
    private Map<String, String> annotations;
    private List<String> types;
    private List<String> sameAs;
    private List<String> differentFrom;
    private Date createdAt;
    private String searchText;

    public IndividualDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getIri() { return iri; }
    public void setIri(String iri) { this.iri = iri; }

    public String getLocalName() { return localName; }
    public void setLocalName(String localName) { this.localName = localName; }

    public Map<String, String> getAnnotations() { return annotations; }
    public void setAnnotations(Map<String, String> annotations) { this.annotations = annotations; }

    public List<String> getTypes() { return types; }
    public void setTypes(List<String> types) { this.types = types; }

    public List<String> getSameAs() { return sameAs; }
    public void setSameAs(List<String> sameAs) { this.sameAs = sameAs; }

    public List<String> getDifferentFrom() { return differentFrom; }
    public void setDifferentFrom(List<String> differentFrom) { this.differentFrom = differentFrom; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public String getSearchText() { return searchText; }
    public void setSearchText(String searchText) { this.searchText = searchText; }
}