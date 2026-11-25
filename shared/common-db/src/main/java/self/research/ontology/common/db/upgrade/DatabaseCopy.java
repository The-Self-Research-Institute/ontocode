package self.research.ontology.common.db.upgrade;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class DatabaseCopy {

    public record Result(String schemaVersion, int tables, long rows, long millis) {
    }

    public static class CopyException extends IllegalStateException {
        public CopyException(String message) {
            super(message);
        }
    }

    public static final class SchemaTooNewException extends CopyException {
        public SchemaTooNewException(String message) {
            super(message);
        }
    }

    public static final class VerificationException extends CopyException {
        private final List<String> mismatches;

        public VerificationException(List<String> mismatches) {
            super("The copy does not match the original: " + String.join("; ", mismatches));
            this.mismatches = List.copyOf(mismatches);
        }

        public List<String> mismatches() {
            return mismatches;
        }
    }

    private static final String HISTORY = "flyway_schema_history";

    private DatabaseCopy() {
    }

    public static Result copy(DataSource source, DataSource target, Consumer<String> progress) throws SQLException {
        long started = System.currentTimeMillis();
        try (Connection from = source.getConnection(); Connection to = target.getConnection()) {
            if (!hasTable(from, HISTORY)) {
                progress.accept("The old database has no schema yet; nothing to copy");
                return new Result(null, 0, 0, System.currentTimeMillis() - started);
            }
            if (hasTable(to, HISTORY)) {
                throw new CopyException("The new database is not empty");
            }
            String version = schemaVersion(from);
            requireKnown(target, version);
            progress.accept("Preparing the new database at schema version " + version);
            Flyway.configure().dataSource(target).target(version).load().migrate();

            List<String> tables = tables(from);
            if (!tables.equals(tables(to))) {
                throw new CopyException("The two databases have different tables");
            }
            long rows = copyRows(from, to, tables, progress);
            progress.accept("Checking the copy");
            List<String> mismatches = verify(from, to);
            if (!mismatches.isEmpty()) {
                throw new VerificationException(mismatches);
            }
            try (Statement s = to.createStatement()) {
                s.execute("ANALYZE");
            }
            return new Result(version, tables.size(), rows, System.currentTimeMillis() - started);
        }
    }

    static List<String> verify(Connection from, Connection to) throws SQLException {
        List<String> mismatches = new ArrayList<>();
        for (String table : tables(from)) {
            String a = fingerprint(from, table);
            String b = fingerprint(to, table);
            if (!a.equals(b)) {
                mismatches.add(table + " (original " + a + ", copy " + b + ")");
            }
        }
        Map<String, String> sa = sequences(from);
        Map<String, String> sb = sequences(to);
        if (!sa.equals(sb)) {
            mismatches.add("sequences (original " + sa + ", copy " + sb + ")");
        }
        return mismatches;
    }

    private static long copyRows(Connection from, Connection to, List<String> tables, Consumer<String> progress)
            throws SQLException {
        boolean autoCommit = to.getAutoCommit();
        to.setAutoCommit(false);
        try {
            try (Statement s = to.createStatement()) {
                s.execute("SET LOCAL session_replication_role = replica");
                s.execute("TRUNCATE " + String.join(", ", tables));
            }
            CopyManager out = from.unwrap(PGConnection.class).getCopyAPI();
            CopyManager in = to.unwrap(PGConnection.class).getCopyAPI();
            long rows = 0;
            int done = 0;
            for (String table : tables) {
                List<String> columns = columns(from, table);
                if (!columns.equals(columns(to, table))) {
                    throw new CopyException("The columns of " + table + " differ between the two databases");
                }
                rows += stream(out, in, table, String.join(", ", columns));
                done++;
                if (done % 10 == 0 || done == tables.size()) {
                    progress.accept("Copied " + done + " of " + tables.size() + " tables");
                }
            }
            copySequences(from, to);
            to.commit();
            return rows;
        } catch (SQLException | RuntimeException e) {
            to.rollback();
            throw e;
        } finally {
            to.setAutoCommit(autoCommit);
        }
    }

    private static long stream(CopyManager out, CopyManager in, String table, String columns) throws SQLException {
        String spec = table + " (" + columns + ")";
        AtomicReference<Exception> failure = new AtomicReference<>();
        try (PipedInputStream pipeIn = new PipedInputStream(1 << 20)) {
            PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
            Thread reader = new Thread(() -> {
                try (pipeOut) {
                    out.copyOut("COPY " + spec + " TO STDOUT", pipeOut);
                } catch (Exception e) {
                    failure.set(e);
                }
            }, "db-copy-" + table);
            reader.start();
            long rows;
            try {
                rows = in.copyIn("COPY " + spec + " FROM STDIN", pipeIn);
            } finally {
                reader.join();
            }
            if (failure.get() != null) {
                throw new SQLException("Reading " + table + " failed", failure.get());
            }
            return rows;
        } catch (IOException e) {
            throw new SQLException("Copying " + table + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Copying " + table + " was interrupted", e);
        }
    }

    private static void copySequences(Connection from, Connection to) throws SQLException {
        try (Statement s = from.createStatement();
             ResultSet r = s.executeQuery("SELECT quote_ident(schemaname) || '.' || quote_ident(sequencename), last_value "
                     + "FROM pg_sequences WHERE last_value IS NOT NULL");
             Statement w = to.createStatement()) {
            while (r.next()) {
                w.execute("SELECT setval(" + literal(r.getString(1)) + ", " + r.getLong(2) + ", true)");
            }
        }
    }

    private static void requireKnown(DataSource target, String version) {
        MigrationVersion wanted = MigrationVersion.fromVersion(version);
        MigrationVersion newest = Arrays.stream(Flyway.configure().dataSource(target).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .max(MigrationVersion::compareTo)
                .orElse(MigrationVersion.EMPTY);
        if (wanted.compareTo(newest) > 0) {
            throw new SchemaTooNewException("The old database was made by a newer OntoCode version (schema "
                    + version + ", this version knows up to " + newest + ")");
        }
    }

    private static String schemaVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT version FROM " + HISTORY
                     + " WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
            if (!r.next()) {
                throw new CopyException("The old database has no applied schema version");
            }
            return r.getString(1);
        }
    }

    private static boolean hasTable(Connection c, String name) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT to_regclass(" + literal(name) + ") IS NOT NULL")) {
            return r.next() && r.getBoolean(1);
        }
    }

    private static List<String> tables(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT quote_ident(table_schema) || '.' || quote_ident(table_name) "
                     + "FROM information_schema.tables WHERE table_type = 'BASE TABLE' "
                     + "AND table_schema NOT IN ('pg_catalog', 'information_schema') ORDER BY 1")) {
            while (r.next()) {
                out.add(r.getString(1));
            }
        }
        return out;
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT quote_ident(attname) FROM pg_attribute WHERE attrelid = "
                     + literal(table) + "::regclass AND attnum > 0 AND NOT attisdropped AND attgenerated = '' "
                     + "ORDER BY attnum")) {
            while (r.next()) {
                out.add(r.getString(1));
            }
        }
        return out;
    }

    private static String fingerprint(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*), coalesce(md5(string_agg(md5(t::text), '' "
                     + "ORDER BY md5(t::text))), '-') FROM " + table + " t")) {
            r.next();
            return r.getLong(1) + " rows, " + r.getString(2);
        }
    }

    private static Map<String, String> sequences(Connection c) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT schemaname || '.' || sequencename, coalesce(last_value::text, '-') "
                     + "FROM pg_sequences ORDER BY 1")) {
            while (r.next()) {
                out.put(r.getString(1), r.getString(2));
            }
        }
        return out;
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
