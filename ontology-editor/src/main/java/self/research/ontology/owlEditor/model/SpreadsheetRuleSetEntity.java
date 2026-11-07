package self.research.ontology.owlEditor.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import self.research.ontology.owlEditor.service.spreadsheet.rule.RuleSpec;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Document("spreadsheet_rule_sets")
@CompoundIndex(name = "project_name", def = "{'projectId': 1, 'name': 1}", unique = true)
public class SpreadsheetRuleSetEntity {

    @Id
    private String id;
    private String projectId;
    private String name;
    private String description;
    private List<RuleSpec> rules = new ArrayList<>();
    private String createdBy;
    private String createdByUserId;
    private String updatedBy;
    private Date createdAt = new Date();
    private Date updatedAt = new Date();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<RuleSpec> getRules() { return rules; }
    public void setRules(List<RuleSpec> rules) { this.rules = rules; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

    public Date getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
