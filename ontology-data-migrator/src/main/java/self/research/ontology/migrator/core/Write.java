package self.research.ontology.migrator.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface Write {

    record Json(String text) {
    }

    record Upsert(String table, List<String> keys, Map<String, Object> values, boolean doNothing) implements Write {

        public List<Object> keyValues() {
            List<Object> out = new ArrayList<>();
            for (String key : keys) {
                out.add(values.get(key));
            }
            return out;
        }
    }

    record DeleteChildren(String table, String column, String value) implements Write {
    }

    static Builder upsert(String table, String... keys) {
        return new Builder(table, List.of(keys));
    }

    static Write deleteChildren(String table, String column, String value) {
        return new DeleteChildren(table, column, value);
    }

    final class Builder {
        private final String table;
        private final List<String> keys;
        private final Map<String, Object> values = new LinkedHashMap<>();
        private boolean doNothing;

        private Builder(String table, List<String> keys) {
            this.table = table;
            this.keys = keys;
        }

        public Builder put(String column, Object value) {
            values.put(column, value);
            return this;
        }

        public Builder putIfPresent(String column, Object value) {
            if (value != null) {
                values.put(column, value);
            }
            return this;
        }

        public Builder doNothingOnConflict() {
            this.doNothing = true;
            return this;
        }

        public Upsert build() {
            for (String key : keys) {
                if (values.get(key) == null) {
                    throw new MappingException("Missing value for key column " + table + "." + key);
                }
            }
            return new Upsert(table, keys, values, doNothing);
        }
    }
}
