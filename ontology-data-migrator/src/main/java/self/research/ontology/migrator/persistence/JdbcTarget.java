package self.research.ontology.migrator.persistence;

import org.postgresql.util.PGobject;
import self.research.ontology.migrator.core.Checkpoint;
import self.research.ontology.migrator.core.Target;
import self.research.ontology.migrator.core.Write;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class JdbcTarget implements Target {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private static final String SAVE_CHECKPOINT = "insert into migration_checkpoint "
            + "(collection, last_id, rows_read, rows_written, rows_skipped, status, updated_at) values (?, ?, ?, ?, ?, ?, now()) "
            + "on conflict (collection) do update set last_id = excluded.last_id, rows_read = excluded.rows_read, "
            + "rows_written = excluded.rows_written, rows_skipped = excluded.rows_skipped, status = excluded.status, "
            + "updated_at = excluded.updated_at";

    private final DataSource dataSource;

    public JdbcTarget(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Checkpoint> checkpoint(String name) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select last_id, rows_read, rows_written, rows_skipped, status "
                     + "from migration_checkpoint where collection = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Checkpoint(name, rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the checkpoint of " + name + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void commit(List<Write> writes, Checkpoint checkpoint) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                apply(c, writes);
                if (checkpoint != null) {
                    saveCheckpoint(c, checkpoint);
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void saveCheckpoint(Connection c, Checkpoint cp) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SAVE_CHECKPOINT)) {
            ps.setString(1, cp.name());
            ps.setString(2, cp.lastId());
            ps.setLong(3, cp.read());
            ps.setLong(4, cp.written());
            ps.setLong(5, cp.skipped());
            ps.setString(6, cp.status());
            ps.executeUpdate();
        }
    }

    private void apply(Connection c, List<Write> writes) throws SQLException {
        Map<String, List<Write.DeleteChildren>> deletes = new LinkedHashMap<>();
        Map<String, List<Write.Upsert>> upserts = new LinkedHashMap<>();
        for (Write w : writes) {
            if (w instanceof Write.DeleteChildren d) {
                deletes.computeIfAbsent(deleteSql(d), k -> new ArrayList<>()).add(d);
            } else if (w instanceof Write.Upsert u) {
                upserts.computeIfAbsent(upsertSql(u), k -> new ArrayList<>()).add(u);
            }
        }
        for (Map.Entry<String, List<Write.DeleteChildren>> e : deletes.entrySet()) {
            try (PreparedStatement ps = c.prepareStatement(e.getKey())) {
                for (Write.DeleteChildren d : e.getValue()) {
                    ps.setString(1, d.value());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        for (Map.Entry<String, List<Write.Upsert>> e : upserts.entrySet()) {
            try (PreparedStatement ps = c.prepareStatement(e.getKey())) {
                for (Write.Upsert u : e.getValue()) {
                    int i = 1;
                    for (Object value : u.values().values()) {
                        bind(c, ps, i++, value);
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static void bind(Connection c, PreparedStatement ps, int index, Object value) throws SQLException {
        if (value instanceof Write.Json json) {
            PGobject o = new PGobject();
            o.setType("jsonb");
            o.setValue(json.text());
            ps.setObject(index, o);
        } else if (value instanceof Collection<?> list) {
            ps.setArray(index, c.createArrayOf("text", list.toArray()));
        } else if (value == null) {
            ps.setObject(index, null);
        } else {
            ps.setObject(index, value);
        }
    }

    static String deleteSql(Write.DeleteChildren d) {
        return "delete from " + ident(d.table()) + " where " + ident(d.column()) + " = ?";
    }

    static String upsertSql(Write.Upsert u) {
        List<String> columns = new ArrayList<>(u.values().keySet());
        columns.forEach(JdbcTarget::ident);
        u.keys().forEach(JdbcTarget::ident);
        StringBuilder sql = new StringBuilder("insert into ").append(ident(u.table()))
                .append(" (").append(String.join(", ", columns)).append(") values (")
                .append(String.join(", ", java.util.Collections.nCopies(columns.size(), "?"))).append(") on conflict (")
                .append(String.join(", ", u.keys())).append(")");
        List<String> updates = new ArrayList<>();
        for (String column : columns) {
            if (!u.keys().contains(column)) {
                updates.add(column + " = excluded." + column);
            }
        }
        if (u.doNothing() || updates.isEmpty()) {
            sql.append(" do nothing");
        } else {
            sql.append(" do update set ").append(String.join(", ", updates));
        }
        return sql.toString();
    }

    private static String ident(String name) {
        if (!IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
        return name;
    }
}
