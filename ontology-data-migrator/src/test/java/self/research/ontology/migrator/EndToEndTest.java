package self.research.ontology.migrator;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.DiskBlobStore;
import self.research.ontology.common.db.StoredFiles;
import self.research.ontology.migrator.collections.Migrations;
import self.research.ontology.migrator.core.BlobFile;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.core.Options;
import self.research.ontology.migrator.persistence.DiskBlobSink;
import self.research.ontology.migrator.persistence.JdbcTarget;
import self.research.ontology.migrator.persistence.JdbcTargetReader;
import self.research.ontology.migrator.persistence.OrphanChecks;
import self.research.ontology.migrator.support.Docs;
import self.research.ontology.migrator.support.Fixtures;
import self.research.ontology.migrator.support.InMemorySource;
import self.research.ontology.migrator.support.MemoryBlobSource;
import self.research.ontology.migrator.support.RecordingTarget;
import self.research.ontology.migrator.support.TestDb;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndToEndTest {

    @TempDir
    Path root;

    private static InMemorySource everything() {
        Map<String, Map<String, Document>> byCollection = new LinkedHashMap<>();
        for (Fixtures.Fixture f : Fixtures.all()) {
            Map<String, Document> docs = byCollection.computeIfAbsent(f.collection(), k -> new LinkedHashMap<>());
            f.docs().forEach(d -> docs.put(String.valueOf(d.get("_id")), d));
        }
        InMemorySource source = new InMemorySource();
        byCollection.forEach((name, docs) -> source.with(name, List.copyOf(docs.values())));
        return source;
    }

    private static MemoryBlobSource blobs() {
        byte[] bytes = "owl content".getBytes();
        BlobFile owl = new BlobFile("fs", null, Docs.oid(500).toHexString(), "a.owl", bytes.length, "application/rdf+xml", Instant.parse("2026-01-03T00:00:00Z"), Map.of());
        BlobFile vsix = new BlobFile("plugins", null, Docs.oid(501).toHexString(), "theme.vsix", bytes.length, null, Instant.parse("2026-01-03T00:00:00Z"), Map.of());
        return new MemoryBlobSource().add(owl, bytes).add(vsix, bytes);
    }

    private MigrationReport run(Options options, InMemorySource source, MemoryBlobSource blobs, JdbcTemplate jdbc) {
        DiskBlobStore store = new DiskBlobStore(new StoredFiles(jdbc, new com.fasterxml.jackson.databind.ObjectMapper()), root, 0);
        Migrator migrator = new Migrator(options, Migrations.all(), source, blobs, new JdbcTarget(TestDb.dataSource()),
                new JdbcTargetReader(TestDb.dataSource()), new DiskBlobSink(store), new OrphanChecks(TestDb.dataSource())::run);
        MigrationReport report = new MigrationReport();
        migrator.run(report);
        return report;
    }

    private static Options options() {
        Options o = new Options();
        o.buckets = List.of("fs", "plugins", "desktop-installers");
        o.batchSize = 50;
        return o;
    }

    @Test
    void aFullRunReconcilesAndAReRunStaysReconciled() {
        JdbcTemplate jdbc = TestDb.clean();
        InMemorySource source = everything();
        MemoryBlobSource blobs = blobs();
        MigrationReport first = run(options(), source, blobs, jdbc);
        assertTrue(first.reconciled, first.toText());
        assertEquals(Migrations.all().size(), first.collections.size());
        assertEquals(2, first.collections.get("projects:containers").skippedOtherKind);
        assertEquals(2, first.blobs.get("fs").files + first.blobs.get("plugins").files);
        assertTrue(first.orphans.values().stream().allMatch(c -> c == 0), first.orphans.toString());
        assertTrue(first.toJson().contains("\"reconciled\" : true"));

        Options fresh = options();
        fresh.fresh = true;
        MigrationReport second = run(fresh, source, blobs, jdbc);
        assertTrue(second.reconciled, second.toText());
        assertEquals(2, second.blobs.get("fs").alreadyPresent + second.blobs.get("plugins").alreadyPresent);
    }

    @Test
    void verifyOnlyWritesNothingAndFindsDamage() {
        JdbcTemplate jdbc = TestDb.clean();
        InMemorySource source = everything();
        MemoryBlobSource blobs = blobs();
        run(options(), source, blobs, jdbc);
        Options verify = options();
        verify.verifyOnly = true;
        assertTrue(run(verify, source, blobs, jdbc).reconciled);

        jdbc.update("delete from plugin_rating");
        jdbc.update("update app_user set email = 'changed@x.com' where username = 'alice'");
        MigrationReport damaged = run(verify, source, blobs, jdbc);
        assertFalse(damaged.reconciled);
        assertEquals(1, damaged.verification.get("plugin_ratings").keysMissing);
        assertTrue(damaged.verification.get("users").fieldMismatches >= 1);
        assertTrue(damaged.collections.isEmpty());
    }

    @Test
    void orphansMakeTheRunUnreconciledUnlessAllowed() {
        JdbcTemplate jdbc = TestDb.clean();
        InMemorySource source = everything().with("plugin_ratings",
                new Document("_id", "r-orphan").append("pluginId", "no-such-plugin").append("userId", "u9").append("stars", 3));
        MigrationReport report = run(options(), source, blobs(), jdbc);
        assertFalse(report.reconciled);
        assertEquals(1L, report.orphans.get("plugin_rating.plugin_id without plugin"));
        Options allow = options();
        allow.allowOrphans = true;
        allow.fresh = true;
        assertTrue(run(allow, source, blobs(), jdbc).reconciled);
    }

    @Test
    void onlyLimitsTheRunToTheNamedCollectionsAndSkipsBlobs() {
        JdbcTemplate jdbc = TestDb.clean();
        Options only = options();
        only.only = new java.util.LinkedHashSet<>(List.of("users", "workspaces"));
        MigrationReport report = run(only, everything(), blobs(), jdbc);
        assertEquals(List.of("users", "workspaces"), List.copyOf(report.collections.keySet()));
        assertTrue(report.blobs.isEmpty());
        assertEquals(0L, jdbc.queryForObject("select count(*) from plugin", Long.class));
    }

    @Test
    void dryRunReportsWithoutTouchingTheDatabase() {
        JdbcTemplate jdbc = TestDb.clean();
        Options dry = options();
        dry.dryRun = true;
        InMemorySource source = everything();
        Migrator migrator = new Migrator(dry, Migrations.all(), source, blobs(), new RecordingTarget(), null, new DiskBlobSink(null), null);
        MigrationReport report = new MigrationReport();
        migrator.run(report);
        assertTrue(report.dryRun);
        assertTrue(report.reconciled, report.toText());
        assertTrue(report.collections.get("users").written > 0);
        assertEquals(0L, jdbc.queryForObject("select count(*) from app_user", Long.class));
        assertTrue(report.verification.isEmpty());
        assertTrue(report.toText().contains("dry run"));
    }
}
