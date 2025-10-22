package self.research.ontology.swrl.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class CreateRuleRequest {
    @NotBlank(message = "Rule name is required")
    private String ruleName;
    
    @NotBlank(message = "Rule text is required")
    private String ruleText;
    
    private String comment;
    private String category;
}