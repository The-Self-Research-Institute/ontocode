package self.research.ontology.owlEditor.service;

public record SparqlUpdateLimits(long maxBytes, long maxQuads, int maxOperations) {

    public static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;
    public static final long DEFAULT_MAX_QUADS = 200_000L;
    public static final int DEFAULT_MAX_OPERATIONS = 100;
    public static final long PARSE_HEAP_FACTOR = 30L;

    public SparqlUpdateLimits {
        maxBytes = maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
        maxQuads = maxQuads > 0 ? maxQuads : DEFAULT_MAX_QUADS;
        maxOperations = maxOperations > 0 ? maxOperations : DEFAULT_MAX_OPERATIONS;
    }

    public static SparqlUpdateLimits defaults() {
        return new SparqlUpdateLimits(0, 0, 0);
    }
}
