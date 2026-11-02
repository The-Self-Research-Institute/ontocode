package self.research.ontology.owlEditor.service;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import self.research.ontology.common.admission.Estimates;

public record SparqlResultLimits(int maxRows, long maxBytes) {

    public static final int DEFAULT_MAX_ROWS = 1_000_000;
    public static final long BYTES_PER_ROW_OVERHEAD = 64L;
    public static final long BYTES_PER_VALUE_OVERHEAD = 48L;
    public static final long BYTES_PER_CHARACTER = 2L;
    public static final long BYTES_PER_STATEMENT_OVERHEAD = 96L;

    public SparqlResultLimits {
        if (maxRows < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("SPARQL result limits must be positive");
        }
    }

    public static SparqlResultLimits defaults() {
        return new SparqlResultLimits(DEFAULT_MAX_ROWS, Estimates.DEFAULT_SPARQL_ROWS_BYTES_CAP);
    }

    public Counter counter() {
        return new Counter(this);
    }

    public static final class Counter {
        private final SparqlResultLimits limits;
        private long rows;
        private long bytes;

        private Counter(SparqlResultLimits limits) {
            this.limits = limits;
        }

        public boolean tryAdd(BindingSet row) {
            long size = BYTES_PER_ROW_OVERHEAD;
            for (Binding binding : row) {
                size += valueBytes(binding.getValue());
            }
            return admit(size);
        }

        public boolean tryAdd(Statement statement) {
            long size = BYTES_PER_STATEMENT_OVERHEAD + valueBytes(statement.getSubject())
                    + valueBytes(statement.getPredicate()) + valueBytes(statement.getObject());
            return admit(size);
        }

        private boolean admit(long size) {
            if (rows + 1 > limits.maxRows() || bytes + size > limits.maxBytes()) {
                return false;
            }
            rows++;
            bytes += size;
            return true;
        }

        public long rows() {
            return rows;
        }

        public long bytes() {
            return bytes;
        }

        private static long valueBytes(Value value) {
            if (value == null) {
                return 0;
            }
            return BYTES_PER_VALUE_OVERHEAD + value.stringValue().length() * BYTES_PER_CHARACTER;
        }
    }
}
