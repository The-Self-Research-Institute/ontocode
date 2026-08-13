package self.research.ontology.owlEditor.service;

import java.util.List;

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

    public static boolean hasStructuredOps() {
        return OPS.get() != null;
    }
}
