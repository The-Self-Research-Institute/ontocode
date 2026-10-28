package self.research.ontology.owlEditor.service.infer;

public class InconsistentOntologyException extends RuntimeException {

    private final String explanation;

    public InconsistentOntologyException(String message, String explanation) {
        super(message);
        this.explanation = explanation;
    }

    public String explanation() {
        return explanation;
    }
}
