package self.research.ontology.swrl.dto;

public class UpdateRuleRequest {
    private String ruleText;
    private String comment;
    private Boolean enabled;
    private String category;

    public String getRuleText() { return ruleText; }
    public void setRuleText(String ruleText) { this.ruleText = ruleText; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}