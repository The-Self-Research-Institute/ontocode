package self.research.ontology.migrator;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import self.research.ontology.migrator.collections.FileMetadataMigration;
import self.research.ontology.migrator.collections.PluginRatingMigration;
import self.research.ontology.migrator.collections.PluginUserInstallMigration;
import self.research.ontology.migrator.collections.ProjectContainerMigration;
import self.research.ontology.migrator.collections.ProjectShareMigration;
import self.research.ontology.migrator.collections.UserMigration;
import self.research.ontology.migrator.collections.WorkspaceMigration;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.DedupPlan;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.core.MigrationRunner;
import self.research.ontology.migrator.support.Docs;
import self.research.ontology.migrator.support.InMemorySource;
import self.research.ontology.migrator.support.RecordingTarget;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateRulesTest {

    private static MigrationReport.CollectionStats migrate(CollectionMigration m, InMemorySource source, RecordingTarget target) {
        DedupPlan plan = DedupPlan.build(source, m, 100);
        return new MigrationRunner(source, target, 100, false, false).run(m, plan);
    }

    private static Set<Object> writtenKeys(RecordingTarget target, String table) {
        return target.upserts(table).stream().map(u -> u.values().get("id")).collect(Collectors.toSet());
    }

    @Test
    void emailsThatDifferOnlyByCaseKeepTheNewestRowAndReportTheRest() {
        InMemorySource source = new InMemorySource().with("users",
                Docs.user(1, "Sam@Example.com", "2026-01-01T00:00:00Z"),
                Docs.user(2, "sam@example.com", "2026-03-01T00:00:00Z"),
                Docs.user(3, "SAM@EXAMPLE.COM", "2026-02-01T00:00:00Z"),
                Docs.user(4, "other@example.com", "2026-01-01T00:00:00Z"));
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = migrate(new UserMigration(), source, target);
        assertEquals(Set.of(Docs.oid(2).toHexString(), Docs.oid(4).toHexString()), writtenKeys(target, "app_user"));
        assertEquals(4, stats.read);
        assertEquals(2, stats.written);
        assertEquals(2, stats.dropped);
        assertEquals(2L, stats.droppedByRule.get("lower(email)"));
        assertTrue(stats.droppedExamples.stream().allMatch(d -> d.reason.contains(Docs.oid(2).toHexString())));
        assertTrue(stats.reconciled());
    }

    @Test
    void whenDatesTieTheLargerIdWins() {
        InMemorySource source = new InMemorySource().with("users",
                Docs.user(1, "a@x.com", "2026-01-01T00:00:00Z"), Docs.user(2, "A@x.com", "2026-01-01T00:00:00Z"));
        RecordingTarget target = new RecordingTarget();
        migrate(new UserMigration(), source, target);
        assertEquals(Set.of(Docs.oid(2).toHexString()), writtenKeys(target, "app_user"));
    }

    @Test
    void repeatedUsernamesKeepTheNewest() {
        Document a = Docs.user(1, "a@x.com", "2026-01-01T00:00:00Z").append("username", "same");
        Document b = Docs.user(2, "b@x.com", "2026-05-01T00:00:00Z").append("username", "same");
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = migrate(new UserMigration(), new InMemorySource().with("users", a, b), target);
        assertEquals(Set.of(Docs.oid(2).toHexString()), writtenKeys(target, "app_user"));
        assertEquals(1L, stats.droppedByRule.get("username"));
    }

    @Test
    void repeatedWorkspaceIdsKeepTheNewest() {
        Document old = new Document("_id", "w1").append("workspaceId", "ws").append("ownerId", "o").append("updatedAt", Docs.date("2026-01-01T00:00:00Z"));
        Document recent = new Document("_id", "w2").append("workspaceId", "ws").append("ownerId", "o").append("updatedAt", Docs.date("2026-02-01T00:00:00Z"));
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = migrate(new WorkspaceMigration(), new InMemorySource().with("workspaces", old, recent), target);
        assertEquals(Set.of("w2"), writtenKeys(target, "workspace"));
        assertEquals(1, stats.dropped);
    }

    @Test
    void repeatedProjectIdsKeepTheNewestContainer() {
        Document old = new Document("_id", "p1").append("projectId", "proj").append("members", List.of()).append("createdAt", Docs.date("2026-01-01T00:00:00Z"));
        Document recent = new Document("_id", "p2").append("projectId", "proj").append("members", List.of()).append("createdAt", Docs.date("2026-02-01T00:00:00Z"));
        Document file = new Document("_id", "f1").append("projectId", "proj").append("gridfsFileId", "abc");
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = migrate(new ProjectContainerMigration(), new InMemorySource().with("projects", old, recent, file), target);
        assertEquals(Set.of("p2"), writtenKeys(target, "project"));
        assertEquals(1, stats.dropped);
        assertEquals(1, stats.skippedOtherKind);
        assertTrue(stats.reconciled());
    }

    @Test
    void repeatedFileIdsKeepTheNewest() {
        Document old = new Document("_id", "m1").append("fileId", "f").append("uploadedAt", Docs.date("2026-01-01T00:00:00Z"));
        Document recent = new Document("_id", "m2").append("fileId", "f").append("uploadedAt", Docs.date("2026-02-01T00:00:00Z"));
        RecordingTarget target = new RecordingTarget();
        migrate(new FileMetadataMigration(), new InMemorySource().with("file_metadata", old, recent), target);
        assertEquals(Set.of("m2"), writtenKeys(target, "file_metadata"));
    }

    @Test
    void repeatedShareRowsKeepTheNewestPerProjectAndPerLink() {
        Document a = new Document("_id", "s1").append("projectId", "p").append("shareLink", "L1").append("updatedAt", Docs.date("2026-01-01T00:00:00Z"));
        Document b = new Document("_id", "s2").append("projectId", "p").append("shareLink", "L2").append("updatedAt", Docs.date("2026-02-01T00:00:00Z"));
        Document c = new Document("_id", "s3").append("projectId", "q").append("shareLink", "L2").append("updatedAt", Docs.date("2026-03-01T00:00:00Z"));
        Document d = new Document("_id", "s4").append("projectId", "r").append("updatedAt", Docs.date("2026-03-01T00:00:00Z"));
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = migrate(new ProjectShareMigration(), new InMemorySource().with("project_shares", a, b, c, d), target);
        assertEquals(Set.of("s3", "s4"), writtenKeys(target, "project_share"));
        assertEquals(2, stats.dropped);
        assertEquals(1L, stats.droppedByRule.get("project_id"));
        assertEquals(1L, stats.droppedByRule.get("share_link"));
    }

    @Test
    void repeatedRatingsAndInstallsKeepTheNewestPerPluginAndUser() {
        Document r1 = new Document("_id", "r1").append("pluginId", "p").append("userId", "u").append("stars", 1).append("updatedAt", Docs.date("2026-01-01T00:00:00Z"));
        Document r2 = new Document("_id", "r2").append("pluginId", "p").append("userId", "u").append("stars", 5).append("updatedAt", Docs.date("2026-02-01T00:00:00Z"));
        Document r3 = new Document("_id", "r3").append("pluginId", "p").append("userId", "v").append("stars", 4);
        RecordingTarget ratings = new RecordingTarget();
        migrate(new PluginRatingMigration(), new InMemorySource().with("plugin_ratings", r1, r2, r3), ratings);
        assertEquals(Set.of("r2", "r3"), writtenKeys(ratings, "plugin_rating"));
        Document i1 = new Document("_id", "i1").append("pluginId", "p").append("userId", "u").append("lastInstalledAt", Docs.date("2026-04-01T00:00:00Z"));
        Document i2 = new Document("_id", "i2").append("pluginId", "p").append("userId", "u").append("lastInstalledAt", Docs.date("2026-01-01T00:00:00Z"));
        RecordingTarget installs = new RecordingTarget();
        MigrationReport.CollectionStats stats = migrate(new PluginUserInstallMigration(), new InMemorySource().with("plugin_user_installs", i1, i2), installs);
        assertEquals(Set.of("i1"), writtenKeys(installs, "plugin_user_install"));
        assertEquals(1, stats.dropped);
    }
}
