package self.research.ontology.common.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

public final class ProjectScopedTables {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectScopedTables.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final List<String> NAMES = new CopyOnWriteArrayList<>();

    static {
        register(
                "ontology_file",
                "project_share",
                "user_project_preference",
                "project_public_graph_version",
                "project_write_lease",
                "project_recovery_lock",
                "draft_change",
                "draft_change_parked",
                "draft_session",
                "draft_pull_request",
                "draft_pr_change",
                "history_change",
                "ontology_change",
                "rollback_audit",
                "undo_intent",
                "class_detail",
                "entity_usage",
                "hierarchy_snapshot",
                "top_level_class_cache",
                "assistant_session",
                "assistant_edit_group",
                "assistant_audit",
                "assistant_apply_operation",
                "datatype_definition",
                "fuzzy_rule",
                "sparql_query",
                "spreadsheet_import_record",
                "spreadsheet_rule_set",
                "swrl_rule");
    }

    private ProjectScopedTables() {
    }

    public static void register(String... tables) {
        for (String table : tables) {
            if (!IDENTIFIER.matcher(table).matches()) {
                throw new IllegalArgumentException("Invalid table name: " + table);
            }
            if (!NAMES.contains(table)) {
                NAMES.add(table);
            }
        }
    }

    public static List<String> names() {
        return List.copyOf(NAMES);
    }

    public static int purge(JdbcTemplate jdbc, String projectId) {
        int removed = 0;
        for (String table : NAMES) {
            try {
                removed += jdbc.update("delete from " + table + " where project_id = ?", projectId);
            } catch (BadSqlGrammarException e) {
                LOG.debug("[Purge] Table {} does not exist yet, skipping", table);
            } catch (DataAccessException e) {
                throw e;
            }
        }
        return removed;
    }
}
