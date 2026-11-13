package self.research.ontology.common.db.sql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class SqlUpsert {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private final String sql;
    private final int columnCount;

    private SqlUpsert(String sql, int columnCount) {
        this.sql = sql;
        this.columnCount = columnCount;
    }

    public static Builder into(String table) {
        return new Builder(table);
    }

    public String sql() {
        return sql;
    }

    public int columnCount() {
        return columnCount;
    }

    public static final class Builder {
        private final String table;
        private List<String> keys = List.of();
        private List<String> columns = List.of();

        private Builder(String table) {
            this.table = identifier(table);
        }

        public Builder key(String... keyColumns) {
            this.keys = Arrays.stream(keyColumns).map(SqlUpsert::identifier).toList();
            return this;
        }

        public Builder columns(String... allColumns) {
            this.columns = Arrays.stream(allColumns).map(SqlUpsert::identifier).toList();
            return this;
        }

        public SqlUpsert build() {
            if (keys.isEmpty() || columns.isEmpty() || !columns.containsAll(keys)) {
                throw new IllegalStateException("An upsert needs columns and a key that is one of them");
            }
            List<String> updates = new ArrayList<>();
            for (String column : columns) {
                if (!keys.contains(column)) {
                    updates.add(column + " = excluded." + column);
                }
            }
            String marks = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
            StringBuilder sql = new StringBuilder("insert into ").append(table)
                    .append(" (").append(String.join(", ", columns)).append(") values (").append(marks).append(")")
                    .append(" on conflict (").append(String.join(", ", keys)).append(")");
            if (updates.isEmpty()) {
                sql.append(" do nothing");
            } else {
                sql.append(" do update set ").append(String.join(", ", updates));
            }
            return new SqlUpsert(sql.toString(), columns.size());
        }
    }

    private static String identifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
        return name;
    }
}
