package self.research.ontology.migrator.support;

import org.springframework.jdbc.core.JdbcTemplate;
import self.research.ontology.common.db.testing.EmbeddedTestDatabase;

import javax.sql.DataSource;

public final class TestDb {

    private static final String TABLES = "app_user, workspace, workspace_member, project, project_member, project_file, "
            + "file_metadata, invitation, stripe_event, email_log, stripe_unmatched_invoice, system_settings, "
            + "plan_feature_config, desktop_download_event, audit_event, plugin, plugin_version, plugin_rating, "
            + "plugin_user_install, swrl_rule, reasoning_job, reasoning_snapshot, ontology_file, project_share, "
            + "user_project_preference, project_public_graph_version, project_write_lease, project_recovery_lock, "
            + "app_migration, draft_change, draft_change_parked, draft_session, draft_pull_request, draft_pr_change, "
            + "history_change, ontology_change, rollback_audit, undo_intent, assistant_audit, assistant_apply_operation, "
            + "datatype_definition, fuzzy_rule, sparql_query, issue_report, spreadsheet_import_record, spreadsheet_rule_set, "
            + "stored_file, file_chunk, migration_checkpoint";

    private TestDb() {
    }

    public static DataSource dataSource() {
        return EmbeddedTestDatabase.dataSource();
    }

    public static JdbcTemplate jdbc() {
        return EmbeddedTestDatabase.jdbc();
    }

    public static JdbcTemplate clean() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("truncate table " + TABLES + " cascade");
        return jdbc;
    }
}
