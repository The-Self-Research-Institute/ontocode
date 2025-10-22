package self.research.ontology.swrl.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "swrl_rules")
public class SwrlRule {
    @Id
    private String id;
    private String projectId;
    private String ruleName;
    private String ruleText;
    private boolean enabled;
    private String comment;
    private String category;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    
    public SwrlRule(String projectId, String ruleName, String ruleText) {
        this.projectId = projectId;
        this.ruleName = ruleName;
        this.ruleText = ruleText;
        this.enabled = true;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}