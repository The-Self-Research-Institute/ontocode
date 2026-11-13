package self.research.ontology.migrator;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.migrator.collections.Migrations;
import self.research.ontology.migrator.collections.UserMigration;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.DedupPlan;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.core.MigrationRunner;
import self.research.ontology.migrator.core.Verification;
import self.research.ontology.migrator.persistence.JdbcTarget;
import self.research.ontology.migrator.persistence.JdbcTargetReader;
import self.research.ontology.migrator.support.Docs;
import self.research.ontology.migrator.support.Fixtures;
import self.research.ontology.migrator.support.InMemorySource;
import self.research.ontology.migrator.support.TestDb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresMigrationTest {

    static Stream<Fixtures.Fixture> fixtures() {
        return Fixtures.all().stream();
    }

    static CollectionMigration migration(String name) {
        return Migrations.all().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static long count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private static String snapshot(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select coalesce(md5(string_agg(t::text, '|' order by t::text)), '') from " + table + " t", String.class);
    }

    @Test
    void everyMigrationHasAFixtureAndTheRegistryHasNoDuplicates() {
        List<String> registered = Migrations.all().stream().map(CollectionMigration::name).toList();
        assertEquals(registered.size(), registered.stream().distinct().count());
        assertEquals(registered.stream().sorted().toList(), Fixtures.all().stream().map(Fixtures.Fixture::migration).sorted().toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void writesMappedRowsAndARerunChangesNothing(Fixtures.Fixture fixture) {
        JdbcTemplate jdbc = TestDb.clean();
        CollectionMigration m = migration(fixture.migration());
        InMemorySource source = new InMemorySource().with(fixture.collection(), fixture.docs());
        fixture.extra().forEach(source::with);
        m.prepare(source);
        JdbcTarget target = new JdbcTarget(TestDb.dataSource());

        MigrationReport.CollectionStats first = new MigrationRunner(source, target, 100, false, false)
                .run(m, DedupPlan.build(source, m, 100));
        assertEquals(0, first.failed, String.valueOf(first.failedExamples));
        assertTrue(first.reconciled());
        fixture.rows().forEach((table, rows) -> assertEquals((long) rows, count(jdbc, table), table));
        Map<String, String> before = new java.util.LinkedHashMap<>();
        fixture.rows().keySet().forEach(t -> before.put(t, snapshot(jdbc, t)));

        MigrationReport.CollectionStats second = new MigrationRunner(source, target, 100, false, true)
                .run(m, DedupPlan.build(source, m, 100));
        assertEquals(0, second.failed);
        fixture.rows().forEach((table, rows) -> {
            assertEquals((long) rows, count(jdbc, table), table);
            assertEquals(before.get(table), snapshot(jdbc, table), table + " changed on rerun");
        });

        MigrationReport.CollectionStats resumed = new MigrationRunner(source, target, 100, false, false)
                .run(m, DedupPlan.build(source, m, 100));
        assertEquals(0, resumed.read);

        MigrationReport.VerifyStats verify = new Verification(source, new JdbcTargetReader(TestDb.dataSource()), 100, 200)
                .verify(m, DedupPlan.build(source, m, 100));
        assertEquals(0, verify.keysMissing, String.valueOf(verify.examples));
        assertEquals(0, verify.fieldMismatches, String.valueOf(verify.examples));
        assertTrue(verify.sampled > 0);
        assertEquals("done", jdbc.queryForObject("select status from migration_checkpoint where collection = ?", String.class, m.name()));
    }

    @Test
    void membersThatShrinkBetweenRunsDoNotLeaveStaleRows() {
        JdbcTemplate jdbc = TestDb.clean();
        CollectionMigration m = migration("workspaces");
        Document two = new Document("_id", "w1").append("workspaceId", "ws").append("ownerId", "o")
                .append("members", List.of(new Document("userId", "a"), new Document("userId", "b")));
        Document one = new Document("_id", "w1").append("workspaceId", "ws").append("ownerId", "o")
                .append("members", List.of(new Document("userId", "a")));
        JdbcTarget target = new JdbcTarget(TestDb.dataSource());
        InMemorySource first = new InMemorySource().with("workspaces", two);
        new MigrationRunner(first, target, 10, false, true).run(m, DedupPlan.empty());
        assertEquals(2, count(jdbc, "workspace_member"));
        InMemorySource second = new InMemorySource().with("workspaces", one);
        new MigrationRunner(second, target, 10, false, true).run(m, DedupPlan.empty());
        assertEquals(1, count(jdbc, "workspace_member"));
    }

    @Test
    void aStoppedRunResumesAfterTheLastCommittedIdInPostgres() {
        JdbcTemplate jdbc = TestDb.clean();
        List<Document> docs = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            docs.add(Docs.user(i, "u" + i + "@x.com", "2026-01-01T00:00:00Z"));
        }
        InMemorySource source = new InMemorySource().with("users", docs).failAfterReading(15);
        JdbcTarget target = new JdbcTarget(TestDb.dataSource());
        UserMigration m = new UserMigration();
        assertThrows(IllegalStateException.class, () -> new MigrationRunner(source, target, 10, false, false).run(m, DedupPlan.empty()));
        assertEquals(10, count(jdbc, "app_user"));
        Map<String, Object> cp = jdbc.queryForMap("select * from migration_checkpoint where collection = 'users'");
        assertEquals("running", cp.get("status"));
        assertEquals("oid:" + Docs.oid(10).toHexString(), cp.get("last_id"));
        assertEquals(10L, cp.get("rows_read"));

        source.stopFailing();
        MigrationReport.CollectionStats resumed = new MigrationRunner(source, target, 10, false, false).run(m, DedupPlan.empty());
        assertEquals(15, resumed.read);
        assertEquals(25, count(jdbc, "app_user"));
        cp = jdbc.queryForMap("select * from migration_checkpoint where collection = 'users'");
        assertEquals("done", cp.get("status"));
        assertEquals(25L, cp.get("rows_read"));
        assertEquals(25L, cp.get("rows_written"));
    }

    @Test
    void duplicateEmailsDoNotBreakTheUniqueIndexBecauseTheOldRowsAreDropped() {
        JdbcTemplate jdbc = TestDb.clean();
        InMemorySource source = new InMemorySource().with("users",
                Docs.user(1, "Sam@Example.com", "2026-01-01T00:00:00Z"), Docs.user(2, "sam@example.com", "2026-03-01T00:00:00Z"));
        UserMigration m = new UserMigration();
        MigrationReport.CollectionStats stats = new MigrationRunner(source, new JdbcTarget(TestDb.dataSource()), 10, false, false)
                .run(m, DedupPlan.build(source, m, 10));
        assertEquals(1, stats.dropped);
        assertEquals(1, count(jdbc, "app_user"));
        assertEquals(Docs.oid(2).toHexString(), jdbc.queryForObject("select id from app_user", String.class));
    }

    @Test
    void timestampsAreStoredInUtc() {
        JdbcTemplate jdbc = TestDb.clean();
        InMemorySource source = new InMemorySource().with("users", Docs.user(1, "a@x.com", "2026-03-04T05:06:07Z"));
        new MigrationRunner(source, new JdbcTarget(TestDb.dataSource()), 10, false, false).run(new UserMigration(), DedupPlan.empty());
        assertEquals("2026-03-04 05:06:07", jdbc.queryForObject(
                "select to_char(created_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS') from app_user", String.class));
    }
}
