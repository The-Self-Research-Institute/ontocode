package self.research.ontology.common.admission;

public final class Estimates {

    public static final long DEFAULT_ESTIMATED_BYTES_PER_TRIPLE_LOAD = 1_000L;
    public static final long DEFAULT_ESTIMATED_BYTES_PER_TRIPLE_RULE_EXECUTE = 2_000L;
    public static final long DEFAULT_ESTIMATED_IMPORT_PARSE_MULTIPLIER = 3L;
    public static final long DEFAULT_ESTIMATED_SPARQL_ROW_BYTES = 512L;
    public static final long DEFAULT_SPARQL_ROWS_BYTES_CAP = 256L * 1024 * 1024;

    private final long estimatedBytesPerTripleLoad;
    private final long estimatedBytesPerTripleRuleExecute;
    private final long estimatedImportParseMultiplier;
    private final long estimatedSparqlRowBytes;
    private final long sparqlRowsBytesCap;

    public Estimates() {
        this(DEFAULT_ESTIMATED_BYTES_PER_TRIPLE_LOAD, DEFAULT_ESTIMATED_BYTES_PER_TRIPLE_RULE_EXECUTE,
                DEFAULT_ESTIMATED_IMPORT_PARSE_MULTIPLIER, DEFAULT_ESTIMATED_SPARQL_ROW_BYTES,
                DEFAULT_SPARQL_ROWS_BYTES_CAP);
    }

    public Estimates(long estimatedBytesPerTripleLoad, long estimatedBytesPerTripleRuleExecute,
            long estimatedImportParseMultiplier, long estimatedSparqlRowBytes, long sparqlRowsBytesCap) {
        this.estimatedBytesPerTripleLoad = estimatedBytesPerTripleLoad;
        this.estimatedBytesPerTripleRuleExecute = estimatedBytesPerTripleRuleExecute;
        this.estimatedImportParseMultiplier = estimatedImportParseMultiplier;
        this.estimatedSparqlRowBytes = estimatedSparqlRowBytes;
        this.sparqlRowsBytesCap = sparqlRowsBytesCap;
    }

    public Estimates withEstimatedBytesPerTripleLoad(long value) {
        return new Estimates(value, estimatedBytesPerTripleRuleExecute, estimatedImportParseMultiplier,
                estimatedSparqlRowBytes, sparqlRowsBytesCap);
    }

    public Estimates withEstimatedBytesPerTripleRuleExecute(long value) {
        return new Estimates(estimatedBytesPerTripleLoad, value, estimatedImportParseMultiplier,
                estimatedSparqlRowBytes, sparqlRowsBytesCap);
    }

    public Estimates withEstimatedImportParseMultiplier(long value) {
        return new Estimates(estimatedBytesPerTripleLoad, estimatedBytesPerTripleRuleExecute, value,
                estimatedSparqlRowBytes, sparqlRowsBytesCap);
    }

    public Estimates withEstimatedSparqlRowBytes(long value) {
        return new Estimates(estimatedBytesPerTripleLoad, estimatedBytesPerTripleRuleExecute,
                estimatedImportParseMultiplier, value, sparqlRowsBytesCap);
    }

    public Estimates withSparqlRowsBytesCap(long value) {
        return new Estimates(estimatedBytesPerTripleLoad, estimatedBytesPerTripleRuleExecute,
                estimatedImportParseMultiplier, estimatedSparqlRowBytes, value);
    }

    public long estimatedBytesPerTripleLoad() {
        return estimatedBytesPerTripleLoad;
    }

    public long estimatedBytesPerTripleRuleExecute() {
        return estimatedBytesPerTripleRuleExecute;
    }

    public long estimatedImportParseMultiplier() {
        return estimatedImportParseMultiplier;
    }

    public long estimatedSparqlRowBytes() {
        return estimatedSparqlRowBytes;
    }

    public long sparqlRowsBytesCap() {
        return sparqlRowsBytesCap;
    }

    public long loadAndReason(long triples) {
        return saturatingMultiply(triples, estimatedBytesPerTripleLoad);
    }

    public long ruleExecute(long triples) {
        return saturatingMultiply(triples, estimatedBytesPerTripleRuleExecute);
    }

    public long importParse(long fileBytes) {
        return saturatingMultiply(fileBytes, estimatedImportParseMultiplier);
    }

    public long sparqlRows(long rows) {
        return Math.min(sparqlRowsBytesCap, saturatingMultiply(rows, estimatedSparqlRowBytes));
    }

    static long saturatingMultiply(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }
}
