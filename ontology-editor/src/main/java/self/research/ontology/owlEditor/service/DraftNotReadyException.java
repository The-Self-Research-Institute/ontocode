package self.research.ontology.owlEditor.service;

public class DraftNotReadyException extends RuntimeException {
    public DraftNotReadyException() {
        super("Private draft is not ready yet. Wait for the graph copy to finish before editing.");
    }
}
