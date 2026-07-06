package self.research.ontology.owlEditor.service;

import java.util.List;

/**
 * Thread-local carrier for structured {@link OntologyMutationService.MutationOp}s
 * while {@link SparqlDatasetService#execUpdate} runs, so the OWLAPI patcher can
 * apply in-memory updates instead of evicting the whole parsed model.
 */
public final class MutationContext {

    private static final ThreadLocal<List<OntologyMutationService.MutationOp>> OPS = new ThreadLocal<>();

    private MutationContext() {}

    public static void setOps(List<OntologyMutationService.MutationOp> ops) {
        OPS.set(ops);
    }

    public static List<OntologyMutationService.MutationOp> getAndClear() {
        List<OntologyMutationService.MutationOp> ops = OPS.get();
        OPS.remove();
        return ops;
    }

    /** Non-destructive check: true while a structured mutation is in flight on this thread. */
    public static boolean hasStructuredOps() {
        return OPS.get() != null;
    }
}
