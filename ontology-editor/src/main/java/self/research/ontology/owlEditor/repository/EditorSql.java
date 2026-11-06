package self.research.ontology.owlEditor.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import self.research.ontology.common.db.sql.Instants;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class EditorSql {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private EditorSql() {
    }

    static int update(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                bind(con, ps, args);
                return ps.executeUpdate();
            }
        });
    }

    static <T> List<T> query(JdbcTemplate jdbc, String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.execute((ConnectionCallback<List<T>>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                bind(con, ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> rows = new ArrayList<>();
                    int i = 0;
                    while (rs.next()) {
                        rows.add(mapper.mapRow(rs, i++));
                    }
                    return rows;
                }
            }
        });
    }

    static int batch(JdbcTemplate jdbc, String sql, List<Object[]> rows) {
        return jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                for (Object[] row : rows) {
                    bind(con, ps, row);
                    ps.addBatch();
                }
                int changed = 0;
                for (int count : ps.executeBatch()) {
                    changed += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(count, 0);
                }
                return changed;
            }
        });
    }

    private static void bind(java.sql.Connection con, PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Collection<?> list) {
                ps.setArray(i + 1, con.createArrayOf("text", list.toArray()));
            } else if (arg instanceof Map<?, ?> map) {
                ps.setObject(i + 1, json(map));
            } else {
                ps.setObject(i + 1, arg);
            }
        }
    }

    static PGobject json(Object value) {
        try {
            PGobject json = new PGobject();
            json.setType("jsonb");
            json.setValue(value == null ? null : MAPPER.writeValueAsString(value));
            return json;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Could not write JSON", e);
        }
    }

    static Map<String, Object> readMap(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, OBJECT_MAP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Map<String, String> readStringMap(ResultSet rs, String column) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, STRING_MAP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> T readJson(ResultSet rs, String column, TypeReference<T> type) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static OffsetDateTime toDb(Date date) {
        return date == null ? null : Instants.toDb(date.toInstant());
    }

    static Date readDate(ResultSet rs, String column) throws SQLException {
        Instant instant = Instants.read(rs, column);
        return instant == null ? null : Date.from(instant);
    }

    static Integer readInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static List<String> readList(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return null;
        }
        return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }

    static Long readLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
