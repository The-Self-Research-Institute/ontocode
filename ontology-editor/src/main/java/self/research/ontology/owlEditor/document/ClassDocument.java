package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Document(collection = "ontology_classes")
public class ClassDocument {

    @Id
    private String id;
    private String projectId;
    private String iri;
    private String localName;
    private Map<String, String> annotations;
    private List<String> superClasses;
    private List<String> subClasses;
    private List<String> equivalentClasses;
    private List<String> disjointClasses;
    private List<String> instances;
    private Date createdAt;
    private String searchText;

    public ClassDocument() {}

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

    public List<String> getSuperClasses() { return superClasses; }
    public void setSuperClasses(List<String> superClasses) { this.superClasses = superClasses; }

    public List<String> getSubClasses() { return subClasses; }
    public void setSubClasses(List<String> subClasses) { this.subClasses = subClasses; }

    public List<String> getEquivalentClasses() { return equivalentClasses; }
    public void setEquivalentClasses(List<String> equivalentClasses) { this.equivalentClasses = equivalentClasses; }

    public List<String> getDisjointClasses() { return disjointClasses; }
    public void setDisjointClasses(List<String> disjointClasses) { this.disjointClasses = disjointClasses; }

    public List<String> getInstances() { return instances; }
    public void setInstances(List<String> instances) { this.instances = instances; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public String getSearchText() { return searchText; }
    public void setSearchText(String searchText) { this.searchText = searchText; }
}