package self.research.ontology.swrl.service;

final class OntologyBudget {

    static final long DEFAULT_BYTES_PER_AXIOM = 6000;
    static final long DEFAULT_TEXT_BYTES_PER_AXIOM = 1000;
    static final double DEFAULT_HEAP_FRACTION = 0.5;
    private static final long FLOOR_AXIOMS = 1000;

    private OntologyBudget() {
    }

    static long maxAxioms(long configured, long maxMemory, double heapFraction, int workers, long bytesPerAxiom) {
        if (configured > 0) {
            return configured;
        }
        double fraction = heapFraction > 0 && heapFraction <= 1 ? heapFraction : DEFAULT_HEAP_FRACTION;
        long perAxiom = Math.max(1, bytesPerAxiom);
        long share = (long) (maxMemory * fraction / Math.max(1, workers));
        return Math.max(FLOOR_AXIOMS, share / perAxiom);
    }

    static long maxTextBytes(long maxAxioms, long textBytesPerAxiom) {
        long perAxiom = Math.max(1, textBytesPerAxiom);
        return maxAxioms > Long.MAX_VALUE / perAxiom ? Long.MAX_VALUE : maxAxioms * perAxiom;
    }
}
