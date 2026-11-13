package self.research.ontology.migrator.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OrphanChecks {

    static final Map<String, String> CHECKS = new LinkedHashMap<>();

    static {
        add("project.workspace_id without workspace", "project p", "p.workspace_id is not null",
                "workspace w where w.workspace_id = p.workspace_id or w.id = p.workspace_id");
        add("project.owner_id without user", "project p", "p.owner_id is not null", "app_user u where u.id = p.owner_id");
        add("workspace.owner_id without user", "workspace w", "w.owner_id is not null", "app_user u where u.id = w.owner_id");
        add("workspace_member.user_id without user", "workspace_member m", "m.user_id is not null",
                "app_user u where u.id = m.user_id");
        add("project_member.user_id without user", "project_member m", "m.user_id is not null",
                "app_user u where u.id = m.user_id");
        add("file_metadata.project_id without project", "file_metadata f", "f.project_id is not null",
                "project p where p.project_id = f.project_id or p.id = f.project_id");
        add("file_metadata.blob_id without stored file", "file_metadata f", "f.blob_id is not null",
                "stored_file s where s.id = f.blob_id");
        add("ontology_file.blob_id without stored file", "ontology_file f", "f.blob_id is not null",
                "stored_file s where s.id = f.blob_id");
        add("ontology_file.project_id without project", "ontology_file f", "f.project_id is not null",
                "project p where p.project_id = f.project_id or p.id = f.project_id");
        add("plugin_version.plugin_id without plugin", "plugin_version v", "true",
                "plugin p where p.plugin_id = v.plugin_id");
        add("plugin_version.vsix_file_id without stored file", "plugin_version v", "v.vsix_file_id is not null",
                "stored_file s where s.id = v.vsix_file_id");
        add("plugin_rating.plugin_id without plugin", "plugin_rating r", "true", "plugin p where p.plugin_id = r.plugin_id");
        add("plugin_user_install.plugin_id without plugin", "plugin_user_install r", "true",
                "plugin p where p.plugin_id = r.plugin_id");
        add("project_share.project_id without file", "project_share s", "true", "ontology_file f where f.id = s.project_id");
        add("swrl_rule.project_id without file or project", "swrl_rule r", "true",
                "ontology_file f where f.id = r.project_id union all select 1 from project p "
                        + "where p.project_id = r.project_id or p.id = r.project_id");
        add("user_project_preference.project_id without file", "user_project_preference r", "true",
                "ontology_file f where f.id = r.project_id");
        add("project_write_lease.project_id without file", "project_write_lease r", "true",
                "ontology_file f where f.id = r.project_id");
        add("project_recovery_lock.project_id without file", "project_recovery_lock r", "true",
                "ontology_file f where f.id = r.project_id");
        add("project_public_graph_version.project_id without file", "project_public_graph_version r", "true",
                "ontology_file f where f.id = r.project_id");
    }

    private final DataSource dataSource;

    public OrphanChecks(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static void add(String name, String from, String condition, String existsClause) {
        CHECKS.put(name, "select count(*) from " + from + " where " + condition + " and not exists (select 1 from " + existsClause + ")");
    }

    public Map<String, Long> run() {
        Map<String, Long> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (Map.Entry<String, String> e : CHECKS.entrySet()) {
                try (ResultSet rs = st.executeQuery(e.getValue())) {
                    rs.next();
                    out.put(e.getKey(), rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Orphan check failed: " + e.getMessage(), e);
        }
        return out;
    }
}
