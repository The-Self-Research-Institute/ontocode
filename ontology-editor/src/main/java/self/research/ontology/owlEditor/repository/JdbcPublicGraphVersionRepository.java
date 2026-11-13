package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Instants;

import java.time.Instant;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcPublicGraphVersionRepository implements PublicGraphVersionRepository {

    private final JdbcTemplate jdbc;

    public JdbcPublicGraphVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long increment(String projectId, Instant now) {
        Long version = jdbc.queryForObject("insert into project_public_graph_version (project_id, version, updated_at) "
                        + "values (?, 1, ?) on conflict (project_id) do update set "
                        + "version = project_public_graph_version.version + 1, updated_at = excluded.updated_at "
                        + "returning version",
                Long.class, projectId, Instants.toDb(now));
        if (version == null) {
            throw new IllegalStateException("Public graph version increment returned no row for " + projectId);
        }
        return version;
    }

    @Override
    public long read(String projectId) {
        List<Long> rows = jdbc.queryForList("select version from project_public_graph_version where project_id = ?",
                Long.class, projectId);
        return rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
    }
}
