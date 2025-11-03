package self.research.ontology.swrl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateRuleRequest {
    
    @NotBlank(message = "Rule name is required")
    @Size(min = 1, max = 100, message = "Rule name must be between 1 and 100 characters")
    private String ruleName;
    
    @NotBlank(message = "Rule text is required")
    @Size(min = 1, max = 5000, message = "Rule text must be between 1 and 5000 characters")
    private String ruleText;
    
    @Size(max = 500, message = "Comment must not exceed 500 characters")
    private String comment;
    
    @Size(max = 50, message = "Category must not exceed 50 characters")
    private String category;

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getRuleText() { return ruleText; }
    public void setRuleText(String ruleText) { this.ruleText = ruleText; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}