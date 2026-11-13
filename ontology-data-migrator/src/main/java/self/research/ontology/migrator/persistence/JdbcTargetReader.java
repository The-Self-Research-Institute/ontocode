package self.research.ontology.migrator.persistence;

import org.postgresql.util.PGobject;
import self.research.ontology.migrator.core.TargetReader;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class JdbcTargetReader implements TargetReader {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private final DataSource dataSource;

    public JdbcTargetReader(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Map<String, Object>> fetch(String table, List<String> keyColumns, List<Object> keyValues) {
        StringBuilder sql = new StringBuilder("select * from ").append(ident(table)).append(" where ");
        for (int i = 0; i < keyColumns.size(); i++) {
            sql.append(i == 0 ? "" : " and ").append(ident(keyColumns.get(i))).append(" = ?");
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < keyValues.size(); i++) {
                ps.setObject(i + 1, keyValues.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ResultSetMetaData meta = rs.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnName(i), read(rs, meta, i));
                }
                return Optional.of(row);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read " + table + ": " + e.getMessage(), e);
        }
    }

    private static Object read(ResultSet rs, ResultSetMetaData meta, int i) throws SQLException {
        String type = meta.getColumnTypeName(i);
        if ("timestamptz".equals(type)) {
            return rs.getObject(i, OffsetDateTime.class);
        }
        Object value = rs.getObject(i);
        if (value instanceof Array array) {
            return Arrays.asList((Object[]) array.getArray());
        }
        if (value instanceof PGobject pg) {
            return pg.getValue();
        }
        return value;
    }

    private static String ident(String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
        return name;
    }
}
