package self.research.ontology.owlEditor.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import self.research.ontology.common.db.sql.Ids;
import self.research.ontology.common.db.sql.Instants;
import self.research.ontology.owlEditor.document.UserProjectPreferences;

import java.time.Instant;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "ontocode.db.impl", havingValue = "jdbc", matchIfMissing = true)
public class JdbcUserProjectPreferencesRepository implements UserProjectPreferencesRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserProjectPreferencesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserProjectPreferences> findByUserEmailAndProjectId(String userEmail, String projectId) {
        return EditorSql.first(jdbc.query("select * from user_project_preference where user_email = ? and project_id = ?",
                (rs, i) -> new UserProjectPreferences(rs.getString("id"), rs.getString("user_email"),
                        rs.getString("project_id"), rs.getString("sync_mode"), Instants.read(rs, "updated_at")),
                userEmail, projectId));
    }

    @Override
    public void saveSyncMode(String userEmail, String projectId, String syncMode, Instant now) {
        jdbc.update("insert into user_project_preference (id, user_email, project_id, sync_mode, updated_at) "
                        + "values (?, ?, ?, ?, ?) on conflict (user_email, project_id) do update set "
                        + "sync_mode = excluded.sync_mode, updated_at = excluded.updated_at",
                Ids.newObjectId(), userEmail, projectId, syncMode, Instants.toDb(now));
    }
}
