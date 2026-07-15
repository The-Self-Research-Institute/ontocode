package self.research.ontology.owlEditor.service;

/**
 * Thrown when a draft mutation is attempted before the copy-on-switch draft graph
 * has finished copying. The GlobalExceptionHandler maps this to HTTP 409 so that
 * every controller (DLQuery, rename, axiom paths, etc.) automatically returns the
 * right status without needing per-controller catch blocks.
 */
public class DraftNotReadyException extends RuntimeException {
    public DraftNotReadyException() {
        super("Private draft is not ready yet. Wait for the graph copy to finish before editing.");
    }
}
