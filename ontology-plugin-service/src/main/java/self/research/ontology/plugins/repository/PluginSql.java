package self.research.ontology.plugins.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

final class PluginSql {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private PluginSql() {
    }

    static int update(JdbcTemplate jdbc, ObjectMapper mapper, String sql, Object... args) {
        return jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    Object arg = args[i];
                    if (arg instanceof List<?> list) {
                        ps.setArray(i + 1, con.createArrayOf("text", list.toArray()));
                    } else if (arg instanceof Map<?, ?> map) {
                        PGobject json = new PGobject();
                        json.setType("jsonb");
                        try {
                            json.setValue(mapper.writeValueAsString(map));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        ps.setObject(i + 1, json);
                    } else {
                        ps.setObject(i + 1, arg);
                    }
                }
                return ps.executeUpdate();
            }
        });
    }

    static List<String> readList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return null;
        }
        return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }

    static Map<String, String> readMap(ObjectMapper mapper, ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, STRING_MAP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Long readLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer readInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Double readDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    static Boolean readBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    static String orderBy(Sort sort, Map<String, String> columns, String fallback) {
        if (sort == null || sort.isUnsorted()) {
            return fallback;
        }
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = columns.get(order.getProperty());
            if (column == null) {
                throw new IllegalArgumentException("Cannot sort by " + order.getProperty());
            }
            parts.add(column + (order.isAscending() ? " asc nulls first" : " desc nulls last"));
        }
        parts.add("id asc");
        return String.join(", ", parts);
    }

    static <T> Page<T> page(List<T> rows, Pageable pageable, long total) {
        return new PageImpl<>(rows, pageable, total);
    }

    static String likePattern(String term) {
        String escaped = (term == null ? "" : term).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
