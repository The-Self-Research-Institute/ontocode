package self.research.ontology.migrator.support;

import org.bson.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static self.research.ontology.migrator.support.Docs.date;
import static self.research.ontology.migrator.support.Docs.oid;

public final class Fixtures {

    public record Fixture(String migration, String collection, List<Document> docs, Map<String, Integer> rows,
                          Map<String, List<Document>> extra) {

        public Fixture(String migration, String collection, List<Document> docs, Map<String, Integer> rows) {
            this(migration, collection, docs, rows, Map.of());
        }
    }

    private Fixtures() {
    }

    public static List<Fixture> all() {
        return List.of(
                new Fixture("users", "users", List.of(
                        new Document("_id", oid(1)).append("username", "alice").append("email", "Alice@Example.com")
                                .append("password", "hash").append("roles", List.of("USER", "ADMIN")).append("enabled", true)
                                .append("failedLoginAttempts", 2).append("autoRenewEnabled", false)
                                .append("subscriptionStatus", "active").append("createdAt", date("2026-01-02T03:04:05Z"))
                                .append("subscriptionCurrentPeriodEnd", date("2027-01-02T03:04:05Z")),
                        new Document("_id", oid(2)).append("username", "bob").append("email", "bob@example.com")),
                        Map.of("app_user", 2)),
                new Fixture("workspaces", "workspaces", List.of(
                        new Document("_id", oid(10)).append("workspaceId", "ws-1").append("ownerId", oid(1).toHexString())
                                .append("name", "Team").append("maxMembers", 5).append("collaborationEnabled", true)
                                .append("members", List.of(
                                        new Document("userId", oid(1).toHexString()).append("username", "alice").append("email", "a@x.com")
                                                .append("role", "OWNER").append("status", "ACTIVE").append("joinedAt", date("2026-01-02T00:00:00Z")),
                                        new Document("userId", oid(2).toHexString()).append("role", "VIEWER").append("status", "PENDING")
                                                .append("invitationToken", "tok")))),
                        Map.of("workspace", 1, "workspace_member", 2)),
                new Fixture("projects:containers", "projects", List.of(
                        new Document("_id", oid(20)).append("projectId", "proj-1").append("name", "P").append("workspaceId", "ws-1")
                                .append("ownerId", oid(1).toHexString()).append("tags", List.of("a", "b")).append("status", "ACTIVE")
                                .append("members", List.of(new Document("userId", oid(1).toHexString()).append("role", "OWNER").append("joinedAt", date("2026-01-02T00:00:00Z"))))
                                .append("files", List.of(
                                        new Document("fileId", "f1").append("fileName", "a.owl").append("fileSize", 12L).append("status", "ACTIVE")
                                                .append("uploadedAt", date("2026-01-03T00:00:00Z")),
                                        new Document("fileId", "f2").append("fileName", "b.ttl").append("fileSize", 5))),
                        new Document("_id", "file-doc").append("filename", "a.owl").append("gridfsFileId", oid(500).toHexString())),
                        Map.of("project", 1, "project_member", 1, "project_file", 2)),
                new Fixture("file_metadata", "file_metadata", List.of(
                        new Document("_id", oid(30)).append("fileId", "f1").append("fileName", "a.owl").append("projectId", "proj-1")
                                .append("fileSize", 12L).append("gridfsId", oid(500).toHexString()).append("uploadedAt", date("2026-01-03T00:00:00Z"))
                                .append("isDeleted", false)),
                        Map.of("file_metadata", 1)),
                new Fixture("invitations", "invitations", List.of(
                        new Document("_id", oid(40)).append("invitationToken", "t-1").append("inviteeEmail", "x@y.com").append("status", "PENDING")
                                .append("createdAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("invitation", 1)),
                new Fixture("stripe_events", "stripe_events", List.of(
                        new Document("_id", oid(41)).append("stripeEventId", "evt_1").append("eventType", "invoice.paid")
                                .append("receivedAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("stripe_event", 1)),
                new Fixture("email_logs", "email_logs", List.of(
                        new Document("_id", oid(42)).append("key", "k-1").append("type", "billing_reminder").append("daysBefore", 3)
                                .append("periodEnd", date("2026-02-01T00:00:00Z")).append("amountPaid", 4900L).append("createdAt", date("2026-01-01T00:00:00Z"))),
                        Map.of("email_log", 1)),
                new Fixture("stripe_unmatched_invoices", "stripe_unmatched_invoices", List.of(
                        new Document("_id", oid(43)).append("invoiceId", "in_1").append("amountPaid", 100L).append("recordedAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("stripe_unmatched_invoice", 1)),
                new Fixture("system_settings", "system_settings", List.of(
                        new Document("_id", "global").append("maintenanceModeEnabled", true).append("maintenanceAllowedEmails", List.of("a@x.com"))
                                .append("maintenanceStartTime", date("2026-02-01T00:00:00Z"))),
                        Map.of("system_settings", 1)),
                new Fixture("plan_feature_configs", "plan_feature_configs", List.of(
                        new Document("_id", oid(44)).append("planId", "PRO").append("monthlyPrice", 49).append("features", List.of("a", "b"))),
                        Map.of("plan_feature_config", 1)),
                new Fixture("desktop_download_events", "desktop_download_events", List.of(
                        new Document("_id", oid(45)).append("platform", "win").append("eventType", "download").append("recordedAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("desktop_download_event", 1)),
                new Fixture("audit_events", "audit_events", List.of(
                        new Document("_id", oid(46)).append("username", "alice").append("eventType", "LOGIN_SUCCESS")
                                .append("timestamp", date("2026-02-01T00:00:00Z")).append("metadata", new Document("reason．code", "x"))),
                        Map.of("audit_event", 1)),
                new Fixture("plugins", "plugins", List.of(
                        new Document("_id", oid(50)).append("pluginId", "theme-pack").append("name", "Theme").append("keywords", List.of("t"))
                                .append("totalDownloads", 10L).append("averageRating", 4.5).append("verified", true)
                                .append("createdAt", date("2026-01-01T00:00:00Z"))),
                        Map.of("plugin", 1)),
                new Fixture("plugin_versions", "plugin_versions", List.of(
                        new Document("_id", oid(51)).append("pluginId", "theme-pack").append("version", "1.0.0")
                                .append("vsixFileId", oid(501).toHexString()).append("fileSize", 99L)
                                .append("dependencies", new Document("a．b", "1")).append("publishedAt", date("2026-01-01T00:00:00Z"))),
                        Map.of("plugin_version", 1)),
                new Fixture("plugin_ratings", "plugin_ratings", List.of(
                        new Document("_id", oid(52)).append("pluginId", "theme-pack").append("userId", "u1").append("stars", 5).append("recommended", true)),
                        Map.of("plugin_rating", 1)),
                new Fixture("plugin_user_installs", "plugin_user_installs", List.of(
                        new Document("_id", oid(53)).append("pluginId", "theme-pack").append("userId", "u1").append("isActive", true).append("totalInstalls", 2)),
                        Map.of("plugin_user_install", 1)),
                new Fixture("swrl_rules", "swrl_rules", List.of(
                        new Document("_id", oid(60)).append("projectId", "proj-1--f1").append("ruleName", "r1").append("ruleText", "A(?x) -> B(?x)")
                                .append("executionCount", 3).append("lastExecutionTime", 120L)),
                        Map.of("swrl_rule", 1)),
                new Fixture("reasoning_jobs", "reasoning_jobs", List.of(
                        new Document("_id", "job-1").append("jobType", "CLASSIFY").append("projectId", "file-1").append("status", "COMPLETED")
                                .append("queuedAt", date("2026-02-01T00:00:00Z")).append("updatedAt", date("2026-02-01T00:01:00Z"))
                                .append("executionTimeMs", 1500L).append("resultJson", "{\"a\":1}")),
                        Map.of("reasoning_job", 1)),
                new Fixture("reasoning_snapshots", "reasoning_snapshots", List.of(
                        new Document("_id", "snap-1").append("projectId", "file-1").append("revision", 4L).append("tripleCount", 1000L)
                                .append("reasoner", "HermiT").append("createdAt", date("2026-02-01T00:00:00Z")).append("updatedAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("reasoning_snapshot", 1)),
                new Fixture("projects:files", "projects", List.of(
                        new Document("_id", "proj-1--f1").append("name", "a").append("filename", "a.owl").append("ownerEmail", "alice@example.com")
                                .append("projectId", "proj-1").append("gridfsFileId", oid(500).toHexString()).append("status", "READY")
                                .append("createdAt", date("2026-01-03T00:00:00Z")).append("updatedAt", date("2026-01-04T00:00:00Z"))
                                .append("metadata", new Document("imports", List.of("http://x/y")).append("prefixes", new Document("ex．", "http://e/")))
                                .append("mutationVersion", 7L).append("mainGraphRevision", 3L),
                        new Document("_id", oid(20)).append("projectId", "proj-1").append("members", List.of())),
                        Map.of("ontology_file", 1)),
                new Fixture("project_shares", "project_shares", List.of(
                        new Document("_id", oid(70)).append("projectId", "proj-1--f1").append("ownerEmail", "alice@example.com")
                                .append("sharedWithEmails", List.of("b@x.com", "c@x.com")).append("shareLink", "LINK1").append("permission", "view")
                                .append("createdAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("project_share", 1)),
                new Fixture("user_project_preferences", "user_project_preferences", List.of(
                        new Document("_id", oid(71)).append("userEmail", "alice@example.com").append("projectId", "proj-1--f1").append("syncMode", "auto")),
                        Map.of("user_project_preference", 1)),
                new Fixture("project_public_graph_versions", "project_public_graph_versions", List.of(
                        new Document("_id", "proj-1--f1").append("version", 12L).append("updatedAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("project_public_graph_version", 1)),
                new Fixture("project_write_leases", "project_write_leases", List.of(
                        new Document("_id", "proj-1--f1").append("ownerToken", "tok").append("holder", "alice")
                                .append("acquiredAt", date("2026-02-01T00:00:00Z")).append("expiresAt", date("2026-02-01T00:05:00Z"))),
                        Map.of("project_write_lease", 1)),
                new Fixture("project_recovery_locks", "project_recovery_locks", List.of(
                        new Document("_id", "proj-1--f1").append("locked", true).append("reason", "restore").append("lockedAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("project_recovery_lock", 1)),
                new Fixture("app_migrations", "app_migrations", List.of(
                        new Document("_id", "prefix-backfill-v3").append("completedAt", "2026-02-01T00:00:00Z").append("updated", 3).append("failed", 0)),
                        Map.of("app_migration", 1)),
                new Fixture("draft_changes", "draft_changes", List.of(
                        new Document("_id", "dc-1").append("projectId", "proj-1--f1").append("userId", "u1").append("username", "alice")
                                .append("operationType", "createClass").append("operationData", new Document("iri", "http://x/A").append("p\uFF0Eq", 1))
                                .append("timestamp", date("2026-02-01T00:00:00Z")).append("sessionId", "s1"),
                        new Document("_id", "dc-2").append("projectId", "proj-1--f1").append("applied", true)),
                        Map.of("draft_change", 2)),
                new Fixture("spreadsheet_import_parked_drafts", "spreadsheet_import_parked_drafts", List.of(
                        new Document("_id", "pk-1").append("projectId", "proj-1--f1").append("userId", "u1").append("operationType", "addAxiom")
                                .append("timestamp", date("2026-02-01T00:00:00Z"))),
                        Map.of("draft_change_parked", 1)),
                new Fixture("draft_sessions", "draft_sessions", List.of(
                        new Document("_id", "ds-1").append("projectId", "proj-1--f1").append("userId", "u1").append("baselineMainRevision", 4L)
                                .append("baselineAt", date("2026-02-01T00:00:00Z")).append("copyStatus", "READY")
                                .append("prefixes", new Document("", "http://x/").append("ex\uFF0E", "http://e/"))),
                        Map.of("draft_session", 1)),
                new Fixture("draft_pull_requests", "draft_pull_requests", List.of(
                        new Document("_id", "pr-1").append("projectId", "proj-1--f1").append("authorId", "u1").append("title", "T")
                                .append("status", "OPEN").append("changeCount", 2).append("submittedChangeIds", List.of("dc-1", "dc-2"))
                                .append("createdAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("draft_pull_request", 1)),
                new Fixture("draft_pr_changes", "draft_pr_changes", List.of(
                        new Document("_id", "pc-1").append("prId", "pr-1").append("projectId", "proj-1--f1").append("snapshotKey", "k")
                                .append("change", new Document("_id", "dc-1").append("userId", "u1").append("operationType", "createClass")
                                        .append("operationData", new Document("iri", "http://x/A")).append("timestamp", date("2026-02-01T00:00:00Z")))),
                        Map.of("draft_pr_change", 1),
                        Map.of("draft_pull_requests", List.of(new Document("_id", "pr-1").append("projectId", "proj-1--f1")))),
                new Fixture("history_changes", "history_changes", List.of(
                        new Document("_id", "h-1").append("projectId", "proj-1--f1").append("editId", "e1").append("userId", "u1")
                                .append("timestamp", date("2026-02-01T00:00:00Z")).append("operationType", "ADD").append("entityIRI", "http://x/A")
                                .append("comments", new Document("u\uFF0E1", new Document("text", "hi").append("timestamp", date("2026-02-02T00:00:00Z"))))
                                .append("metadata", new Document("importId", "i1"))
                                .append("subChanges", List.of(new Document("_id", "sc-1").append("predicate", "p").append("addition", true)))
                                .append("ai", new Document("provider", "x")),
                        new Document("_id", "h-2").append("projectId", "proj-1--f1").append("documentBefore", "{\"_id\":{\"$oid\":\"abc\"}}")),
                        Map.of("history_change", 2)),
                new Fixture("ontology_changes", "ontology_changes", List.of(
                        new Document("_id", "oc-1").append("projectId", "proj-1--f1").append("changeType", "CLASS_ADDED")
                                .append("timestamp", date("2026-02-01T00:00:00Z")).append("entityIRI", "http://x/A")
                                .append("metadata", new Document("k", "v"))),
                        Map.of("ontology_change", 1)),
                new Fixture("rollback_audit", "rollback_audit", List.of(
                        new Document("_id", "ra-1").append("projectId", "proj-1--f1").append("historyChangeId", "h-1").append("direction", "UNDO")
                                .append("changeIds", List.of("h-1")).append("revertedAt", date("2026-02-03T00:00:00Z"))
                                .append("draftRecords", List.of(new Document("_id", "dc-1").append("projectId", "proj-1--f1")
                                        .append("timestamp", date("2026-02-01T00:00:00Z")))),
                        new Document("_id", "ra-2").append("projectId", "proj-1--f1")),
                        Map.of("rollback_audit", 2)),
                new Fixture("undo_intents", "undo_intents", List.of(
                        new Document("_id", "ui-1").append("projectId", "proj-1--f1").append("opId", "op-1").append("direction", "UNDO")
                                .append("changeIds", List.of("h-1")).append("stage", "STARTED").append("status", "RUNNING")
                                .append("createdAt", date("2026-02-01T00:00:00Z")).append("updatedAt", date("2026-02-01T00:00:00Z")),
                        new Document("_id", "ui-2").append("projectId", "proj-1--f1").append("direction", "UNDO")),
                        Map.of("undo_intent", 2)),
                new Fixture("assistant_audit", "assistant_audit", List.of(
                        new Document("_id", "aa-1").append("actor", "alice").append("projectId", "proj-1--f1").append("operation", "PROPOSE")
                                .append("sourceRevision", 3L).append("outcome", "OK")
                                .append("createdAt", java.util.Date.from(java.time.Instant.now().minus(java.time.Duration.ofDays(1))))),
                        Map.of("assistant_audit", 1)),
                new Fixture("assistant_apply_operations", "assistant_apply_operations", List.of(
                        new Document("_id", "ao-1").append("projectId", "proj-1--f1").append("groupId", "g1").append("status", "PREPARED")
                                .append("createdAt", date("2026-02-01T00:00:00Z")).append("updatedAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("assistant_apply_operation", 1)),
                new Fixture("datatype_definitions", "datatype_definitions", List.of(
                        new Document("_id", "dd-1").append("projectId", "proj-1--f1").append("datatypeIri", "http://x/dt").append("expression", "xsd:int[>0]")
                                .append("createdAt", date("2026-02-01T00:00:00Z")).append("updatedAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("datatype_definition", 1)),
                new Fixture("fuzzy_rules", "fuzzy_rules", List.of(
                        new Document("_id", "fr-1").append("projectId", "proj-1--f1").append("name", "r").append("condition", "a").append("action", "b")
                                .append("createdAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("fuzzy_rule", 1)),
                new Fixture("sparql_queries", "sparql_queries", List.of(
                        new Document("_id", "sq-1").append("projectId", "proj-1--f1").append("name", "q").append("queryText", "select * {?s ?p ?o}")
                                .append("createdAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("sparql_query", 1)),
                new Fixture("issue_reports", "issue_reports", List.of(
                        new Document("_id", "ir-1").append("title", "Bug").append("userEmail", "alice@example.com").append("projectId", "proj-1--f1")
                                .append("systemInfo", new Document("osName", "Win").append("javaVersion", "21"))
                                .append("attachmentFileNames", List.of("a.png")).append("status", "CREATED").append("createdAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("issue_report", 1)),
                new Fixture("spreadsheet_imports", "spreadsheet_imports", List.of(
                        new Document("_id", "si-1").append("projectId", "proj-1--f1").append("userId", "u1").append("fileName", "a.xlsx").append("draft", true)
                                .append("addedTriples", List.of("<a> <b> <c> .")).append("axiomCount", 3).append("createdAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("spreadsheet_import_record", 1)),
                new Fixture("spreadsheet_rule_sets", "spreadsheet_rule_sets", List.of(
                        new Document("_id", "rs-1").append("projectId", "proj-1--f1").append("name", "set").append("createdBy", "alice")
                                .append("rules", List.of(new Document("sheetName", "S1").append("startColumn", 1).append("rule", "r").append("active", true)))
                                .append("createdAt", date("2026-02-01T00:00:00Z")).append("updatedAt", date("2026-02-01T00:00:00Z"))),
                        Map.of("spreadsheet_rule_set", 1)));
    }

    public static Map<String, Integer> expectedRows(List<Fixture> fixtures) {
        Map<String, Integer> out = new LinkedHashMap<>();
        fixtures.forEach(f -> f.rows().forEach((t, n) -> out.merge(t, n, Integer::sum)));
        return out;
    }
}
