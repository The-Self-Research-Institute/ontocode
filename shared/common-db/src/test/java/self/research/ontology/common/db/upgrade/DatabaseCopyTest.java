package self.research.ontology.common.db.upgrade;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.PostgresTestSupport;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCopyTest {

    private final EmbeddedPostgres server = PostgresTestSupport.postgres();
    private final List<String> progress = new ArrayList<>();

    private DataSource freshDatabase() {
        String name = "copy_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(server.getPostgresDatabase()).execute("CREATE DATABASE " + name);
        return server.getDatabase("postgres", name);
    }

    private DataSource migratedWithData() {
        DataSource ds = freshDatabase();
        Flyway.configure().dataSource(ds).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO stored_file (id, bucket, filename, content_type, length, metadata) "
                + "VALUES ('f1', 'fs', 'pizza.owl', 'application/rdf+xml', 300000, '{\"projectId\": \"p1\", \"n\": [1, 2]}')");
        byte[] chunk = new byte[300_000];
        new Random(7).nextBytes(chunk);
        jdbc.update("INSERT INTO file_chunk (file_id, n, data) VALUES ('f1', 0, ?)", (Object) chunk);
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO draft_pr_change (id, pr_id, project_id, change_type, change_data, change_at) "
                    + "VALUES (?, 'pr1', 'p1', 'ADD', '{\"label\": \"Pizza é\"}', now())", "c" + i);
        }
        jdbc.update("DELETE FROM draft_pr_change WHERE id = 'c2'");
        return ds;
    }

    @Test
    void everyRowSequenceAndIdentityValueIsCopiedAndChecked() throws Exception {
        DataSource source = migratedWithData();
        DataSource target = freshDatabase();

        DatabaseCopy.Result result = DatabaseCopy.copy(source, target, progress::add);

        assertThat(result.schemaVersion()).isNotBlank();
        assertThat(result.tables()).isGreaterThan(10);
        JdbcTemplate to = new JdbcTemplate(target);
        assertThat(to.queryForList("SELECT seq FROM draft_pr_change ORDER BY seq", Long.class)).containsExactly(1L, 3L);
        assertThat(to.queryForObject("SELECT data FROM file_chunk WHERE file_id = 'f1'", byte[].class))
                .isEqualTo(new JdbcTemplate(source).queryForObject("SELECT data FROM file_chunk WHERE file_id = 'f1'", byte[].class));
        assertThat(to.queryForObject("SELECT metadata ->> 'projectId' FROM stored_file WHERE id = 'f1'", String.class)).isEqualTo("p1");
        to.update("INSERT INTO draft_pr_change (id, pr_id) VALUES ('c4', 'pr1')");
        assertThat(to.queryForObject("SELECT seq FROM draft_pr_change WHERE id = 'c4'", Long.class)).isEqualTo(4L);
        assertThat(to.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class))
                .isEqualTo(new JdbcTemplate(source).queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class));
        assertThat(progress).anyMatch(line -> line.startsWith("Copied"));
    }

    @Test
    void anOldDatabaseWithoutASchemaHasNothingToCopy() throws Exception {
        DataSource target = freshDatabase();

        DatabaseCopy.Result result = DatabaseCopy.copy(freshDatabase(), target, progress::add);

        assertThat(result.schemaVersion()).isNull();
        assertThat(new JdbcTemplate(target).queryForObject(
                "SELECT to_regclass('flyway_schema_history') IS NULL", Boolean.class)).isTrue();
    }

    @Test
    void aNewDatabaseThatAlreadyHoldsASchemaIsRefused() {
        DataSource source = migratedWithData();
        DataSource target = freshDatabase();
        Flyway.configure().dataSource(target).load().migrate();

        assertThatThrownBy(() -> DatabaseCopy.copy(source, target, progress::add))
                .isInstanceOf(DatabaseCopy.CopyException.class)
                .hasMessageContaining("not empty");
    }

    @Test
    void anOldDatabaseFromANewerAppVersionIsRefusedAndTheNewOneStaysEmpty() {
        DataSource source = migratedWithData();
        new JdbcTemplate(source).update("INSERT INTO flyway_schema_history (installed_rank, version, description, type, "
                + "script, checksum, installed_by, execution_time, success) VALUES (9999, '9999', 'future', 'SQL', "
                + "'V9999__future.sql', 0, 'test', 0, true)");
        DataSource target = freshDatabase();

        assertThatThrownBy(() -> DatabaseCopy.copy(source, target, progress::add))
                .isInstanceOf(DatabaseCopy.SchemaTooNewException.class);
        assertThat(new JdbcTemplate(target).queryForObject(
                "SELECT to_regclass('flyway_schema_history') IS NULL", Boolean.class)).isTrue();
    }

    @Test
    void theCheckNoticesACopyThatDiffersFromTheOriginal() throws Exception {
        DataSource source = migratedWithData();
        DataSource target = freshDatabase();
        DatabaseCopy.copy(source, target, progress::add);
        new JdbcTemplate(target).update("UPDATE stored_file SET filename = 'changed.owl' WHERE id = 'f1'");

        try (Connection from = source.getConnection(); Connection to = target.getConnection()) {
            assertThat(DatabaseCopy.verify(from, to)).singleElement().asString().contains("stored_file");
        }
    }

    @Test
    void theCommandNamesAMissingSettingWithoutPrintingThePassword() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int code = DatabaseUpgradeCommand.run(Map.of(DatabaseUpgradeCommand.PASSWORD, "secret-value-123"),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));

        assertThat(code).isEqualTo(DatabaseUpgradeCommand.FAILED);
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("is not set").doesNotContain("secret-value-123");
    }

    @Test
    void theCommandCopiesBetweenTwoDatabasesAndReportsOk() {
        String sourceName = "copy_" + UUID.randomUUID().toString().replace("-", "");
        String targetName = "copy_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(server.getPostgresDatabase());
        admin.execute("CREATE DATABASE " + sourceName);
        admin.execute("CREATE DATABASE " + targetName);
        Flyway.configure().dataSource(server.getDatabase("postgres", sourceName)).load().migrate();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int code = DatabaseUpgradeCommand.run(Map.of(
                DatabaseUpgradeCommand.SOURCE_URL, server.getJdbcUrl("postgres", sourceName),
                DatabaseUpgradeCommand.TARGET_URL, server.getJdbcUrl("postgres", targetName),
                DatabaseUpgradeCommand.USER, "postgres",
                DatabaseUpgradeCommand.PASSWORD, "postgres"), new PrintStream(buffer, true, StandardCharsets.UTF_8));

        assertThat(code).as(buffer.toString(StandardCharsets.UTF_8)).isEqualTo(DatabaseUpgradeCommand.OK);
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains("[upgrade] OK");
    }
}
