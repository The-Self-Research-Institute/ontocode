package self.research.ontology.migrator;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import self.research.ontology.migrator.collections.AppMigrationMigration;
import self.research.ontology.migrator.collections.AuditEventMigration;
import self.research.ontology.migrator.collections.FileMetadataMigration;
import self.research.ontology.migrator.collections.OntologyFileMigration;
import self.research.ontology.migrator.collections.PluginVersionMigration;
import self.research.ontology.migrator.collections.ProjectContainerMigration;
import self.research.ontology.migrator.collections.ProjectRecoveryLockMigration;
import self.research.ontology.migrator.collections.ReasoningJobMigration;
import self.research.ontology.migrator.collections.SwrlRuleMigration;
import self.research.ontology.migrator.collections.SystemSettingsMigration;
import self.research.ontology.migrator.collections.UserMigration;
import self.research.ontology.migrator.collections.WorkspaceMigration;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.MappingException;
import self.research.ontology.migrator.core.Values;
import self.research.ontology.migrator.core.Write;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingRulesTest {

    private static Map<String, Object> row(CollectionMigration m, Document d, String table) {
        return m.map(d).stream().filter(w -> w instanceof Write.Upsert u && u.table().equals(table))
                .map(w -> ((Write.Upsert) w).values()).findFirst().orElseThrow();
    }

    @Test
    void mongoDatesBecomeUtcTimestamps() {
        Document d = new Document("_id", "u1").append("username", "a").append("email", "a@x.com")
                .append("createdAt", Date.from(Instant.parse("2026-03-04T05:06:07Z")));
        OffsetDateTime created = (OffsetDateTime) row(new UserMigration(), d, "app_user").get("created_at");
        assertEquals(OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC), created);
        assertEquals(ZoneOffset.UTC, created.getOffset());
    }

    @Test
    void datesWithoutAZoneAreReadAsUtc() {
        Document d = new Document("_id", "u1").append("username", "a").append("email", "a@x.com")
                .append("createdAt", LocalDateTime.of(2026, 3, 4, 5, 6, 7))
                .append("updatedAt", "2026-03-04T05:06:07")
                .append("lastLoginAt", "2026-03-04T05:06:07Z");
        Map<String, Object> r = row(new UserMigration(), d, "app_user");
        OffsetDateTime expected = OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.UTC);
        assertEquals(expected, r.get("created_at"));
        assertEquals(expected, r.get("updated_at"));
        assertEquals(expected, r.get("last_login_at"));
    }

    @Test
    void objectIdAndStringIdsBecomeTextUnchanged() {
        ObjectId oid = new ObjectId("65a1b2c3d4e5f60718293a4b");
        Document withOid = new Document("_id", oid).append("username", "a").append("email", "a@x.com");
        Document withString = new Document("_id", "custom-id-7").append("username", "b").append("email", "b@x.com");
        assertEquals("65a1b2c3d4e5f60718293a4b", row(new UserMigration(), withOid, "app_user").get("id"));
        assertEquals("custom-id-7", row(new UserMigration(), withString, "app_user").get("id"));
    }

    @Test
    void fullWidthDotKeysAreDecodedAtEveryDepth() {
        Document d = new Document("_id", "v1").append("pluginId", "p").append("version", "1.0.0")
                .append("dependencies", new Document("a．b", "1").append("nested", new Document("c．d．e", List.of(new Document("x．y", 2)))));
        Write.Json json = (Write.Json) row(new PluginVersionMigration(), d, "plugin_version").get("dependencies");
        assertTrue(json.text().contains("\"a.b\":\"1\""), json.text());
        assertTrue(json.text().contains("\"c.d.e\""), json.text());
        assertTrue(json.text().contains("\"x.y\":2"), json.text());
        assertFalse(json.text().contains("．"));
    }

    @Test
    void auditMetadataAndMigrationDetailsAreDecodedToo() {
        Document audit = new Document("_id", "a1").append("username", "u").append("timestamp", new Date(0))
                .append("metadata", new Document("k．l", "v"));
        assertTrue(((Write.Json) row(new AuditEventMigration(), audit, "audit_event").get("metadata")).text().contains("\"k.l\""));
        Document file = new Document("_id", "f").append("gridfsFileId", "abc").append("metadata", new Document("prefixes", new Document("ex．", "http://x/")));
        assertTrue(((Write.Json) row(new OntologyFileMigration(), file, "ontology_file").get("metadata")).text().contains("\"ex.\""));
    }

    @Test
    void missingFlagsBecomeFalseWhereTheContractSaysSo() {
        Map<String, Object> user = row(new UserMigration(), new Document("_id", "u").append("username", "a").append("email", "a@x.com"), "app_user");
        assertEquals(false, user.get("enabled"));
        assertEquals(false, user.get("has_used_free_trial"));
        assertEquals(0, user.get("failed_login_attempts"));
        assertEquals(List.of(), user.get("roles"));
        Map<String, Object> settings = row(new SystemSettingsMigration(), new Document("_id", "global"), "system_settings");
        assertEquals(false, settings.get("maintenance_mode_enabled"));
        assertEquals(false, settings.get("maintenance_schedule_enabled"));
        assertEquals(false, settings.get("maintenance_daily_enabled"));
        Map<String, Object> lock = row(new ProjectRecoveryLockMigration(), new Document("_id", "p"), "project_recovery_lock");
        assertEquals(false, lock.get("locked"));
        assertEquals(false, lock.get("can_restore"));
        Map<String, Object> job = row(new ReasoningJobMigration(), new Document("_id", "j").append("status", "DONE").append("queuedAt", new Date(1000)), "reasoning_job");
        assertEquals(false, job.get("result_too_large"));
        assertEquals(0L, job.get("execution_time_ms"));
        Map<String, Object> rule = row(new SwrlRuleMigration(), new Document("_id", "r").append("projectId", "p").append("ruleName", "n"), "swrl_rule");
        assertEquals(false, rule.get("validated"));
    }

    @Test
    void flagsThatWereTrueByDefaultInTheOldModelStayTrue() {
        Map<String, Object> user = row(new UserMigration(), new Document("_id", "u").append("username", "a").append("email", "a@x.com"), "app_user");
        assertEquals(true, user.get("auto_renew_enabled"));
        Map<String, Object> rule = row(new SwrlRuleMigration(), new Document("_id", "r").append("projectId", "p").append("ruleName", "n"), "swrl_rule");
        assertEquals(true, rule.get("enabled"));
        Map<String, Object> ws = row(new WorkspaceMigration(), new Document("_id", "w").append("workspaceId", "ws").append("ownerId", "o"), "workspace");
        assertEquals(false, ws.get("is_deleted"));
        assertEquals(false, ws.get("collaboration_enabled"));
        assertEquals("ACTIVE", ws.get("billing_status"));
    }

    @Test
    void flagsThatMayLegitimatelyBeMissingStayNull() {
        Map<String, Object> plugin = row(new PluginVersionMigration(), new Document("_id", "v").append("pluginId", "p").append("version", "1"), "plugin_version");
        assertNull(plugin.get("deprecated"));
        assertNull(plugin.get("dependencies"));
    }

    @Test
    void blobIdsTakeTheGridFsId() {
        Map<String, Object> meta = row(new FileMetadataMigration(), new Document("_id", "m1").append("fileId", "f1")
                .append("gridfsId", "65a1b2c3d4e5f60718293a4b"), "file_metadata");
        assertEquals("65a1b2c3d4e5f60718293a4b", meta.get("blob_id"));
        Map<String, Object> file = row(new OntologyFileMigration(), new Document("_id", "proj--f1").append("gridfsFileId", new ObjectId("65a1b2c3d4e5f60718293a4c")), "ontology_file");
        assertEquals("65a1b2c3d4e5f60718293a4c", file.get("blob_id"));
    }

    @Test
    void theProjectsCollectionIsSplitByKind() {
        ProjectContainerMigration containers = new ProjectContainerMigration();
        OntologyFileMigration files = new OntologyFileMigration();
        Document withBlob = new Document("_id", "1").append("gridfsFileId", "abc").append("members", List.of());
        Document noMembersList = new Document("_id", "2").append("filename", "a.owl");
        Document nullMembers = new Document("_id", "3").append("members", null);
        Document blankBlobWithMembers = new Document("_id", "4").append("gridfsFileId", " ").append("projectId", "p").append("members", List.of());
        Document container = new Document("_id", "5").append("projectId", "p5").append("members", List.of(new Document("userId", "u")));
        Document emptyContainer = new Document("_id", "6").append("projectId", "p6").append("members", List.of());
        for (Document file : List.of(withBlob, noMembersList, nullMembers)) {
            assertTrue(files.accepts(file), file.toJson());
            assertFalse(containers.accepts(file), file.toJson());
        }
        for (Document c : List.of(blankBlobWithMembers, container, emptyContainer)) {
            assertTrue(containers.accepts(c), c.toJson());
            assertFalse(files.accepts(c), c.toJson());
        }
    }

    @Test
    void workspaceMembersKeepTheirOrderAsPositions() {
        Document d = new Document("_id", "w").append("workspaceId", "ws").append("ownerId", "o")
                .append("members", List.of(new Document("userId", "u1").append("role", "OWNER"), new Document("userId", "u2").append("role", "VIEWER")));
        List<Write> writes = new WorkspaceMigration().map(d);
        List<Write.Upsert> members = writes.stream().filter(w -> w instanceof Write.Upsert u && u.table().equals("workspace_member"))
                .map(w -> (Write.Upsert) w).toList();
        assertEquals(2, members.size());
        assertEquals(0, members.get(0).values().get("position"));
        assertEquals("u2", members.get(1).values().get("user_id"));
        assertTrue(writes.stream().anyMatch(w -> w instanceof Write.DeleteChildren));
    }

    @Test
    void appMigrationKeepsTheRestOfTheDocumentAsDetails() {
        Document d = new Document("_id", "prefix-backfill-v3").append("completedAt", "2026-01-01T00:00:00Z").append("updated", 3);
        Map<String, Object> r = row(new AppMigrationMigration(), d, "app_migration");
        assertEquals(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC), r.get("completed_at"));
        assertEquals("{\"updated\":3}", ((Write.Json) r.get("details")).text());
    }

    @Test
    void requiredFieldsAreReportedNotGuessed() {
        assertThrows(MappingException.class, () -> new UserMigration().map(new Document("_id", "u").append("email", "a@x.com")));
        assertThrows(MappingException.class, () -> new WorkspaceMigration().map(new Document("_id", "w").append("workspaceId", "ws")));
        assertThrows(MappingException.class, () -> new ReasoningJobMigration().map(new Document("_id", "j").append("status", "X")));
    }

    @Test
    void valueHelpersHandleStringsNumbersAndLists() {
        Document d = new Document("n", "42").append("l", 7).append("list", List.of(new ObjectId("65a1b2c3d4e5f60718293a4b"), "x"));
        assertEquals(42, Values.integer(d, "n"));
        assertEquals(7L, Values.longValue(d, "l"));
        assertEquals(List.of("65a1b2c3d4e5f60718293a4b", "x"), Values.list(d, "list"));
        assertNull(Values.list(d, "missing"));
    }
}
