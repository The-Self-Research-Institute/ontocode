package self.research.ontology.auth.repository;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;

final class AuthSql {

    private AuthSql() {
    }

    static String idOrNew(String id) {
        return id == null || id.isBlank() ? Ids.newObjectId() : id;
    }

    static Object db(LocalDateTime value) {
        return Instants.toDb(value);
    }

    static int update(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                bind(con, ps, args);
                return ps.executeUpdate();
            }
        });
    }

    static void bind(java.sql.Connection con, PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Collection<?> list) {
                ps.setArray(i + 1, con.createArrayOf("text", list.toArray()));
            } else {
                ps.setObject(i + 1, arg);
            }
        }
    }

    static List<String> textList(Collection<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    static List<String> readList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return null;
        }
        return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }

    static List<String> readListOrEmpty(ResultSet rs, String column) throws SQLException {
        List<String> list = readList(rs, column);
        return list == null ? new ArrayList<>() : list;
    }

    static Set<String> readSet(ResultSet rs, String column) throws SQLException {
        return readListOrEmpty(rs, column).stream().collect(Collectors.toCollection(java.util.HashSet::new));
    }

    static Long readLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer readInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Boolean readBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    static <T> java.util.Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }
}
