package self.research.ontology.swrl.dto;

import lombok.Data;

@Data
public class UpdateRuleRequest {
    private String ruleText;
    private String comment;
    private Boolean enabled;
    private String category;
}