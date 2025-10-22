package self.research.ontology.owlEditor.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.Map;

@Document(collection = "ontology_search_index")
public class SearchIndexDocument {

    @Id
    private String id;
    private String projectId;
    private Date createdAt;
    private Map<String, Object> searchData;

    public SearchIndexDocument() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public Map<String, Object> getSearchData() { return searchData; }
    public void setSearchData(Map<String, Object> searchData) { this.searchData = searchData; }
}