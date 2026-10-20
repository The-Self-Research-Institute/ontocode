package self.research.ontology.owlEditor.service;

public class DraftMergeTooLargeException extends IllegalStateException {
    public DraftMergeTooLargeException() {
        super("This merge is too large to apply inside a private draft. Publish or discard your draft, or merge in public mode.");
    }
}
