package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import self.research.ontology.owlEditor.document.WorkspaceDocument;

import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcWorkspaceRepository implements WorkspaceRepository {

    private final JdbcTemplate jdbc;

    public JdbcWorkspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WorkspaceDocument> findByWorkspaceId(String workspaceId) {
        return EditorSql.first(jdbc.query("select id, workspace_id, owner_id, subscription_plan from workspace where workspace_id = ?",
                (rs, i) -> new WorkspaceDocument(rs.getString("id"), rs.getString("workspace_id"),
                        rs.getString("owner_id"), rs.getString("subscription_plan")), workspaceId));
    }
}
