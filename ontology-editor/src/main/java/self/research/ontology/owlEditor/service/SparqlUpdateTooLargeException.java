package self.research.ontology.owlEditor.service;

public class SparqlUpdateTooLargeException extends SparqlQueryRejectedException {

    public SparqlUpdateTooLargeException(String message) {
        super(message);
    }
}
