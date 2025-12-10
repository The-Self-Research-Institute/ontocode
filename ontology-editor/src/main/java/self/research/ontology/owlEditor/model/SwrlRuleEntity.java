package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document("swrl_rules")
public class SwrlRuleEntity {
    
    @Id
    private String id;
    private String projectId;
    private String ruleName;
    private String ruleText;
    private String category;
    private String comment;
    private boolean enabled = true;
    private Date createdAt = new Date();
    private Date updatedAt = new Date();

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    
    public String getRuleText() { return ruleText; }
    public void setRuleText(String ruleText) { this.ruleText = ruleText; }
    
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    
    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}