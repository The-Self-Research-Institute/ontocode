package self.research.ontology.auth.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.ProjectScopedTables;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcProjectDataPurgeRepository implements ProjectDataPurgeRepository {

    private final JdbcTemplate jdbc;

    public JdbcProjectDataPurgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int purgeEditorData(String projectId) {
        return ProjectScopedTables.purge(jdbc, projectId);
    }
}
