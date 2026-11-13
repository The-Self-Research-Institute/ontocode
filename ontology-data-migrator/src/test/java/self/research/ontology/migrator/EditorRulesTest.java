package self.research.ontology.migrator;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import self.research.ontology.migrator.collections.AssistantApplyOperationMigration;
import self.research.ontology.migrator.collections.AssistantAuditMigration;
import self.research.ontology.migrator.collections.DatatypeDefinitionMigration;
import self.research.ontology.migrator.collections.DraftChangeMigration;
import self.research.ontology.migrator.collections.DraftPrChangeMigration;
import self.research.ontology.migrator.collections.DraftSessionMigration;
import self.research.ontology.migrator.collections.FuzzyRuleMigration;
import self.research.ontology.migrator.collections.HistoryChangeMigration;
import self.research.ontology.migrator.collections.IssueReportMigration;
import self.research.ontology.migrator.collections.Migrations;
import self.research.ontology.migrator.collections.RollbackAuditMigration;
import self.research.ontology.migrator.collections.SparqlQueryMigration;
import self.research.ontology.migrator.collections.SpreadsheetImportRecordMigration;
import self.research.ontology.migrator.collections.SpreadsheetRuleSetMigration;
import self.research.ontology.migrator.collections.UndoIntentMigration;
import self.research.ontology.migrator.core.CollectionMigration;
import self.research.ontology.migrator.core.DedupPlan;
import self.research.ontology.migrator.core.MigrationReport;
import self.research.ontology.migrator.core.MigrationRunner;
import self.research.ontology.migrator.core.Write;
import self.research.ontology.migrator.support.InMemorySource;
import self.research.ontology.migrator.support.RecordingTarget;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static self.research.ontology.migrator.support.Docs.date;

class EditorRulesTest {

    private static Map<String, Object> row(CollectionMigration m, Document d, String table) {
        return m.map(d).stream().filter(w -> w instanceof Write.Upsert u && u.table().equals(table))
                .map(w -> ((Write.Upsert) w).values()).findFirst().orElseThrow();
    }

    private static MigrationReport.CollectionStats run(CollectionMigration m, InMemorySource source, RecordingTarget target) {
        m.prepare(source);
        return new MigrationRunner(source, target, 100, false, false).run(m, DedupPlan.build(source, m, 100));
    }

    private static Set<Object> ids(RecordingTarget target, String table) {
        return target.upserts(table).stream().map(u -> u.values().get("id")).collect(Collectors.toSet());
    }

    @Test
    void draftSessionsKeepTheNewestPerProjectAndUser() {
        Document old = new Document("_id", "a").append("projectId", "p").append("userId", "u").append("baselineAt", date("2026-01-01T00:00:00Z"));
        Document recent = new Document("_id", "b").append("projectId", "p").append("userId", "u").append("baselineAt", date("2026-02-01T00:00:00Z"));
        Document other = new Document("_id", "c").append("projectId", "p").append("userId", "v");
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = run(new DraftSessionMigration(), new InMemorySource().with("draft_sessions", old, recent, other), target);
        assertEquals(Set.of("b", "c"), ids(target, "draft_session"));
        assertEquals(1L, stats.droppedByRule.get("project_id+user_id"));
    }

    @Test
    void prefixKeysAreDecodedAndMissingCountersAreZero() {
        Document d = new Document("_id", "a").append("projectId", "p").append("userId", "u")
                .append("prefixes", new Document("ex．", "http://e/").append("", "http://x/"));
        Map<String, Object> r = row(new DraftSessionMigration(), d, "draft_session");
        String json = ((Write.Json) r.get("prefixes")).text();
        assertTrue(json.contains("\"ex.\""), json);
        assertTrue(json.contains("\"\":\"http://x/\""), json);
        assertEquals(0L, r.get("baseline_main_revision"));
    }

    @Test
    void aMissingAppliedFlagBecomesFalse() {
        Map<String, Object> r = row(new DraftChangeMigration(), new Document("_id", "d").append("projectId", "p").append("applied", null), "draft_change");
        assertEquals(false, r.get("applied"));
        assertEquals(false, row(new DraftChangeMigration(), new Document("_id", "d").append("projectId", "p"), "draft_change").get("applied"));
    }

    @Test
    void pullRequestChangesWithoutTheirPullRequestAreSkippedAndReported() {
        InMemorySource source = new InMemorySource()
                .with("draft_pull_requests", new Document("_id", "pr-1").append("projectId", "p"))
                .with("draft_pr_changes",
                        new Document("_id", "c1").append("prId", "pr-1").append("projectId", "p"),
                        new Document("_id", "c2").append("prId", "pr-gone").append("projectId", "p"),
                        new Document("_id", "c3").append("projectId", "p"));
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = run(new DraftPrChangeMigration(), source, target);
        assertEquals(Set.of("c1"), ids(target, "draft_pr_change"));
        assertEquals(2L, stats.droppedByRule.get("pull request missing"));
        assertTrue(stats.reconciled());
        Map<String, Object> r = target.upserts("draft_pr_change").get(0).values();
        assertEquals(false, r.get("has_change"));
    }

    @Test
    void anEmbeddedChangeIsFlattened() {
        Document d = new Document("_id", "c1").append("prId", "pr-1").append("change",
                new Document("_id", "dc").append("operationType", "x").append("applied", true));
        Map<String, Object> r = row(new DraftPrChangeMigration(), d, "draft_pr_change");
        assertEquals(true, r.get("has_change"));
        assertEquals("dc", r.get("change_id"));
        assertEquals(true, r.get("change_applied"));
    }

    @Test
    void historyRowsWithASnapshotOrDraftRecordsGetFormatMongo() {
        HistoryChangeMigration history = new HistoryChangeMigration();
        assertEquals("mongo", row(history, new Document("_id", "h").append("projectId", "p").append("documentBefore", "{\"_id\":1}"), "history_change").get("format"));
        assertEquals("mongo", row(history, new Document("_id", "h").append("projectId", "p").append("documentAfter", "{}"), "history_change").get("format"));
        assertEquals("json", row(history, new Document("_id", "h").append("projectId", "p"), "history_change").get("format"));
        assertEquals("json", row(history, new Document("_id", "h").append("projectId", "p").append("documentBefore", ""), "history_change").get("format"));
        RollbackAuditMigration audit = new RollbackAuditMigration();
        Document withRecords = new Document("_id", "r").append("projectId", "p").append("draftRecords", List.of(new Document("_id", "d1").append("timestamp", new Date(0))));
        Map<String, Object> mongo = row(audit, withRecords, "rollback_audit");
        assertEquals("mongo", mongo.get("format"));
        assertTrue(((Write.Json) mongo.get("draft_records")).text().contains("\"_id\":\"d1\""));
        Map<String, Object> plain = row(audit, new Document("_id", "r").append("projectId", "p").append("draftRecords", List.of()), "rollback_audit");
        assertEquals("json", plain.get("format"));
        assertEquals(null, plain.get("draft_records"));
        assertEquals("UNDO", plain.get("direction"));
    }

    @Test
    void subChangeIdsComeFromTheirMongoId() {
        Document d = new Document("_id", "h").append("projectId", "p").append("subChanges", List.of(
                new Document("_id", "sc-1").append("predicate", "x").append("reverted", true),
                new Document("id", "sc-2").append("predicate", "y")));
        String json = ((Write.Json) row(new HistoryChangeMigration(), d, "history_change").get("sub_changes")).text();
        assertTrue(json.contains("\"id\":\"sc-1\""), json);
        assertTrue(json.contains("\"id\":\"sc-2\""), json);
        assertFalse(json.contains("_id"), json);
    }

    @Test
    void historyCommentAndMetadataKeysAreDecoded() {
        Document d = new Document("_id", "h").append("projectId", "p")
                .append("comments", new Document("a．b", new Document("text", "t")))
                .append("metadata", new Document("m．n", "v"));
        Map<String, Object> r = row(new HistoryChangeMigration(), d, "history_change");
        assertTrue(((Write.Json) r.get("comments")).text().contains("\"a.b\""));
        assertTrue(((Write.Json) r.get("metadata")).text().contains("\"m.n\""));
    }

    @Test
    void undoIntentsKeepTheNewestPerProjectAndOperationAndLeaveNullOperationsAlone() {
        Document a = new Document("_id", "a").append("projectId", "p").append("opId", "op").append("updatedAt", date("2026-01-01T00:00:00Z"));
        Document b = new Document("_id", "b").append("projectId", "p").append("opId", "op").append("updatedAt", date("2026-02-01T00:00:00Z"));
        Document n1 = new Document("_id", "n1").append("projectId", "p");
        Document n2 = new Document("_id", "n2").append("projectId", "p");
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = run(new UndoIntentMigration(), new InMemorySource().with("undo_intents", a, b, n1, n2), target);
        assertEquals(Set.of("b", "n1", "n2"), ids(target, "undo_intent"));
        assertEquals(1, stats.dropped);
    }

    @Test
    void assistantAuditGetsNinetyDaysAndOldRowsAreSkipped() {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        AssistantAuditMigration m = new AssistantAuditMigration(Clock.fixed(now, ZoneOffset.UTC));
        Document fresh = new Document("_id", "a1").append("createdAt", Date.from(now.minus(Duration.ofDays(10))));
        Document edge = new Document("_id", "a2").append("createdAt", Date.from(now.minus(Duration.ofDays(90))));
        Document old = new Document("_id", "a3").append("createdAt", Date.from(now.minus(Duration.ofDays(120))));
        Document undated = new Document("_id", "a4");
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = run(m, new InMemorySource().with("assistant_audit", fresh, old, edge, undated), target);
        assertEquals(Set.of("a1"), ids(target, "assistant_audit"));
        assertEquals(3, stats.dropped);
        assertEquals(2L, stats.droppedByRule.get("past 90 day retention"));
        Map<String, Object> r = target.upserts("assistant_audit").get(0).values();
        OffsetDateTime created = (OffsetDateTime) r.get("created_at");
        assertEquals(created.plusDays(90), r.get("expires_at"));
    }

    @Test
    void onlyUnresolvedApplyOperationsAreCopied() {
        Date resolved = date("2026-02-01T00:00:00Z");
        InMemorySource source = new InMemorySource().with("assistant_apply_operations",
                new Document("_id", "1").append("status", "PREPARED"),
                new Document("_id", "2").append("status", "GRAPH_IMPORTING"),
                new Document("_id", "3").append("status", "FAILED"),
                new Document("_id", "4").append("status", "FAILED").append("resolvedAt", resolved),
                new Document("_id", "5").append("status", "COMMITTED").append("resolvedAt", resolved),
                new Document("_id", "6").append("status", "PREPARED").append("resolvedAt", resolved));
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = run(new AssistantApplyOperationMigration(), source, target);
        assertEquals(Set.of("1", "2", "3", "6"), ids(target, "assistant_apply_operation"));
        assertEquals(2, stats.dropped);
        assertTrue(stats.reconciled());
    }

    @Test
    void fuzzyRulesWithoutEnabledAreEnabled() {
        Map<String, Object> r = row(new FuzzyRuleMigration(), new Document("_id", "f").append("projectId", "p").append("condition", "c").append("action", "a"), "fuzzy_rule");
        assertEquals(true, r.get("enabled"));
        assertEquals("c", r.get("rule_condition"));
        assertEquals("a", r.get("rule_action"));
        assertEquals(false, row(new FuzzyRuleMigration(), new Document("_id", "f").append("projectId", "p").append("enabled", false), "fuzzy_rule").get("enabled"));
    }

    @Test
    void missingFlagsAndCountsOfRecordsDefault() {
        Map<String, Object> dt = row(new DatatypeDefinitionMigration(), new Document("_id", "d").append("projectId", "p"), "datatype_definition");
        assertEquals(false, dt.get("draft"));
        assertEquals(false, dt.get("deleted"));
        Map<String, Object> si = row(new SpreadsheetImportRecordMigration(), new Document("_id", "s").append("projectId", "p"), "spreadsheet_import_record");
        assertEquals(0, si.get("axiom_count"));
        assertEquals(0, si.get("already_present_count"));
        assertEquals(false, si.get("rolled_back"));
        assertEquals(List.of(), si.get("added_triples"));
    }

    @Test
    void rowsWithoutAProjectAreSkippedAndReported() {
        Document noProject = new Document("_id", "x").append("name", "n");
        Document blank = new Document("_id", "y").append("projectId", " ");
        for (CollectionMigration m : List.of(new DraftChangeMigration(), new HistoryChangeMigration(),
                new DatatypeDefinitionMigration(), new FuzzyRuleMigration(), new SparqlQueryMigration(),
                new SpreadsheetImportRecordMigration(), new SpreadsheetRuleSetMigration(), new UndoIntentMigration(),
                new RollbackAuditMigration())) {
            RecordingTarget target = new RecordingTarget();
            MigrationReport.CollectionStats stats = run(m, new InMemorySource().with(m.sourceCollection(), noProject, blank), target);
            assertEquals(2, stats.dropped, m.name());
            assertEquals(2L, stats.droppedByRule.get("missing projectId"), m.name());
            assertTrue(target.writes.isEmpty(), m.name());
            assertTrue(stats.reconciled(), m.name());
        }
    }

    @Test
    void issueReportsWithoutAProjectAreKept() {
        Map<String, Object> r = row(new IssueReportMigration(), new Document("_id", "i").append("title", "t")
                .append("systemInfo", new Document("osName", "Win")), "issue_report");
        assertEquals(null, r.get("project_id"));
        assertTrue(((Write.Json) r.get("system_info")).text().contains("\"osName\":\"Win\""));
    }

    @Test
    void ruleSetsKeepTheNewestPerProjectAndName() {
        Document old = new Document("_id", "a").append("projectId", "p").append("name", "n").append("updatedAt", date("2026-01-01T00:00:00Z"));
        Document recent = new Document("_id", "b").append("projectId", "p").append("name", "n").append("updatedAt", date("2026-02-01T00:00:00Z"))
                .append("rules", List.of(new Document("sheetName", "S").append("startRow", 2)));
        Document other = new Document("_id", "c").append("projectId", "q").append("name", "n");
        RecordingTarget target = new RecordingTarget();
        MigrationReport.CollectionStats stats = run(new SpreadsheetRuleSetMigration(), new InMemorySource().with("spreadsheet_rule_sets", old, recent, other), target);
        assertEquals(Set.of("b", "c"), ids(target, "spreadsheet_rule_set"));
        assertEquals(1, stats.dropped);
        Map<String, Object> withRules = target.upserts("spreadsheet_rule_set").stream().filter(u -> "b".equals(u.values().get("id"))).findFirst().orElseThrow().values();
        assertEquals("[{\"sheetName\":\"S\",\"startRow\":2}]", ((Write.Json) withRules.get("rules")).text());
        Map<String, Object> empty = target.upserts("spreadsheet_rule_set").stream().filter(u -> "c".equals(u.values().get("id"))).findFirst().orElseThrow().values();
        assertEquals("[]", ((Write.Json) empty.get("rules")).text());
    }

    @Test
    void cachesAreNotMigratedAndSwrlRulesAreMigratedExactlyOnce() {
        Set<String> sources = Migrations.all().stream().map(CollectionMigration::sourceCollection).collect(Collectors.toSet());
        for (String cache : List.of("class_details", "entity_usage", "hierarchy_snapshots", "hierarchy_snapshot_chunks",
                "top_level_class_cache", "assistant_sessions", "assistant_edit_group", "assistant_idempotency", "plugin_installations")) {
            assertFalse(sources.contains(cache), cache);
        }
        assertEquals(1, Migrations.all().stream().filter(m -> m.sourceCollection().equals("swrl_rules")).count());
        assertTrue(sources.contains("draft_changes") && sources.contains("history_changes") && sources.contains("assistant_audit"));
    }
}
