package self.research.ontology.swrl.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for SQWRL query execution
 */
public class SqwrlQueryRequest {

    @NotBlank(message = "Query text is required")
    private String queryText;
    
    private String queryName;
    
    private Integer maxResults;

    public SqwrlQueryRequest() {
    }

    public SqwrlQueryRequest(String queryText) {
        this.queryText = queryText;
    }

    public String getQueryText() {
        return queryText;
    }

    public void setQueryText(String queryText) {
        this.queryText = queryText;
    }

    public String getQueryName() {
        return queryName;
    }

    public void setQueryName(String queryName) {
        this.queryName = queryName;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }
}
