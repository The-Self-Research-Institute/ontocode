package self.research.ontology.common.db;

import java.util.regex.Pattern;

public record ExpiringTable(String table, String column) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    public ExpiringTable {
        if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches()) {
            throw new IllegalArgumentException("Invalid table or column name: " + table + "." + column);
        }
    }

    public static ExpiringTable of(String table) {
        return new ExpiringTable(table, "expires_at");
    }
}
