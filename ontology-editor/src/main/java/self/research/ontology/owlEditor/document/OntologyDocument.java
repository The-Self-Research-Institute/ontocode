package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import self.research.ontology.owlEditor.service.OwlParsingService.OntologyMetadata;

import java.util.Date;
import java.util.Map;

@Document(collection = "ontologies")
public class OntologyDocument {
    @Id
    private String id;
    private String projectId;
    private OntologyMetadata metadata;  // ADD THIS
    private Map<String, Integer> statistics;
    private Date createdAt;
    private Date updatedAt;

    // Existing getters and setters...

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    // ADD THIS GETTER AND SETTER
    public OntologyMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(OntologyMetadata metadata) {
        this.metadata = metadata;
    }

    public Map<String, Integer> getStatistics() {
        return statistics;
    }

    public void setStatistics(Map<String, Integer> statistics) {
        this.statistics = statistics;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }
}