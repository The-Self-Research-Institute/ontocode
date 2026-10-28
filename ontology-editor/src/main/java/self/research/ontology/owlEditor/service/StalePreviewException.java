package self.research.ontology.owlEditor.service;

import java.util.List;

public class StalePreviewException extends RuntimeException {

    private final List<String> reasons;

    public StalePreviewException(List<String> reasons) {
        super("The preview is out of date: " + String.join("; ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> reasons() {
        return reasons;
    }
}
