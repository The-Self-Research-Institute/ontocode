package self.research.ontology.swrl.model;

import java.util.List;

public class ValidationResult {
    private boolean valid;
    private String errorMessage;
    private List<String> suggestions;

    public ValidationResult() {}

    public ValidationResult(boolean valid, String errorMessage, List<String> suggestions) {
        this.valid = valid;
        this.errorMessage = errorMessage;
        this.suggestions = suggestions;
    }

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public List<String> getSuggestions() { return suggestions; }
    public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
}